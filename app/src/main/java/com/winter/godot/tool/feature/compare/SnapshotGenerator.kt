package com.winter.godot.tool.feature.compare

import android.content.Context
import com.winter.godot.core.io.GodotDependencies
import com.winter.godot.core.io.ModelDependencies
import com.winter.godot.core.io.SafeFiles
import com.winter.godot.core.model.CoreProgress
import com.winter.godot.core.model.RecoverySelection
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/**
 * 在清理恢复中间文件前生成版本快照。
 * @author Tingdong
 * @date 2026-11-25
 */
class SnapshotGenerator(private val context: Context) {
    /**
     * 分析全量代码清单，流式保存快照。
     * 使用示例：generator.generate(workspace, summary, check, progress)。
     * @param directory 私有任务目录。
     * @param summary 原恢复摘要，补充快照状态。
     * @param check 取消检查。
     * @param progress 真实文件进度。
     */
    fun generate(directory: File, summary: JSONObject, check: () -> Unit, progress: (CoreProgress) -> Unit) {
        val catalog = File(directory, "snapshot-catalog.json")
        require(catalog.isFile && catalog.length() in 1..SnapshotFormat.MAX_BYTES) { "SNAPSHOT_CATALOG_MISSING" }
        val array = JSONArray(catalog.readText())
        require(array.length() <= SnapshotFormat.MAX_ENTRIES) { "SNAPSHOT_ENTRY_LIMIT" }
        val records = (0 until array.length()).map { array.getJSONObject(it) }.sortedBy { it.getString("path") }
        val spool = File(directory, ".snapshot-records.part")
        var failed = 0
        var methods = 0
        try {
            DataOutputStream(spool.outputStream().buffered()).use { output ->
                for ((index, row) in records.withIndex()) {
                    check()
                    val path = SafeFiles.relative(row.getString("path"))
                    val container = row.getBoolean("container")
                    val record = try {
                        val candidates = listOf(SafeFiles.resolve(File(directory, "recovered"), path), SafeFiles.resolve(File(directory, "raw"), path)) +
                            row.getJSONArray("originals").let { originals -> (0 until originals.length()).map { SafeFiles.resolve(File(directory, "raw"), originals.getString(it)) } }
                        val expectedExtensions = if (container) setOf("tscn", "tres") else setOf("gd")
                        val source = candidates.firstOrNull { it.isFile && it.extension.lowercase() in expectedExtensions } ?: error("RECOVERY_FAILED")
                        require(source.length() <= SnapshotFormat.MAX_SOURCE) { "SOURCE_LIMIT" }
                        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        val text = InputStreamReader(source.inputStream(), decoder).use { it.readText() }
                        if (container) EmbeddedScripts.analyze(path, text, check) else ScriptAnalyzer.analyze(path, text, check)
                    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (error: Exception) {
                        failed++
                        ScriptSnapshot(path, container, "failed", SnapshotReasonText.code(error))
                    }
                    methods += record.methods.size
                    writeRecord(output, record)
                    require(spool.length() <= SnapshotFormat.MAX_BYTES) { "SNAPSHOT_SIZE_LIMIT" }
                    progress(CoreProgress("snapshot", path, files = index + 1, totalFiles = records.size))
                }
            }
            val inputManifest = JSONArray(File(directory, "inputs.json").readText())
            val input = inputManifest.optJSONObject(0)
            val apk = input?.optString("local")?.let { SafeFiles.resolve(directory, it) }?.takeIf { it.extension.equals("apk", true) }
            val info = apk?.let { context.packageManager.getPackageArchiveInfo(it.absolutePath, 0) }
            val toolVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            val header = SnapshotHeader(input?.optString("name").orEmpty(), info?.packageName.orEmpty(), info?.versionName.orEmpty(),
                summary.optString("engine"), "GodotTool $toolVersion", "GDRE 2.7.4", catalogComplete = true, complete = failed == 0, membersIndexed = true)
            DataInputStream(spool.inputStream().buffered()).use { inputStream ->
                SnapshotIO.write(File(directory, SnapshotFormat.FILE_NAME), header, sequence {
                    repeat(records.size) { check(); yield(readRecord(inputStream)) }
                }, check)
            }
            summary.put("snapshotStatus", if (failed == 0) "complete" else "partial").put("snapshotFailures", failed)
                .put("snapshotRecords", records.size).put("snapshotMethods", methods)
            if (failed > 0 && !summary.has("warning")) summary.put("warning", "SNAPSHOT_PARTIAL")
        } finally { spool.delete() }
    }

    /**
     * 移除仅为快照提取的资源，保留原导出与依赖闭包。
     * @param directory 私有任务目录。
     * @param selection 原导出范围。
     * @param fallbacks 原导出需要的转换失败依赖。
     * @param check 取消检查。
     */
    fun prune(directory: File, selection: RecoverySelection, fallbacks: List<String>, check: () -> Unit) {
        val keep = retainedFiles(directory, selection, fallbacks, check)
        for (name in listOf("raw", "recovered")) {
            for ((path, file) in collect(File(directory, name), check)) {
                check()
                if ("$name/$path" !in keep) require(file.delete()) { "SNAPSHOT_CLEANUP_FAILED" }
            }
        }
        // 保留源码映射需要的索引，但仅包含原导出所保留的资源。
        val indexFile = File(directory, "raw-index.json")
        if (indexFile.isFile && indexFile.length() <= SnapshotFormat.MAX_BYTES) {
            val entries = JSONArray(indexFile.readText())
            val filtered = JSONArray()
            for (i in 0 until entries.length()) if ("raw/${entries.getJSONObject(i).getString("path")}" in keep) filtered.put(entries.getJSONObject(i))
            indexFile.writeText(filtered.toString())
        }
    }

    companion object {
        /**
         * 计算原导出范围及其实际依赖，供清理和中断快照共用。
         * 使用示例：SnapshotGenerator.retainedFiles(workspace, selection, emptyList(), check)。
         * @param directory 私有任务目录。
         * @param selection 原导出范围。
         * @param fallbacks 转换失败仍需保留的依赖。
         * @param check 取消检查。
         * @return 带 raw/recovered 前缀的保留路径。
         */
        fun retainedFiles(directory: File, selection: RecoverySelection, fallbacks: List<String>, check: () -> Unit): Set<String> {
            val raw = collect(File(directory, "raw"), check)
            val restored = collect(File(directory, "recovered"), check)
            val dependencies = ModelDependencies.collect(raw + restored, selection::includes, check)
            val keep = raw.keys.filterTo(mutableSetOf(), selection::needsForRecovery)
            keep += dependencies
            keep += fallbacks.map(SafeFiles::relative)
            val queue = ArrayDeque(keep.toList())
            while (queue.isNotEmpty()) {
                check()
                val path = queue.removeFirst()
                val file = raw[path] ?: continue
                if (file.extension in setOf("remap", "import") && file.length() <= 2097152) {
                    for (dependency in GodotDependencies.paths(file.readText())) if (keep.add(dependency)) queue.addLast(dependency)
                }
            }
            return keep.mapTo(mutableSetOf()) { "raw/$it" } + restored.keys.filter { selection.includes(it) || it in dependencies }.map { "recovered/$it" }
        }

        /**
         * 校验边界并收集任务目录内文件。
         * @param root 指定内容目录。
         * @param check 取消检查。
         * @return 原项目相对路径到文件的映射。
         */
        private fun collect(root: File, check: () -> Unit): Map<String, File> {
            if (!root.isDirectory) return emptyMap()
            val files = linkedMapOf<String, File>()
            val boundary = root.canonicalFile.toPath()
            root.walkTopDown().onEnter { check(); require(it.canonicalFile.toPath().startsWith(boundary)); true }
                .filter { it.isFile }.forEach { file ->
                    check()
                    require(files.size < SnapshotFormat.MAX_ENTRIES && file.canonicalFile.toPath().startsWith(boundary)) { "UNSAFE_PATH" }
                    files[SafeFiles.relative(file.relativeTo(root).invariantSeparatorsPath)] = file
                }
            return files
        }

        /**
         * 为资源恢复选择额外脚本与容器。
         * 使用示例：SnapshotGenerator.needs("scripts/main.gdc")。
         * @param path 资源原路径。
         * @return 是否属于快照输入。
         */
        fun needs(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in setOf("gd", "gdc", "gde", "tscn", "scn", "tres", "res", "remap", "import", "uid")
    }

    /**
     * 将单个脚本写入临时顺序文件。
     * @param output 顺序输出。
     * @param record 单个分析结果。
     */
    private fun writeRecord(output: DataOutputStream, record: ScriptSnapshot) {
        output.writeUTF(record.path); output.writeBoolean(record.container); output.writeUTF(record.status)
        output.writeUTF(record.reason); output.writeUTF(record.outside); output.writeInt(record.methods.size)
        for (method in record.methods) { output.writeUTF(method.key); output.writeUTF(method.signature); output.writeUTF(method.declaration); output.writeUTF(method.body) }
        output.writeInt(record.members.size)
        for (member in record.members) { output.writeUTF(member.key); output.writeUTF(member.signature); output.writeUTF(member.fingerprint) }
    }

    /**
     * 读取由当前生成任务写入的单个脚本。
     * @param input 顺序输入。
     * @return 分析结果。
     */
    private fun readRecord(input: DataInputStream): ScriptSnapshot {
        val path = input.readUTF(); val container = input.readBoolean(); val status = input.readUTF()
        val reason = input.readUTF(); val outside = input.readUTF(); val count = input.readInt()
        require(count in 0..100000)
        val methods = List(count) { MethodSnapshot(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF()) }
        val members = input.readInt().also { require(it in 0..100000) }
        return ScriptSnapshot(path, container, status, reason, outside, methods, List(members) { MemberSnapshot(input.readUTF(), input.readUTF(), input.readUTF()) })
    }
}
