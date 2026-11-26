package com.winter.godot.tool.data

import com.winter.godot.core.io.SafeFiles
import com.winter.godot.core.model.CoreFailure
import com.winter.godot.tool.feature.compare.SnapshotFormat
import com.winter.godot.tool.feature.compare.SnapshotIO
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 从内部记录生成唯一的导出报告。
 * @author Tingdong
 * @date 2026-11-24
 */
object RecoveryReport {
    const val FILE_NAME = "report.md"

    /**
     * 汇总解包结果，原子更新根目录报告。
     * 使用示例：RecoveryReport.write(workspace, check)。
     * @param directory 私有工作区。
     * @param check 取消检查。
     * @param describeReason 失败原因的本地化说明。
     * @return 可导出的 Markdown 报告。
     */
    fun write(directory: File, check: () -> Unit = {}, describeReason: (String) -> String = { it }): File {
        check()
        val result = read(ProjectAssembler.reportFile(directory, "result.json"))?.let(::JSONObject)
            ?: read(File(directory, "engine-result.json"))?.let(::JSONObject)?.optJSONObject("summary") ?: JSONObject()
        val inputs = read(File(directory, "inputs.json"))?.let(::JSONArray)
        val details = publicText(read(ProjectAssembler.reportFile(directory, "recovery-report.txt")).orEmpty())
        val diagnostic = publicText(result.optString("diagnostic")).take(4000)
        val warning = result.optString("warning")
        val snapshotError = result.optString("snapshotError").ifEmpty { if (result.optString("snapshotStatus") == "failed") "SNAPSHOT_FAILED" else "" }
        val status = when {
            warning in setOf("RECOVERY_INTERRUPTED", "NATIVE_RECOVERY_INTERRUPTED") -> "恢复未完成，已保留当前产物"
            warning == "RECOVERY_CANCELED" -> "已取消，已保留当前产物"
            warning.isNotEmpty() || snapshotError.isNotEmpty() || result.optInt("snapshotFailures") > 0 || result.optString("snapshotStatus") == "partial" ||
                result.optInt("failedScripts") > 0 || result.optInt("failedConversions") > 0 -> "部分完成"
            result.has("resources") -> "已完成"
            else -> "未取得完成记录"
        }
        val target = SafeFiles.resolve(directory, FILE_NAME)
        val temporary = SafeFiles.resolve(directory, ".$FILE_NAME.part")
        try {
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                with(writer) {
                    appendLine("# GodotTool 解包报告")
                    appendLine()
                    appendLine("> **处理结果：$status**")
                    appendLine()
                    appendLine("## 基本信息")
                    appendLine()
                    appendLine("| 项目 | 内容 |")
                    appendLine("| :---: | :---: |")
                    if (inputs != null) {
                        val names = (0 until minOf(inputs.length(), 128)).mapNotNull { inputs.optJSONObject(it)?.optString("name") }
                        appendLine("| 输入文件 | ${escapeCell(names.joinToString("\n"))} |")
                    }
                    result.optString("engine").takeIf { it.isNotEmpty() }?.let { appendLine("| Godot 版本 | ${escapeCell(it)} |") }
                    result.optString("adapter").takeIf { it.isNotEmpty() }?.let { adapter ->
                        val mode = when (adapter) { "compatibility", "CNM" -> "Super"; else -> adapter }
                        appendLine("| 适配模式 | ${escapeCell(mode)} |")
                    }
                    appendLine()
                    appendLine("## 恢复统计")
                    appendLine()
                    appendLine("| 项目 | 数量 |")
                    appendLine("| :---: | :---: |")
                    for ((key, label) in listOf("rawResources" to "原始资源", "resources" to if (result.has("projectFiles")) "输出文件" else "资源条目", "scripts" to "可读脚本",
                        "failedScripts" to "脚本恢复失败", "converted" to "资源转换成功", "directImages" to "原样提取图片", "failedConversions" to "资源转换失败")) {
                        if (result.has(key)) appendLine("| $label | ${result.optLong(key)} |")
                    }
                    Regex("(?m)^Lossy:\\s*(\\d+)").find(details)?.groupValues?.get(1)?.let { appendLine("| 有损恢复 | $it |") }
                    appendLine()
                    appendLine("## 输出目录")
                    appendLine()
                    if (ProjectAssembler.contentDirectory(directory, "original", "raw").isDirectory)
                        appendLine("- `original`：原始资源")
                    if (ProjectAssembler.contentDirectory(directory, "unpacked", "project", "recovered").isDirectory)
                        appendLine("- `unpacked`：解包结果")
                    if (result.optInt("snapshotFailures") > 0 || result.optString("snapshotStatus") == "partial")
                        appendSnapshotFailures(directory, describeReason, check)
                    val errorCode = result.optString("errorCode")
                    if (warning.isNotEmpty() || errorCode.isNotEmpty() || diagnostic.isNotEmpty() || snapshotError.isNotEmpty()) {
                        appendLine()
                        appendLine("## 问题摘要")
                        appendLine()
                        if (warning.isNotEmpty()) appendLine("- 结果标记：${escapeCell(warning)}")
                        if (errorCode.isNotEmpty()) appendLine("- 错误代码：${escapeCell(errorCode)}")
                        if (snapshotError.isNotEmpty()) appendLine("- 快照生成失败：${escapeCell(describeReason(snapshotError))}")
                        if (diagnostic.isNotEmpty()) { appendLine(); appendCodeBlock(diagnostic) }
                    }
                    if (details.isNotBlank()) {
                        appendLine()
                        appendLine("## 恢复明细")
                        appendLine()
                        appendCodeBlock(details.trim())
                    }
                }
            }
            check()
            if (!temporary.renameTo(target)) throw CoreFailure("WRITE_FAILED", "Cannot commit $FILE_NAME")
        } finally { temporary.delete() }
        return target
    }

    /**
     * 从快照流式读取失败路径与原因，兼容没有额外诊断摘要的旧任务。
     * @param directory 私有工作区。
     * @param describeReason 原因说明转换器。
     * @param check 取消检查。
     */
    private fun Appendable.appendSnapshotFailures(directory: File, describeReason: (String) -> String, check: () -> Unit) {
        appendLine(); appendLine("## 分析失败明细"); appendLine()
        val snapshot = File(directory, SnapshotFormat.FILE_NAME)
        if (!snapshot.isFile) {
            appendLine("快照文件未保留，无法读取具体失败路径和原因，请重新生成快照。")
            return
        }
        var count = 0
        try {
            SnapshotIO.open(snapshot).use { cursor ->
                while (true) {
                    check()
                    val script = cursor.next() ?: break
                    if (script.status != "failed") continue
                    if (count++ == 0) {
                        appendLine("| 文件路径 | 失败原因 |")
                        appendLine("| :--- | :--- |")
                    }
                    appendLine("| ${escapeCell(script.path)} | ${escapeCell(describeReason(script.reason.ifBlank { "ANALYSIS_FAILED" }))} |")
                }
            }
            if (count == 0) appendLine("当前快照中未找到失败明细，请重新生成快照核对。")
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) {
            val reason = error.message.orEmpty().takeIf { it.matches(Regex("[A-Z_]+")) } ?: "SNAPSHOT_READ_FAILED"
            appendLine(); appendLine("无法完整读取失败明细：${escapeCell(describeReason(reason))}")
        }
    }

    /**
     * 截断内部日志段，兼容旧版中断记录。
     * @param value 内部诊断或恢复明细。
     * @return 可公开的摘要。
     */
    private fun publicText(value: String): String = value.lineSequence()
        .takeWhile { !it.trim().endsWith(".log:") && it.trim() != "Previous report:" }.joinToString("\n").trim()

    /**
     * 转义表格单元格中的格式字符。
     * @param value 原始文本。
     * @return 可直接放入表格或列表的文本。
     */
    private fun escapeCell(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\\", "\\\\").replace("|", "\\|").replace("`", "\\`").replace("*", "\\*").replace("_", "\\_")
        .replace("[", "\\[").replace("]", "\\]").replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>")

    /**
     * 以代码块保留原始诊断排版。
     * @param value 诊断或恢复明细。
     */
    private fun Appendable.appendCodeBlock(value: String) {
        val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0) + 1))
        appendLine("${fence}text")
        appendLine(value)
        appendLine(fence)
    }

    /**
     * 有界读取内部报告或轻量记录。
     * @param file 内部文件。
     * @return 文本内容，不存在时为空。
     */
    private fun read(file: File): String? = if (!file.isFile) null else file.inputStream().use { input ->
        val limit = 1024 * 1024
        if (file.extension == "json" && file.length() > limit) throw CoreFailure("SIZE_LIMIT", "Internal record exceeds report limit")
        val bytes = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val size = input.read(bytes, count, limit - count)
            if (size < 0) break
            if (size == 0) continue
            count += size
        }
        val text = String(bytes, 0, count, Charsets.UTF_8)
        if (file.length() > count) "$text\n（内容过长，完整记录保留在应用内部）" else text
    }
}
