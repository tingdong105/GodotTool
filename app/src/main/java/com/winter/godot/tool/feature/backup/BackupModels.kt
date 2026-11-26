package com.winter.godot.tool.feature.backup

import com.winter.godot.tool.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 可独立备份与覆盖还原的数据范围。
 * @author Tingdong
 * @date 2026-11-26
 */
enum class BackupScope(val label: Int, val entry: String) {
    HISTORY(R.string.compare_history, "history"),
    FILTER_RULES(R.string.compare_filter_rules, "filter-rules.json"),
    FOCUS_RULES(R.string.compare_focus_rules, "focus-rules.json"),
    SETTINGS(R.string.backup_settings, "settings.json")
}

/**
 * 隔离备份维护期间的新写入，锁由主线程领取与释放。
 * @author Tingdong
 * @date 2026-11-26
 */
object BackupAccess {
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    var recoveryRequired = false
        private set

    /** @return 是否成功领取唯一维护会话。 */
    fun begin(): Boolean {
        if (mutableBusy.value) return false
        mutableBusy.value = true
        return true
    }

    /** 释放已完成的会话，待恢复事务继续阻止写入。 */
    fun end() { if (!recoveryRequired) mutableBusy.value = false }

    /** 回滚失败时保留维护锁，等待下次启动重放。 */
    fun requireRecovery() { recoveryRequired = true }
}

/**
 * 已读取的备份清单摘要。
 * @author Tingdong
 * @date 2026-11-26
 */
data class BackupArchive(val path: String, val manifest: String, val scopes: Set<BackupScope>, val createdAt: Long)

/**
 * 范围、确认、字节进度与操作结果的单向状态。
 * @author Tingdong
 * @date 2026-11-26
 */
data class BackupUiState(
    val id: String = "", val restoring: Boolean = false,
    val choosing: Boolean = false, val confirming: Boolean = false, val inspecting: Boolean = false,
    val available: Set<BackupScope> = BackupScope.entries.toSet(),
    val selected: Set<BackupScope> = BackupScope.entries.toSet(),
    val archive: BackupArchive? = null,
    val running: Boolean = false, val committing: Boolean = false,
    val fraction: Float = 0f, val finished: Boolean = false,
    val notice: Int? = null, val detail: String = "", val output: String = ""
)
