package com.winter.godot.tool.feature.compare

/**
 * 可复用快照的格式及边界。
 * @author Tingdong
 * @date 2026-11-25
 */
object SnapshotFormat {
    const val FILE_NAME = "diff.gt"
    const val VERSION = 2
    const val RULES = "gdscript-tokens-v1"
    const val MAX_BYTES = 128L * 1024 * 1024
    const val MAX_ENTRIES = 200000
    const val MAX_SOURCE = 16 * 1024 * 1024
    const val MAX_TEXT = 16384
}

/**
 * 与导出范围独立的快照选项。
 * @author Tingdong
 * @date 2026-11-25
 */
data class SnapshotOptions(val generate: Boolean = false)

/**
 * 快照来源与覆盖信息。
 * @author Tingdong
 * @date 2026-11-25
 */
data class SnapshotHeader(
    val name: String = "",
    val packageName: String = "",
    val version: String = "",
    val engine: String = "",
    val tool: String = "GodotTool",
    val core: String = "",
    val catalogComplete: Boolean = false,
    val complete: Boolean = false,
    val membersIndexed: Boolean = false,
)

/**
 * 方法声明与内容指纹。
 * @author Tingdong
 * @date 2026-11-25
 */
data class MethodSnapshot(val key: String, val signature: String, val declaration: String, val body: String)

/**
 * 类级常量、变量的声明与内容指纹。
 * @author Tingdong
 * @date 2026-11-25
 */
data class MemberSnapshot(val key: String, val signature: String, val fingerprint: String)

/**
 * 独立的结果查看范围，关心模式预留。
 * @author Tingdong
 * @date 2026-11-25
 */
enum class ComparisonMode(val kinds: List<ChangeKind>) {
    METHODS(listOf(ChangeKind.ADDED, ChangeKind.MODIFIED, ChangeKind.REMOVED)),
    CONSTANTS(ChangeKind.entries),
    FOCUS(emptyList());

    /**
     * 统一判定列表与报告中允许展示的条目。
     * 使用示例：ComparisonMode.METHODS.includes(change)。
     * @param change 持久化差异，包含旧格式的汇总条目。
     * @return 是否属于当前模式允许展示的变化。
     */
    fun includes(change: MethodChange): Boolean = change.mode == this && change.kind in kinds &&
        (this != METHODS || change.key.isNotEmpty())
}

/**
 * 独立脚本或内嵌脚本容器记录。
 * @author Tingdong
 * @date 2026-11-25
 */
data class ScriptSnapshot(
    val path: String,
    val container: Boolean = false,
    val status: String = "ok",
    val reason: String = "",
    val outside: String = "",
    val methods: List<MethodSnapshot> = emptyList(),
    val members: List<MemberSnapshot> = emptyList(),
) {
    val plugin: Boolean get() = path.startsWith("addons/")
}

/**
 * 方法或方法外内容的变化类别。
 * @author Tingdong
 * @date 2026-11-25
 */
enum class ChangeKind { ADDED, MODIFIED, REMOVED, OUTSIDE, UNKNOWN }

/**
 * 可直接展示的变化条目。
 * @property before 旧版声明；UNKNOWN 条目保存旧版分析状态或原因。
 * @property after 新版声明；UNKNOWN 条目保存新版分析状态或原因。
 * @author Tingdong
 * @date 2026-11-25
 */
data class MethodChange(val kind: ChangeKind, val key: String = "", val before: String = "", val after: String = "",
    val declarationChanged: Boolean = false, val bodyChanged: Boolean = false, val mode: ComparisonMode = ComparisonMode.METHODS)

/**
 * 单个脚本的变化结果。
 * @author Tingdong
 * @date 2026-11-25
 */
data class ScriptChange(val path: String, val changes: List<MethodChange>)

/**
 * 磁盘结果的轻量索引。
 * @author Tingdong
 * @date 2026-11-25
 */
data class ComparisonEntry(val path: String, val offset: Long, val counts: List<Int>, val memberCounts: List<Int> = List(ChangeKind.entries.size) { 0 }) {
    val plugin: Boolean get() = path.startsWith("addons/")
}

/**
 * 对比完成后的来源和结果索引。
 * @author Tingdong
 * @date 2026-11-25
 */
data class ComparisonResult(val before: SnapshotHeader, val after: SnapshotHeader, val entries: List<ComparisonEntry>, val file: java.io.File,
    val dataVersion: Int = 2) {
    val membersAvailable: Boolean get() = dataVersion >= 2 && before.membersIndexed && after.membersIndexed
}
