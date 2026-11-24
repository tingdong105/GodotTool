package com.winter.godot.tool.feature.compare

import java.io.RandomAccessFile

/**
 * 可选择和关注的一条方法结果。
 * @author Tingdong
 * @date 2026-11-25
 */
data class ComparisonRow(val path: String, val change: MethodChange, val focused: Boolean) {
    val id: String get() = "$path::${change.key}::${change.kind}::${change.mode}"
}

/**
 * 按分类排序的方法位置索引，不保留方法文本。
 * @author Tingdong
 * @date 2026-11-25
 */
class ComparisonIndex internal constructor(internal val result: ComparisonResult, internal val references: LongArray,
    val counts: List<Int>, val matchedCounts: List<Int>) {
    val total: Int get() = references.size
}

/**
 * 一次筛选建立轻量索引，滚动时分批读取方法。
 * @author Tingdong
 * @date 2026-11-25
 */
object ComparisonResults {
    const val BATCH_SIZE = 100

    /**
     * 扫描结果并记录匹配方法的位置，保持增加、修改、删除顺序。
     * 使用示例：ComparisonResults.index(result, "player", null, false) { check() }。
     * @param result 已保存的差异索引。
     * @param query 路径、名称或签名关键词。
     * @param kind 变化类别，空值为全部。
     * @param plugins 是否包含插件。
     * @param mode 当前查看范围。
     * @param check 取消检查。
     * @return 可复用的方法位置索引及分类统计。
     */
    fun index(result: ComparisonResult, query: String, kind: ChangeKind?, plugins: Boolean, mode: ComparisonMode = ComparisonMode.METHODS, check: () -> Unit): ComparisonIndex {
        val counts = MutableList(ChangeKind.entries.size) { 0 }
        val groups = List(ChangeKind.entries.size) { mutableListOf<Long>() }
        if (mode == ComparisonMode.FOCUS || mode == ComparisonMode.CONSTANTS && !result.membersAvailable)
            return ComparisonIndex(result, LongArray(0), counts, counts)
        RandomAccessFile(result.file, "r").use { file ->
            result.entries.forEachIndexed { entryIndex, entry ->
                check()
                if (!plugins && entry.plugin) return@forEachIndexed
                SnapshotDiff.read(file, entry, result.dataVersion).changes.forEachIndexed methods@ { methodIndex, change ->
                    check()
                    if (!mode.includes(change)) return@methods
                    counts[change.kind.ordinal]++
                    if (kind != null && change.kind != kind) return@methods
                    if (query.isNotBlank() && listOf(entry.path, change.key, change.before, change.after, ComparisonText.line(entry.path, change))
                            .none { it.contains(query, ignoreCase = true) }) return@methods
                    groups[change.kind.ordinal] += (entryIndex.toLong() shl 32) or methodIndex.toLong()
                }
            }
        }
        val matched = groups.map { it.size }
        val references = LongArray(matched.sum())
        var position = 0
        for (group in groups) for (reference in group) { check(); references[position++] = reference }
        return ComparisonIndex(result, references, counts, matched)
    }

    /**
     * 只读取所需批次，复用同一脚本的已解码内容。
     * 使用示例：ComparisonResults.read(index, 100, check)。
     * @param index 当前筛选条件的位置索引。
     * @param start 从零开始的方法位置。
     * @param check 取消检查。
     * @return 下一批方法，无更多内容时返回空列表。
     */
    fun read(index: ComparisonIndex, start: Int, check: () -> Unit): List<ComparisonRow> {
        require(start >= 0)
        val rows = mutableListOf<ComparisonRow>()
        val end = minOf(index.total.toLong(), start.toLong() + BATCH_SIZE).toInt()
        RandomAccessFile(index.result.file, "r").use { file ->
            var loadedEntry = -1
            var changes = emptyList<MethodChange>()
            for (position in start until end) {
                check()
                val reference = index.references[position]
                val entryIndex = (reference ushr 32).toInt()
                val entry = index.result.entries[entryIndex]
                if (loadedEntry != entryIndex) { changes = SnapshotDiff.read(file, entry, index.result.dataVersion).changes; loadedEntry = entryIndex }
                rows += ComparisonRow(entry.path, changes[reference.toInt()], false)
            }
        }
        return rows
    }

    /**
     * 构建稳定的关注键。
     * 使用示例：ComparisonResults.starKey(path, change)。
     * @param path 脚本路径。
     * @param change 方法变化。
     * @return 当前记录内的方法键。
     */
    fun starKey(path: String, change: MethodChange): String = "$path::${change.key}"
}
