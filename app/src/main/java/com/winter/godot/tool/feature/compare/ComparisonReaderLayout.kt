package com.winter.godot.tool.feature.compare

import androidx.compose.ui.unit.Constraints

/**
 * 限制历史结果的展示长度与横向布局边界。
 * @author Tingdong
 * @date 2026-11-25
 */
object ComparisonReaderLayout {
    private const val PREVIEW_LENGTH = 2048

    /**
     * 为旧记录和长路径生成展示摘要，不修改磁盘数据和比较指纹。
     * 使用示例：ComparisonReaderLayout.preview(line)。
     * @param line 完整格式化结果。
     * @return 不截断 Unicode 代理对的展示行。
     */
    fun preview(line: String): String {
        if (line.length <= PREVIEW_LENGTH) return line
        var end = PREVIEW_LENGTH - 1
        if (line[end - 1].isHighSurrogate()) end--
        return line.substring(0, end) + "…"
    }

    /**
     * 在保留视口高度的前提下限制横向布局宽度。
     * 使用示例：ComparisonReaderLayout.width(1080, 1800, 500000, 96, false)。
     * @param viewportWidth 视口宽度，单位为像素。
     * @param viewportHeight 视口高度，单位为像素。
     * @param lineWidth 最长展示行宽度，单位为像素。
     * @param padding 两侧内边距的总像素数。
     * @param wrap 是否自动换行。
     * @return Compose 约束可以表示的内容宽度。
     */
    fun width(viewportWidth: Int, viewportHeight: Int, lineWidth: Int, padding: Int, wrap: Boolean): Int {
        if (wrap) return viewportWidth
        val requested = (lineWidth.toLong().coerceAtLeast(0) + padding.coerceAtLeast(0)).coerceAtLeast(viewportWidth.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong() - 1).toInt()
        return Constraints.fitPrioritizingHeight(minWidth = viewportWidth, maxWidth = requested, minHeight = 0, maxHeight = viewportHeight).maxWidth
    }
}
