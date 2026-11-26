package com.winter.godot.tool.feature.compare

import java.io.File
import java.io.RandomAccessFile

/**
 * 流式合并快照并将差异写入私有索引。
 * @author Tingdong
 * @date 2026-11-25
 */
object SnapshotDiff {
    /**
     * 对比两个快照，只让单个脚本驻留内存。
     * 使用示例：SnapshotDiff.compare(old, new, output, check) { count, bytes, total -> }。
     * @param before 旧版本输入。
     * @param after 新版本输入。
     * @param output 私有结果文件。
     * @param check 取消检查。
     * @param progress 已合并脚本数、已对比字节数与输入总字节数。
     * @return 来源与磁盘结果索引。
     */
    fun compare(before: File, after: File, output: File, check: () -> Unit, progress: (Int, Long, Long) -> Unit): ComparisonResult {
        try {
            SnapshotIO.open(before).use { old -> SnapshotIO.open(after).use { new ->
                require(old.header.packageName.isEmpty() || new.header.packageName.isEmpty() || old.header.packageName == new.header.packageName) { "SNAPSHOT_DIFFERENT_APP" }
                val entries = mutableListOf<ComparisonEntry>()
                val totalBytes = old.totalBytes + new.totalBytes
                var processed = 0
                progress(0, 0L, totalBytes)
                RandomAccessFile(output, "rw").use { file ->
                    file.setLength(0)
                    var a = old.next()
                    var b = new.next()
                    var oldBytes = 0L
                    var newBytes = 0L
                    while (a != null || b != null) {
                        check()
                        val path = if (a == null) b!!.path else if (b == null) a.path else minOf(a.path, b.path)
                        val left = a?.takeIf { it.path == path }
                        val right = b?.takeIf { it.path == path }
                        val change = compareScript(left, right, old.header.catalogComplete, new.header.catalogComplete,
                            old.header.membersIndexed && new.header.membersIndexed)
                        if (change.changes.isNotEmpty()) {
                            val offset = file.filePointer
                            write(file, change)
                            require(file.length() <= SnapshotFormat.MAX_BYTES * 4) { "COMPARISON_SIZE_LIMIT" }
                            entries += ComparisonEntry(path, offset,
                                ChangeKind.entries.map { kind -> change.changes.count { it.kind == kind && it.mode == ComparisonMode.METHODS } },
                                ChangeKind.entries.map { kind -> change.changes.count { it.kind == kind && it.mode == ComparisonMode.CONSTANTS } })
                        }
                        if (left != null) { oldBytes = old.bytesRead; a = old.next() }
                        if (right != null) { newBytes = new.bytesRead; b = new.next() }
                        progress(++processed, oldBytes + newBytes, totalBytes)
                    }
                }
                progress(processed, totalBytes, totalBytes)
                return ComparisonResult(old.header, new.header, entries, output)
            } }
        } catch (error: Throwable) { output.delete(); throw error }
    }

    /**
     * 根据覆盖状态判定单个脚本的变化。
     * @param before 旧记录。
     * @param after 新记录。
     * @param oldComplete 旧目录清单是否完整。
     * @param newComplete 新目录清单是否完整。
     * @param membersEnabled 双方是否均包含成员明细。
     * @return 确定变化或无法确认。
     */
    fun compareScript(before: ScriptSnapshot?, after: ScriptSnapshot?, oldComplete: Boolean, newComplete: Boolean, membersEnabled: Boolean = true): ScriptChange {
        val path = before?.path ?: requireNotNull(after).path
        val typeChanged = before != null && after != null && before.container != after.container
        if (before?.status == "failed" || after?.status == "failed" || before == null && !oldComplete || after == null && !newComplete ||
            typeChanged) {
            val oldReason = reason(before, oldComplete, typeChanged)
            val newReason = reason(after, newComplete, typeChanged)
            return ScriptChange(path, listOfNotNull(MethodChange(ChangeKind.UNKNOWN, before = oldReason, after = newReason),
                if (membersEnabled) MethodChange(ChangeKind.UNKNOWN, before = oldReason, after = newReason, mode = ComparisonMode.CONSTANTS) else null))
        }
        val alignedBefore = if (before?.container == true && after != null) {
            EmbeddedScriptMatching.align(before, after) ?: return ScriptChange(path, listOfNotNull(
                MethodChange(ChangeKind.UNKNOWN, before = "EMBEDDED_IDENTITY_UNKNOWN", after = "EMBEDDED_IDENTITY_UNKNOWN"),
                if (membersEnabled) MethodChange(ChangeKind.UNKNOWN, before = "EMBEDDED_IDENTITY_UNKNOWN", after = "EMBEDDED_IDENTITY_UNKNOWN", mode = ComparisonMode.CONSTANTS) else null))
        } else before
        val old = alignedBefore?.methods.orEmpty().associateBy { it.key }
        val new = after?.methods.orEmpty().associateBy { it.key }
        val changes = (old.keys + new.keys).sorted().mapNotNull { key ->
            val a = old[key]; val b = new[key]
            when {
                a == null -> MethodChange(ChangeKind.ADDED, key, after = b!!.signature)
                b == null -> MethodChange(ChangeKind.REMOVED, key, before = a.signature)
                a.declaration != b.declaration || a.body != b.body -> MethodChange(ChangeKind.MODIFIED, key, a.signature, b.signature,
                    a.declaration != b.declaration, a.body != b.body)
                else -> null
            }
        }.toMutableList()
        if (membersEnabled) {
            val oldMembers = alignedBefore?.members.orEmpty().associateBy { it.key }
            val newMembers = after?.members.orEmpty().associateBy { it.key }
            for (key in (oldMembers.keys + newMembers.keys).sorted()) {
                val a = oldMembers[key]; val b = newMembers[key]
                val change = when {
                    a == null -> MethodChange(ChangeKind.ADDED, key, after = b!!.signature, mode = ComparisonMode.CONSTANTS)
                    b == null -> MethodChange(ChangeKind.REMOVED, key, before = a.signature, mode = ComparisonMode.CONSTANTS)
                    a.fingerprint != b.fingerprint -> MethodChange(ChangeKind.MODIFIED, key, a.signature, b.signature, mode = ComparisonMode.CONSTANTS)
                    else -> null
                }
                if (change != null) changes += change
            }
        }
        return ScriptChange(path, changes)
    }

    /**
     * 保留每个版本无法参与比较的具体原因。
     * @param script 单侧脚本，可能缺失。
     * @param complete 该版本文件清单是否完整。
     * @param typeChanged 两侧脚本容器类型是否发生变化。
     * @return 稳定诊断代码，成功和确认缺失也单独标记。
     */
    private fun reason(script: ScriptSnapshot?, complete: Boolean, typeChanged: Boolean): String = when {
        script?.status == "failed" -> script.reason.ifBlank { "ANALYSIS_FAILED" }
        script == null -> if (complete) "NOT_PRESENT" else "CATALOG_INCOMPLETE"
        typeChanged -> "CONTAINER_CHANGED"
        else -> "ANALYSIS_OK"
    }

    /**
     * 按偏移读取一个脚本的差异。
     * @param result 对比结果。
     * @param entry 轻量索引项。
     * @return 可展示条目。
     */
    fun read(result: ComparisonResult, entry: ComparisonEntry): ScriptChange = RandomAccessFile(result.file, "r").use { file -> read(file, entry, result.dataVersion) }

    /**
     * 复用已打开的结果句柄顺序检索。
     * @param file 当前结果句柄。
     * @param entry 脚本索引。
     * @param dataVersion 持久结果的二进制版本。
     * @return 单个脚本的差异。
     */
    internal fun read(file: RandomAccessFile, entry: ComparisonEntry, dataVersion: Int): ScriptChange {
        file.seek(entry.offset)
        val count = file.readInt()
        require(dataVersion in 1..2 && count in 1..400002) { "COMPARISON_INVALID" }
        return ScriptChange(entry.path, List(count) { readChange(file, dataVersion) })
    }

    /**
     * 流式验证备份中的单条脚本，避免同时保留全部方法字符串。
     * @param file 已打开的二进制结果。
     * @param entry 对应索引。
     * @param dataVersion 二进制格式版本。
     * @param check 取消检查。
     */
    internal fun validate(file: RandomAccessFile, entry: ComparisonEntry, dataVersion: Int, check: () -> Unit) {
        require(file.filePointer == entry.offset && dataVersion in 1..2) { "COMPARISON_INVALID" }
        val count = file.readInt()
        require(count in 1..400002) { "COMPARISON_INVALID" }
        val methods = MutableList(ChangeKind.entries.size) { 0 }
        val members = MutableList(ChangeKind.entries.size) { 0 }
        repeat(count) {
            check()
            val change = readChange(file, dataVersion)
            val counts = if (change.mode == ComparisonMode.METHODS) methods else members
            counts[change.kind.ordinal]++
        }
        require(methods == entry.counts && members == entry.memberCounts) { "COMPARISON_INVALID" }
    }

    /**
     * 统一解码并校验单条变化的枚举范围。
     * @param file 当前结果句柄。
     * @param dataVersion 二进制版本。
     * @return 单条方法或字段变化。
     */
    private fun readChange(file: RandomAccessFile, dataVersion: Int): MethodChange {
        val change = MethodChange(ChangeKind.entries[file.readInt()], file.readUTF(), file.readUTF(), file.readUTF(), file.readBoolean(), file.readBoolean())
        return if (dataVersion >= 2) change.copy(mode = ComparisonMode.entries[file.readInt()].also {
            require(it != ComparisonMode.FOCUS) { "COMPARISON_INVALID" }
        }) else change
    }

    /**
     * 保存一个脚本的可展开结果。
     * @param file 私有结果文件。
     * @param value 脚本差异。
     */
    private fun write(file: RandomAccessFile, value: ScriptChange) {
        file.writeInt(value.changes.size)
        for (change in value.changes) {
            file.writeInt(change.kind.ordinal); file.writeUTF(change.key); file.writeUTF(change.before); file.writeUTF(change.after)
            file.writeBoolean(change.declarationChanged); file.writeBoolean(change.bodyChanged)
            file.writeInt(change.mode.ordinal)
        }
    }
}
