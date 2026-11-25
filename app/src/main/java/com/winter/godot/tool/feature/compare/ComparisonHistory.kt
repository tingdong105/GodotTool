package com.winter.godot.tool.feature.compare

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.winter.godot.core.io.SafeFiles
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 无需原快照即可回看的对比记录。
 * @author Tingdong
 * @date 2026-11-25
 */
data class ComparisonRecord(val id: String, val createdAt: Long, val oldName: String, val newName: String,
    val before: SnapshotHeader, val after: SnapshotHeader, val counts: List<Int>, val publicName: String = "", val publicBase: String = PUBLIC_BASE,
    val memberCounts: List<Int> = List(ChangeKind.entries.size) { 0 }, val dataVersion: Int = 2) {
    val published: Boolean get() = publicName.isNotEmpty() && publicBase == PUBLIC_BASE
    val membersAvailable: Boolean get() = dataVersion >= 2 && before.membersIndexed && after.membersIndexed

    companion object {
        const val PUBLIC_BASE = "GodotTool/diff"
    }
}

/**
 * 私有记录及公开 change.txt 的独立存储。
 * @author Tingdong
 * @date 2026-11-25
 */
class ComparisonHistory(private val root: File, private val publicRoot: File,
    private val unknownLine: (String, MethodChange) -> String = ComparisonText::line) {
    /**
     * 加载轻量历史摘要，最新记录排在前面。
     * @return 可读取的私有记录。
     */
    fun list(): List<ComparisonRecord> = root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[0-9a-f-]{36}")) }
        .mapNotNull { runCatching { metadata(File(it, "record.json")) }.getOrNull() }.sortedByDescending { it.createdAt }

    /**
     * 原子保存完整差异及可读报告。
     * @param result 比较结果。
     * @param oldName 旧版输入名称。
     * @param newName 新版输入名称。
     * @param rules 保存时采用的筛选和关注规则。
     * @param check 取消检查。
     * @return 已保存记录。
     */
    fun save(result: ComparisonResult, oldName: String, newName: String, rules: List<ComparisonRule>, check: () -> Unit): ComparisonRecord {
        check(root.isDirectory || root.mkdirs()) { "COMPARISON_SAVE_FAILED" }
        val id = UUID.randomUUID().toString()
        val target = directory(id)
        val stage = File(root, ".$id.part")
        check(stage.mkdir()) { "COMPARISON_SAVE_FAILED" }
        val record = ComparisonRecord(id, System.currentTimeMillis(), oldName, newName, result.before, result.after,
            ChangeKind.entries.map { kind -> result.entries.sumOf { it.counts[kind.ordinal] } },
            memberCounts = ChangeKind.entries.map { kind -> result.entries.sumOf { it.memberCounts[kind.ordinal] } }, dataVersion = result.dataVersion)
        try {
            require(stage.usableSpace > result.file.length() * 3 + 1024 * 1024) { "COMPARISON_NO_SPACE" }
            result.file.inputStream().use { input -> File(stage, "result.bin").outputStream().use { output ->
                SafeFiles.copy(input, output, SnapshotFormat.MAX_BYTES * 4, check)
            } }
            JsonWriter.of(File(stage, "index.json").sink().buffer()).use { writer ->
                writer.beginArray()
                for (entry in result.entries) {
                    check()
                    writer.beginObject().name("path").value(entry.path).name("offset").value(entry.offset).name("counts").beginArray()
                    entry.counts.forEach { writer.value(it) }; writer.endArray()
                    writer.name("memberCounts").beginArray(); entry.memberCounts.forEach { writer.value(it) }; writer.endArray().endObject()
                }
                writer.endArray()
            }
            writeReport(File(stage, "change.txt"), record, result, ComparisonRules(rules), check)
            writeMetadata(File(stage, "record.json"), record)
            check()
            check(stage.renameTo(target)) { "COMPARISON_SAVE_FAILED" }
            return record
        } finally {
            if (stage.exists() && stage.canonicalFile.parentFile == root.canonicalFile) stage.deleteRecursively()
        }
    }

    /**
     * 打开完整历史结果，不依赖缓存或原快照。
     * @param id 记录标识。
     * @param check 取消检查。
     * @return 记录及磁盘差异索引。
     */
    fun open(id: String, check: () -> Unit): Pair<ComparisonRecord, ComparisonResult> {
        val folder = directory(id)
        val record = metadata(File(folder, "record.json"))
        require(record.id == id) { "COMPARISON_INVALID" }
        val data = File(folder, "result.bin")
        val index = File(folder, "index.json")
        require(data.isFile && data.length() <= SnapshotFormat.MAX_BYTES * 4 && index.length() in 1..SnapshotFormat.MAX_BYTES) { "COMPARISON_INVALID" }
        val entries = JsonReader.of(index.source().buffer()).use { reader ->
            val entries = mutableListOf<ComparisonEntry>()
            reader.beginArray()
            while (reader.hasNext()) {
                check()
                require(entries.size < SnapshotFormat.MAX_ENTRIES * 2) { "COMPARISON_INVALID" }
                var path = ""; var offset = -1L; var counts = emptyList<Int>()
                var memberCounts = List(ChangeKind.entries.size) { 0 }
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "path" -> path = reader.nextString()
                    "offset" -> offset = reader.nextLong()
                    "counts" -> counts = counts(reader)
                    "memberCounts" -> memberCounts = counts(reader)
                    else -> reader.skipValue()
                }
                reader.endObject()
                require(path == SafeFiles.relative(path) && offset >= 0 && offset < data.length() && counts.size == ChangeKind.entries.size) { "COMPARISON_INVALID" }
                require(entries.isEmpty() || entries.last().path < path) { "COMPARISON_INVALID" }
                entries += ComparisonEntry(path, offset, counts, memberCounts)
            }
            reader.endArray()
            require(reader.peek() == JsonReader.Token.END_DOCUMENT)
            entries
        }
        return record to ComparisonResult(record.before, record.after, entries, data, record.dataVersion)
    }

    /**
     * 发布单份公开报告，重试时不覆盖已有结果。
     * @param record 已提交的私有记录。
     * @param check 取消检查。
     * @return 带公开目录名称的记录。
     */
    @Synchronized
    fun publish(record: ComparisonRecord, check: () -> Unit): ComparisonRecord {
        if (record.published && record.publicName.matches(Regex("\\d{6}_\\d+")) && File(publicRoot, "${record.publicName}/change.txt").isFile) return record
        check(publicRoot.isDirectory || publicRoot.mkdirs()) { "COMPARISON_PUBLISH_FAILED" }
        val date = SimpleDateFormat("yyMMdd", Locale.ROOT).format(Date(record.createdAt))
        val pattern = Regex("${date}_(\\d+)")
        var number = (publicRoot.listFiles().orEmpty().mapNotNull { pattern.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        var folder: File
        while (true) {
            check(); require(number > 0) { "COMPARISON_PUBLISH_FAILED" }
            folder = SafeFiles.resolve(publicRoot, "${date}_${number++}")
            if (folder.mkdir()) break
            if (!folder.exists()) error("COMPARISON_PUBLISH_FAILED")
        }
        val temporary = File(folder, ".change.part")
        try {
            File(directory(record.id), "change.txt").inputStream().use { input -> temporary.outputStream().use { output ->
                SafeFiles.copy(input, output, SnapshotFormat.MAX_BYTES * 4, check)
            } }
            check()
            check(temporary.renameTo(File(folder, "change.txt"))) { "COMPARISON_PUBLISH_FAILED" }
            val published = record.copy(publicName = folder.name, publicBase = ComparisonRecord.PUBLIC_BASE)
            writeMetadata(File(directory(record.id), "record.json"), published)
            return published
        } finally { temporary.delete(); if (folder.listFiles()?.isEmpty() == true) folder.delete() }
    }

    /**
     * 删除用户选定的私有记录，公开报告保留。
     * @param ids 选中标识。
     * @param check 取消检查。
     */
    fun delete(ids: Set<String>, check: () -> Unit) {
        for (id in ids) { check(); val folder = directory(id); check(!folder.exists() || folder.deleteRecursively()) { "COMPARISON_DELETE_FAILED" } }
    }

    /**
     * 读取一条记录的手动关注标识。
     * @param id 记录标识。
     * @return 被关注的方法键。
     */
    fun stars(id: String): Set<String> {
        val file = File(directory(id), "stars.json")
        if (!file.isFile) return emptySet()
        require(file.length() <= SnapshotFormat.MAX_BYTES)
        return JsonReader.of(file.source().buffer()).use { reader ->
            val values = mutableSetOf<String>(); reader.beginArray()
            while (reader.hasNext()) { require(values.size < 100000); values += reader.nextString().also { require(it.length <= 32768) } }
            reader.endArray(); values
        }
    }

    /**
     * 保存手动关注标识。
     * @param id 记录标识。
     * @param values 关注方法集合。
     */
    fun stars(id: String, values: Set<String>) {
        require(values.size <= 100000)
        val target = File(directory(id), "stars.json"); val temporary = File(target.parentFile, ".stars.part")
        try {
            JsonWriter.of(temporary.sink().buffer()).use { writer -> writer.beginArray(); values.sorted().forEach { writer.value(it) }; writer.endArray() }
            check(temporary.renameTo(target)) { "COMPARISON_SAVE_FAILED" }
        } finally { temporary.delete() }
    }

    /**
     * 校验并定位单条私有记录。
     * @param id UUID 标识。
     * @return 记录目录。
     */
    private fun directory(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}"))) { "COMPARISON_INVALID" }
        return SafeFiles.resolve(root, id)
    }

    /**
     * 读取固定长度的变化计数。
     * @param reader 输入游标。
     * @return 各类别数量。
     */
    private fun counts(reader: JsonReader): List<Int> {
        val result = mutableListOf<Int>(); reader.beginArray()
        while (reader.hasNext()) { require(result.size < ChangeKind.entries.size); result += reader.nextInt().also { require(it >= 0) } }
        reader.endArray(); require(result.size == ChangeKind.entries.size); return result
    }

    /**
     * 读取轻量历史元数据。
     * @param file 元数据文件。
     * @return 已验证的摘要。
     */
    private fun metadata(file: File): ComparisonRecord {
        require(file.isFile && file.length() in 1..1048576)
        return JsonReader.of(file.source().buffer()).use { reader ->
            var id = ""; var created = 0L; var old = ""; var new = ""; var publicName = ""; var version = 0
            var publicBase = "Godot/diff"
            var dataVersion = 1
            var memberCounts = List(ChangeKind.entries.size) { 0 }
            var before: SnapshotHeader? = null; var after: SnapshotHeader? = null; var values = emptyList<Int>()
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "format" -> version = reader.nextInt()
                "id" -> id = reader.nextString()
                "createdAt" -> created = reader.nextLong()
                "oldName" -> old = reader.nextString()
                "newName" -> new = reader.nextString()
                "publicName" -> publicName = reader.nextString()
                "publicBase" -> publicBase = reader.nextString()
                "dataVersion" -> dataVersion = reader.nextInt()
                "memberCounts" -> memberCounts = counts(reader)
                "before" -> before = SnapshotIO.readHeader(reader)
                "after" -> after = SnapshotIO.readHeader(reader)
                "counts" -> values = counts(reader)
                else -> reader.skipValue()
            }
            reader.endObject()
            require(version == 1 && id.matches(Regex("[0-9a-f-]{36}")) && values.size == ChangeKind.entries.size && before != null && after != null)
            require(reader.peek() == JsonReader.Token.END_DOCUMENT)
            require(publicName.isEmpty() || publicName.matches(Regex("\\d{6}_\\d+")))
            require(publicBase in setOf("Godot/diff", ComparisonRecord.PUBLIC_BASE))
            require(dataVersion in 1..2)
            ComparisonRecord(id, created, old, new, before, after, values, publicName, publicBase, memberCounts, dataVersion)
        }
    }

    /**
     * 原子写入历史摘要。
     * @param file 私有元数据文件。
     * @param record 记录摘要。
     */
    private fun writeMetadata(file: File, record: ComparisonRecord) {
        val temporary = File(file.parentFile, ".record.part")
        try {
            JsonWriter.of(temporary.sink().buffer()).use { writer ->
                writer.beginObject().name("format").value(1).name("id").value(record.id).name("createdAt").value(record.createdAt)
                writer.name("oldName").value(record.oldName).name("newName").value(record.newName).name("publicName").value(record.publicName)
                writer.name("publicBase").value(record.publicBase)
                writer.name("dataVersion").value(record.dataVersion)
                writer.name("before"); SnapshotIO.writeHeader(writer, record.before)
                writer.name("after"); SnapshotIO.writeHeader(writer, record.after)
                writer.name("counts").beginArray(); record.counts.forEach { writer.value(it) }; writer.endArray()
                writer.name("memberCounts").beginArray(); record.memberCounts.forEach { writer.value(it) }; writer.endArray().endObject()
            }
            check(temporary.renameTo(file)) { "COMPARISON_SAVE_FAILED" }
        } finally { temporary.delete() }
    }

    /**
     * 分类别流式生成可读报告。
     * @param target 私有报告目标。
     * @param record 记录摘要。
     * @param result 磁盘差异。
     * @param rules 保存时的规则。
     * @param check 取消检查。
     */
    private fun writeReport(target: File, record: ComparisonRecord, result: ComparisonResult, rules: ComparisonRules, check: () -> Unit) {
        val modes = listOf(ComparisonMode.METHODS, ComparisonMode.CONSTANTS)
        val sections = modes.flatMap { mode -> mode.kinds.map { mode to it } }
            .associateWith { File(target.parentFile, ".section-${it.first.name}-${it.second.name}") }
        val writers = sections.mapValues { it.value.bufferedWriter(Charsets.UTF_8) }
        val visible = sections.keys.associateWith { 0 }.toMutableMap()
        try {
            for (entry in result.entries) {
                check()
                for (change in SnapshotDiff.read(result, entry).changes) {
                    check()
                    if (!change.mode.includes(change) || rules.excludes(entry.path, change)) continue
                    val key = change.mode to change.kind
                    visible[key] = visible.getValue(key) + 1
                    val writer = writers.getValue(key)
                    if (rules.focuses(entry.path, change)) writer.append("⭐ ")
                    writer.appendLine(if (change.kind == ChangeKind.UNKNOWN) unknownLine(entry.path, change) else ComparisonText.line(entry.path, change))
                }
            }
            writers.values.forEach { it.close() }
            target.bufferedWriter(Charsets.UTF_8).use { output ->
                output.appendLine("GodotTool 版本对比结果")
                output.appendLine("${record.oldName} → ${record.newName}")
                output.appendLine("${record.before.version} → ${record.after.version}")
                if (!record.before.complete || !record.after.complete || !record.before.catalogComplete || !record.after.catalogComplete ||
                    record.counts[ChangeKind.UNKNOWN.ordinal] > 0) output.appendLine("部分内容无法分析，无法确认的内容不计入新增或删除。")
                for (mode in modes) {
                    output.appendLine(); output.appendLine(if (mode == ComparisonMode.METHODS) "[方法模式]" else "[字段模式]")
                    if (mode == ComparisonMode.CONSTANTS && !record.membersAvailable) {
                        output.appendLine("旧快照不含字段明细，请重新生成两个版本的 diff.gt 后对比。")
                        continue
                    }
                    val labels = if (mode == ComparisonMode.METHODS) listOf("增加的方法", "修改的方法", "删除的方法")
                        else listOf("增加的字段", "修改的字段", "删除的字段", "其它声明修改", "无法确认")
                    val original = if (mode == ComparisonMode.METHODS) record.counts else record.memberCounts
                    for (kind in mode.kinds) {
                        val count = visible.getValue(mode to kind)
                        output.appendLine("${labels[kind.ordinal]}：$count（原始 ${original[kind.ordinal]}，过滤 ${original[kind.ordinal] - count}）")
                    }
                    for (kind in mode.kinds) {
                        check(); if (visible.getValue(mode to kind) == 0) continue
                        output.appendLine(); output.appendLine("[${labels[kind.ordinal]}]")
                        sections.getValue(mode to kind).bufferedReader(Charsets.UTF_8).use { input ->
                            val buffer = CharArray(8192)
                            while (true) { check(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                        }
                    }
                }
            }
            require(target.length() <= SnapshotFormat.MAX_BYTES * 4) { "COMPARISON_SIZE_LIMIT" }
        } finally { writers.values.forEach { runCatching { it.close() } }; sections.values.forEach { it.delete() } }
    }
}
