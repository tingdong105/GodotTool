package com.winter.godot.tool.task

import org.json.JSONObject

/**
 * 按实际工作量累加全流程进度，保存完成前不达到百分之百。
 * 使用示例：overallProgress(previous, event)。
 * @param previous 本任务已确认的整体进度。
 * @param event 含真实字节或文件数量的进度事件。
 * @return 单调递增的整体进度估计。
 */
internal fun overallProgress(previous: Float, event: JSONObject): Float {
    val stage = event.optString("stage")
    val (start, end) = when (stage) {
        "import" -> 0f to 0.12f
        "probe" -> 0.12f to 0.14f
        "decrypt" -> 0.14f to 0.16f
        "extract" -> 0.16f to 0.48f
        "checkpoint" -> 0.48f to 0.50f
        "recover" -> 0.50f to 0.78f
        "snapshot" -> 0.78f to 0.84f
        "assemble" -> 0.84f to 0.92f
        "publish", "export" -> 0.92f to 0.99f
        else -> return previous
    }
    val bytes = event.optLong("bytes").coerceAtLeast(0)
    val totalBytes = event.optLong("totalBytes")
    val files = event.optInt("files").coerceAtLeast(0)
    val totalFiles = event.optInt("totalFiles")
    val fraction = when {
        totalBytes > 0 -> (bytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)
        totalFiles > 0 -> (files.toDouble() / totalFiles).coerceIn(0.0, 1.0)
        else -> 0.0
    }
    // 导入的字节计数属于单个输入，分包按文件顺序合并。
    val completed = if (stage == "import" && totalFiles > 0) (files + fraction) / totalFiles else fraction
    return maxOf(previous, start + (end - start) * completed.toFloat()).coerceIn(0f, 0.99f)
}
