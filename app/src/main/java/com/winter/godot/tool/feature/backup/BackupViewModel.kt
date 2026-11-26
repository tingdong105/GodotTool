package com.winter.godot.tool.feature.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.winter.godot.tool.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 管理备份选择、确认及跨窗口重建保留的操作会话。
 * @author Tingdong
 * @date 2026-11-26
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BackupRepository(application)
    private val mutableUi = MutableStateFlow(BackupUiState())
    val ui = mutableUi.asStateFlow()
    private var job: Job? = null
    private var cleared = false

    /** 打开默认全部选中的备份范围选择。 */
    fun chooseBackup() {
        if (BackupAccess.busy.value || job?.isActive == true) return
        mutableUi.value = BackupUiState(id = UUID.randomUUID().toString(), choosing = true)
    }

    /**
     * 读取用户选择的备份摘要后打开范围选择。
     * @param path 内置浏览器确认的 ZIP 路径。
     */
    fun inspect(path: String) {
        if (BackupAccess.busy.value || job?.isActive == true) return
        mutableUi.value = BackupUiState(id = UUID.randomUUID().toString(), restoring = true, inspecting = true)
        job = viewModelScope.launch {
            try {
                val archive = withContext(Dispatchers.IO) { repository.inspect(path) }
                mutableUi.update { it.copy(inspecting = false, choosing = true, archive = archive,
                    available = archive.scopes, selected = archive.scopes) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableUi.update { it.copy(inspecting = false, notice = errorText(error)) } }
        }
    }

    /**
     * 仅允许选择备份内已有的范围。
     * @param selected 新的勾选集合。
     */
    fun select(selected: Set<BackupScope>) {
        if (ui.value.choosing) mutableUi.update { it.copy(selected = selected.intersect(it.available)) }
    }

    /** 从范围选择进入独立的二次确认。 */
    fun review() {
        if (ui.value.choosing && ui.value.selected.isNotEmpty()) mutableUi.update { it.copy(choosing = false, confirming = true) }
    }

    /** 关闭范围选择或取消确认，保留当前选择供调整。 */
    fun dismissChoice() { mutableUi.update { it.copy(choosing = false, confirming = false) } }

    /** 返回范围选择继续调整。 */
    fun revise() { mutableUi.update { it.copy(choosing = true, confirming = false) } }

    /**
     * 领取维护锁并执行已确认的数据操作。
     * @param ready 主线程检查其它数据任务是否空闲。
     * @param onRestored 成功提交后同步内存与阅读状态。
     */
    fun confirm(ready: () -> Boolean, onRestored: suspend (Set<BackupScope>) -> Unit) {
        val state = ui.value
        if (!state.confirming || state.selected.isEmpty() || job?.isActive == true) return
        if (!ready() || !BackupAccess.begin()) {
            mutableUi.update { it.copy(confirming = false, notice = R.string.backup_busy) }
            return
        }
        mutableUi.update { it.copy(confirming = false, running = true, fraction = 0f, finished = false) }
        job = viewModelScope.launch {
            val operation = coroutineContext
            var success = false
            var output = ""
            try {
                withContext(Dispatchers.IO) {
                    val check = { operation.ensureActive() }
                    val progress: (Float) -> Unit = { fraction ->
                        mutableUi.update { if (it.id == state.id && fraction > it.fraction &&
                            (fraction == 1f || fraction - it.fraction >= 0.001f)) it.copy(fraction = fraction) else it }
                    }
                    val committing: suspend () -> Unit = {
                        // 取消与不可取消提交在主线程交接，避免取消成功后仍覆盖数据。
                        withContext(Dispatchers.Main.immediate) {
                            operation.ensureActive()
                            mutableUi.update { it.copy(committing = true) }
                        }
                    }
                    if (state.restoring) repository.restore(requireNotNull(state.archive), state.selected, check, progress, committing)
                    else output = repository.export(state.selected, check, progress, committing)
                    success = true
                }
            } catch (error: CancellationException) {
                if (!success) mutableUi.update { it.copy(running = false, notice = R.string.backup_cancelled) }
            } catch (error: Exception) {
                mutableUi.update { it.copy(running = false, notice = errorText(error)) }
            } finally {
                withContext(NonCancellable) {
                    if (success) {
                        var refreshError = ""
                        if (state.restoring) try { onRestored(state.selected) }
                        catch (_: Exception) { refreshError = getApplication<Application>().getString(R.string.backup_refresh_error) }
                        mutableUi.update { it.copy(fraction = 1f, finished = true, output = output, detail = refreshError) }
                    }
                    if (!success || cleared) BackupAccess.end()
                }
            }
        }
    }

    /** 仅在提交前取消，清理完暂存数据后释放维护锁。 */
    fun cancel() {
        if (!ui.value.committing && !ui.value.finished) {
            job?.cancel()
            if (ui.value.inspecting) mutableUi.update { it.copy(inspecting = false) }
        }
    }

    /**
     * 完整进度与退出动画完成后显示成功结果。
     * @param id 完成的会话身份。
     */
    fun finish(id: String) {
        val state = ui.value
        if (state.id != id || !state.finished) return
        BackupAccess.end()
        mutableUi.update { it.copy(running = false, notice = if (it.restoring) R.string.backup_restore_success else R.string.backup_export_success,
            detail = it.output.ifEmpty { it.detail }) }
    }

    /** 关闭结果提示。 */
    fun dismissNotice() { mutableUi.update { it.copy(notice = null, detail = "") } }

    /**
     * 将输入与磁盘错误转为可读提示。
     * @param error 原始异常。
     * @return 对应字符串资源。
     */
    private fun errorText(error: Exception): Int = when (error.message) {
        "BACKUP_VERSION" -> R.string.backup_version_error
        "BACKUP_SPACE" -> R.string.backup_space_error
        "BACKUP_CHANGED" -> R.string.backup_changed_error
        "BACKUP_CHECKSUM" -> R.string.backup_checksum_error
        "BACKUP_LIMIT" -> R.string.backup_limit_error
        "BACKUP_RECOVERY_REQUIRED" -> R.string.backup_recovery_error
        else -> if (error is java.io.IOException || error is SecurityException) R.string.backup_io_error else R.string.backup_invalid_error
    }

    /** 清理窗口会话，提交中的事务仍由 IO 工作线程完成或回滚。 */
    override fun onCleared() {
        cleared = true
        if (ui.value.finished) BackupAccess.end()
        super.onCleared()
    }
}
