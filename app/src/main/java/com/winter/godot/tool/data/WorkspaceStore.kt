package com.winter.godot.tool.data

import android.content.Context
import com.winter.godot.core.io.SafeFiles
import com.winter.godot.core.model.CoreFailure
import com.winter.godot.tool.feature.compare.SnapshotReasonText
import com.winter.godot.tool.feature.files.StorageAccess
import com.winter.godot.tool.task.EngineExitDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 管理工作区文件、索引和导出边界。
 * @author Tingdong
 * @date 2026-11-21
 */
class WorkspaceStore(private val context: Context) {
    private val root = File(context.filesDir, "workspaces").apply { mkdirs() }

    /**
     * 获取经过标识校验的工作目录。
     * @param id 任务标识。
     * @return 应用私有目录。
     */
    fun directory(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        return SafeFiles.resolve(root, id)
    }

    /**
     * 原子保存 JSON 结果。
     * 使用示例：store.writeJson(taskId, "result.json", result)。
     * @param id 工作区标识。
     * @param name 文件名称。
     * @param json 序列化内容。
     */
    fun writeJson(id: String, name: String, json: String) {
        val directory = directory(id).apply { mkdirs() }
        val target = SafeFiles.resolve(directory, name)
        val temporary = File(directory, "$name.part")
        temporary.writeText(json)
        if (!temporary.renameTo(target)) throw CoreFailure("WRITE_FAILED", name)
    }

    /**
     * 读取核心已落盘的终态事件。
     * @param id 任务标识。
     * @return 终态事件，尚未写入时为空。
     */
    fun result(id: String): JSONObject? = runCatching {
        val file = File(directory(id), "engine-result.json")
        require(file.length() in 1..(1024 * 1024))
        JSONObject(file.readText())
    }.getOrNull()

    /**
     * 将崩溃前的阶段记录转为明确的中断报告。
     * @param id 已结束的任务标识。
     * @param since 任务开始时间。
     * @return 已落盘的完成或中断事件。
     */
    @Synchronized
    fun reconcileInterrupted(id: String, since: Long): JSONObject? {
        val event = result(id) ?: return null
        val summary = event.optJSONObject("summary") ?: return event
        if (summary.optString("warning") != "RECOVERY_INTERRUPTED") return event
        val output = readableDirectory(id)
        // 公共结果已完成而进程来不及写私有终态时，保留已完成结果。
        val published = runCatching {
            val local = if (summary.optInt("layoutVersion") >= 4) File(directory(id), "published-result.json")
                else ProjectAssembler.reportFile(output, "result.json")
            val completed = if (local.isFile && local.length() in 1..1048576) JSONObject(local.readText()) else null
            if (completed != null && completed.optString("warning") != "RECOVERY_INTERRUPTED") return@runCatching completed
            val path = summary.optString("outputPath")
            if (path.endsWith(".zip")) {
                val archive = File(path).canonicalFile
                require(archive.parentFile == StorageAccess.outputRoot().canonicalFile)
                require(archive.name.matches(Regex("\\d{6}_\\d+\\.zip")))
                ZipFile(archive).use { zip ->
                    val entry = zip.getEntry("reports/result.json") ?: zip.getEntry("result.json")
                    if (entry == null || entry.size !in 1..1048576) null
                    else zip.getInputStream(entry).use { input ->
                        val bytes = java.io.ByteArrayOutputStream()
                        SafeFiles.copy(input, bytes, 1048576)
                        JSONObject(bytes.toString("UTF-8"))
                    }
                }
            } else {
                val file = ProjectAssembler.reportFile(output, "result.json")
                if (file.length() !in 1..1048576) null else JSONObject(file.readText())
            }
        }.getOrNull()
        if (published != null && published.optString("outputPath").isNotEmpty() && published.has("resources") && published.optString("warning") != "RECOVERY_INTERRUPTED") {
            event.put("summary", published)
            ProjectAssembler.reportFile(output, "result.json").writeText(published.toString(2))
        } else {
            val diagnostic = EngineExitDiagnostics.read(context, output, summary.optLong("recoveryStartedAt", since), summary.optInt("enginePid"))
            val recovered = ProjectAssembler.contentDirectory(output, "unpacked", "project", "recovered")
            val scripts = if (recovered.isDirectory) recovered.walkTopDown().onEnter { it.canonicalFile.toPath().startsWith(output.canonicalFile.toPath()) }
                .count { it.isFile && it.extension.equals("gd", true) } else 0
            summary.put("scripts", scripts).put("warning", "NATIVE_RECOVERY_INTERRUPTED").put("errorCode", "ENGINE_EXIT").put("diagnostic", diagnostic)
            ProjectAssembler.reportFile(output, "engine-exit.log").writeText(diagnostic)
            val report = ProjectAssembler.reportFile(output, "recovery-report.txt")
            report.appendText("\n脚本恢复中断，已解密资源保留。\n已生成可读脚本：$scripts\n")
            ProjectAssembler.reportFile(output, "result.json").writeText(summary.toString(2))
        }
        RecoveryReport.write(output, describeReason = { SnapshotReasonText.describe(context, it) })
        writeJson(id, "engine-result.json", event.toString())
        val archive = summary.optString("outputPath").takeIf { it.endsWith(".zip") }?.let(::File)
        if (archive != null) runCatching { PublicOutputs(StorageAccess.outputRoot(), { SnapshotReasonText.describe(context, it) }).publish(output, since, {}, {}, archive) }
        File(directory(id), "workspace.json").delete()
        return event
    }

    /**
     * 索引原始与可读产物，保留对应的源文件路径。
     * @param id 工作区标识。
     * @return 文件列表。
     */
    @Synchronized
    fun index(id: String): List<WorkspaceEntry> {
        val directory = readableDirectory(id)
        val raw = ProjectAssembler.reportFile(directory, "raw-index.json")
        val sources = mutableSetOf<String>()
        if (raw.isFile && raw.length() <= 128L * 1024 * 1024) {
            val array = JSONArray(raw.readText())
            for (i in 0 until array.length()) sources += SafeFiles.relative(array.getJSONObject(i).getString("path"))
        }
        val entries = mutableListOf<WorkspaceEntry>()
        for (base in listOf(
            ProjectAssembler.contentDirectory(directory, "unpacked", "project", "recovered"),
            ProjectAssembler.contentDirectory(directory, "original", "raw"),
        )) {
            if (!base.exists()) continue
            val folder = base.name
            base.walkTopDown().onEnter { it.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) }.filter { it.isFile }.forEach { file ->
                if (entries.size >= 200000) throw CoreFailure("ENTRY_LIMIT", "Workspace entry count exceeds 200000")
                val relative = SafeFiles.relative(file.relativeTo(base).invariantSeparatorsPath)
                val source = when {
                    relative in sources -> relative
                    relative.endsWith(".gd") && relative.dropLast(3) + ".gdc" in sources -> relative.dropLast(3) + ".gdc"
                    relative.endsWith(".gd") && relative.dropLast(3) + ".gde" in sources -> relative.dropLast(3) + ".gde"
                    relative.endsWith(".tscn") && relative.dropLast(5) + ".scn" in sources -> relative.dropLast(5) + ".scn"
                    relative.endsWith(".tres") && relative.dropLast(5) + ".res" in sources -> relative.dropLast(5) + ".res"
                    else -> ""
                }
                entries += WorkspaceEntry("$folder/$relative", file.length(), source)
            }
        }
        val report = File(directory, RecoveryReport.FILE_NAME).takeIf { it.isFile }
            ?: File(directory, "report.txt").takeIf { it.isFile }
            ?: ProjectAssembler.reportFile(directory, "recovery-report.txt").takeIf { it.isFile }
            ?: ProjectAssembler.reportFile(directory, "result.json").takeIf { it.isFile }
        if (report != null) entries += WorkspaceEntry(report.relativeTo(directory).invariantSeparatorsPath, report.length())
        File(directory, "diff.gt").takeIf { it.isFile }?.let { entries += WorkspaceEntry(it.name, it.length()) }
        val sorted = entries.sortedBy { it.path }
        val json = JSONArray()
        sorted.forEach { json.put(JSONObject().put("path", it.path).put("size", it.size).put("source", it.source)) }
        writeJson(id, "workspace.json", json.toString())
        return sorted
    }

    /**
     * 读取已生成的工作区索引。
     * @param id 工作区标识。
     * @return 校验后的文件列表。
     */
    @Synchronized
    fun entries(id: String): List<WorkspaceEntry> {
        val file = File(directory(id), "workspace.json")
        if (!file.isFile) return index(id)
        if (file.length() > 128L * 1024 * 1024) throw CoreFailure("ENTRY_LIMIT", "Workspace index exceeds 128 MiB")
        val array = JSONArray(file.readText())
        if (array.length() > 200000) throw CoreFailure("ENTRY_LIMIT", "Workspace entry count exceeds 200000")
        return List(array.length()) { i ->
            val entry = array.getJSONObject(i)
            WorkspaceEntry(SafeFiles.relative(entry.getString("path")), entry.getLong("size"), entry.optString("source"))
        }
    }

    /**
     * 解析索引内文件，拒绝不存在或越界的路径。
     * @param id 工作区标识。
     * @param path 索引相对路径。
     * @return 可读取的文件。
     */
    fun file(id: String, path: String): File = SafeFiles.resolve(if (path.startsWith("input/") || path == "inputs.json") directory(id) else readableDirectory(id), path).also {
        if (!it.isFile) throw CoreFailure("MISSING_ENTRY", path)
    }

    /**
     * 优先读取已发布的公共结果，兼容旧的私有工作区。
     * @param id 任务标识。
     * @return 有效结果根目录。
     */
    private fun readableDirectory(id: String): File {
        val local = directory(id)
        if (File(local, "unpacked").isDirectory || File(local, "original").isDirectory) return local
        val path = result(id)?.optJSONObject("summary")?.optString("outputPath").orEmpty()
        if (path.isNotEmpty()) {
            val output = File(path).canonicalFile
            val root = StorageAccess.outputRoot().canonicalFile
            if (output.parentFile == root && output.name.matches(Regex("\\d{6}_\\d+")) && output.isDirectory) return output
        }
        return local
    }

    /**
     * 删除指定工作区，调用方负责排除运行任务。
     * @param id 已确认清理的任务标识。
     */
    @Synchronized
    fun delete(id: String) {
        val directory = directory(id)
        check(directory.canonicalFile.parentFile == root.canonicalFile)
        if (directory.exists() && !directory.deleteRecursively()) throw CoreFailure("DELETE_FAILED", id)
    }
}
