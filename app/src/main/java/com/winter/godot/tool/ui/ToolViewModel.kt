package com.winter.godot.tool.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.winter.godot.core.model.RecoverySelection
import com.winter.godot.tool.App
import com.winter.godot.tool.R
import com.winter.godot.tool.data.ImportDraft
import com.winter.godot.tool.data.TaskRecord
import com.winter.godot.tool.data.TaskStatus
import com.winter.godot.tool.data.ToolSettings
import com.winter.godot.tool.feature.backup.BackupAccess
import com.winter.godot.tool.feature.compare.SnapshotOptions
import com.winter.godot.tool.feature.files.BrowserFile
import com.winter.godot.tool.feature.files.BrowserSort
import com.winter.godot.tool.feature.files.FileBrowser
import com.winter.godot.tool.feature.files.StorageAccess
import com.winter.godot.tool.feature.files.sortBrowserFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 文件选择与临时交互状态。
 * @author Tingdong
 * @date 2026-11-21
 */
data class ToolUiState(
    val screen: Screen = Screen.HOME,
    val selection: RecoverySelection = RecoverySelection.scriptsOnly(),
    val browserPath: String = "",
    val browserFiles: List<BrowserFile> = emptyList(),
    val browserSort: BrowserSort = BrowserSort.NAME,
    val browserReversed: Boolean = false,
    val browserDirectoryOnly: Boolean = false,
    val browserRefreshing: Boolean = false,
    val chosenFiles: Set<String> = emptySet(),
    val inputFiles: Set<String> = emptySet(),
    val followsSettings: Boolean = true,
    val generateSnapshot: Boolean = false,
    val pickerPurpose: String = "recovery",
    val homeStep: Int = 1,
    val loading: Boolean = false,
    val selectedTasks: Set<String> = emptySet(),
    val deletingTasks: Boolean = false,
    @param:StringRes val notice: Int? = null,
    val noticeDetail: String = "",
)

/**
 * 工作台、导入与文件选择的界面状态模型。
 * @author Tingdong
 * @date 2026-11-21
 */
class ToolViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = (application as App).repository
    val settings = repository.settings
    val tasks = repository.tasks
    val active = repository.active
    val logs = repository.logs
    private val navigation = ToolNavigation(saved)
    val navEntries = navigation.entries
    val backStack = navigation.stack
    private val restoringTask = active.value != null || tasks.value.firstOrNull()?.acknowledged == false
    private var defaultBrowserSort = BrowserSort.entries.firstOrNull { it.name == saved.get<String>("browserDefaultSort") }
        ?: BrowserSort.entries.firstOrNull { it.name == saved.get<String>("browserSort") } ?: BrowserSort.NAME
    private var defaultBrowserReversed = saved.get<Boolean>("browserDefaultReversed") ?: saved.get<Boolean>("browserReversed") ?: false
    private val mutableUi = MutableStateFlow(ToolUiState(screen = backStack.value.last(), selection = saved.restoreRecoverySelection(settings.value.selection),
        browserPath = saved.get<String>("browserPath").orEmpty(), chosenFiles = if (restoringTask) emptySet() else saved.get<ArrayList<String>>("chosenFiles").orEmpty().takeLast(1).toSet(),
        browserSort = BrowserSort.entries.firstOrNull { it.name == saved.get<String>("browserSort") } ?: BrowserSort.NAME,
        browserReversed = saved.get<Boolean>("browserReversed") ?: false,
        browserDirectoryOnly = saved.get<Boolean>("browserDirectoryOnly") ?: false,
        inputFiles = if (restoringTask) emptySet() else saved.get<ArrayList<String>>("inputFiles").orEmpty().takeLast(1).toSet(),
        followsSettings = saved.get<Boolean>("followsSettings") ?: true,
        generateSnapshot = saved.get<Boolean>("generateSnapshot") ?: settings.value.snapshot.generate,
        pickerPurpose = saved.get<String>("pickerPurpose") ?: "recovery",
        homeStep = restoreHomeStep(saved.get<Int>("homeStep") ?: 1, saved.get<ArrayList<String>>("inputFiles").orEmpty().isNotEmpty(),
            active.value != null, tasks.value.firstOrNull())))
    val ui = mutableUi.asStateFlow()
    private var fileJob: Job? = null

    init {
        viewModelScope.launch {
            tasks.collect { records ->
                val selectable = records.filter { it.status != TaskStatus.RUNNING && it.id != active.value }.map { it.id }.toSet()
                mutableUi.update { it.copy(selectedTasks = it.selectedTasks.intersect(selectable)) }
            }
        }
        saved["homeStep"] = ui.value.homeStep
        if (restoringTask) {
            saved["inputFiles"] = arrayListOf<String>()
            saved["chosenFiles"] = arrayListOf<String>()
        }
        if (ui.value.screen == Screen.FILES) readBrowser(ui.value.browserPath.ifEmpty { pickerStartPath() }, restoring = true)
    }

    /**
     * 更新页面并保存轻量导航状态。
     * @param screen 目标页面。
     */
    fun navigate(screen: Screen) {
        if (navigation.navigate(screen)) showScreen(screen)
    }

    /**
     * 将栈顶页面同步到业务状态，保留文件选择和页面内状态。
     * @param screen 当前栈顶。
     */
    private fun showScreen(screen: Screen) {
        if (screen != Screen.FILES) fileJob?.cancel()
        if (screen != Screen.FILES && ui.value.browserDirectoryOnly) resetDirectorySort()
        mutableUi.update { it.copy(screen = screen, chosenFiles = if (screen == Screen.FILES) { if (it.pickerPurpose == "recovery") it.inputFiles else emptySet() } else it.chosenFiles, notice = null,
            selectedTasks = if (screen == Screen.TASKS) it.selectedTasks else emptySet(),
            browserPath = if (screen == Screen.FILES) "" else it.browserPath,
            browserFiles = if (screen == Screen.FILES) emptyList() else it.browserFiles,
            loading = false, browserRefreshing = false) }
        saved.saveRecoverySelection(ui.value.selection)
        if (screen == Screen.FILES) readBrowser(pickerStartPath(), restoring = true)
    }

    /**
     * 处理记录多选、文件选择与顶层页面的返回。
     */
    fun back() {
        if (ui.value.screen == Screen.TASKS && ui.value.selectedTasks.isNotEmpty()) {
            clearTaskSelection()
        } else if (ui.value.screen == Screen.FILES && ui.value.browserPath.isNotEmpty() && ui.value.browserPath != StorageAccess.root().absolutePath) {
            browseFiles(File(ui.value.browserPath).parent ?: StorageAccess.root().absolutePath)
        } else if (navigation.back()) showScreen(backStack.value.last())
    }

    /**
     * 异步读取内置文件浏览器目录。
     * @param path 共享存储内的目录，空值使用根目录。
     */
    fun browseFiles(path: String = "") { readBrowser(path) }

    /**
     * 下拉刷新当前目录，保留排序与选择并忽略重复请求。
     */
    fun refreshFiles() {
        if (ui.value.screen != Screen.FILES || ui.value.loading || ui.value.browserPath.isEmpty()) return
        readBrowser(ui.value.browserPath, refreshing = true)
    }

    /**
     * 更新排序依据与逆向选项，在后台重新整理目录。
     * @param order 排序依据。
     * @param reversed 是否逆向排列。
     * @param directoryOnly 是否只在当前目录生效。
     */
    fun sortFiles(order: BrowserSort, reversed: Boolean, directoryOnly: Boolean) {
        val sortingChanged = ui.value.browserSort != order || ui.value.browserReversed != reversed
        if (!sortingChanged && ui.value.browserDirectoryOnly == directoryOnly) return
        if (!directoryOnly) {
            defaultBrowserSort = order
            defaultBrowserReversed = reversed
        }
        saved["browserDefaultSort"] = defaultBrowserSort.name
        saved["browserDefaultReversed"] = defaultBrowserReversed
        saved["browserSort"] = order.name
        saved["browserReversed"] = reversed
        saved["browserDirectoryOnly"] = directoryOnly
        mutableUi.update { it.copy(browserSort = order, browserReversed = reversed, browserDirectoryOnly = directoryOnly) }
        if (sortingChanged && ui.value.browserPath.isNotEmpty()) readBrowser(ui.value.browserPath)
    }

    /**
     * 离开临时排序目录后恢复此前的通用排序。
     */
    private fun resetDirectorySort() {
        saved["browserSort"] = defaultBrowserSort.name
        saved["browserReversed"] = defaultBrowserReversed
        saved["browserDirectoryOnly"] = false
        mutableUi.update { it.copy(browserSort = defaultBrowserSort, browserReversed = defaultBrowserReversed, browserDirectoryOnly = false) }
    }

    /** @return 当前用途的默认目录，备份与其它选择会话相互独立。 */
    private fun pickerStartPath(): String = if (ui.value.pickerPurpose == "backup-restore")
        StorageAccess.backupRoot().takeIf { it.isDirectory }?.absolutePath ?: StorageAccess.root().absolutePath
    else repository.confirmedBrowserPath(ui.value.pickerPurpose != "recovery")

    /**
     * 异步校验、读取与排序目录，恢复失效位置时回退内部存储。
     * @param path 目标目录。
     * @param restoring 是否允许恢复失败后回退根目录。
     * @param refreshing 是否显示下拉刷新状态。
     */
    private fun readBrowser(path: String, restoring: Boolean = false, refreshing: Boolean = false) {
        val target = path.ifEmpty { StorageAccess.root().absolutePath }
        val browserState = ui.value
        val fallbackSort = defaultBrowserSort
        val fallbackReversed = defaultBrowserReversed
        mutableUi.update { it.copy(loading = true, browserRefreshing = refreshing) }
        fileJob?.cancel()
        fileJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val browser = FileBrowser(StorageAccess.root())
                    val folder = try {
                        browser.checked(target).also { if (restoring && !it.canRead()) error("Directory unavailable") }
                    } catch (error: Exception) {
                        if (restoring) StorageAccess.root() else throw error
                    }
                    val extensions = when (browserState.pickerPurpose) {
                        "recovery" -> setOf("apk", "pck", "zip")
                        "backup-restore" -> setOf("zip")
                        else -> setOf("gt", "zip")
                    }
                    val (actualPath, files) = try { folder.absolutePath to browser.list(folder.absolutePath, extensions) }
                    catch (error: Exception) {
                        if (restoring && folder != StorageAccess.root()) StorageAccess.root().absolutePath to browser.list(StorageAccess.root().absolutePath, extensions) else throw error
                    }
                    val leavingDirectory = browserState.browserDirectoryOnly && actualPath != browserState.browserPath
                    actualPath to sortBrowserFiles(files, if (leavingDirectory) fallbackSort else browserState.browserSort,
                        if (leavingDirectory) fallbackReversed else browserState.browserReversed)
                }
                if (ui.value.browserDirectoryOnly && result.first != ui.value.browserPath) resetDirectorySort()
                mutableUi.update { it.copy(browserPath = result.first, browserFiles = result.second, loading = false, browserRefreshing = false) }
                saved["browserPath"] = result.first
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { mutableUi.update { it.copy(loading = false, browserRefreshing = false) }; showError(R.string.error_read, error.message.orEmpty()) }
        }
    }

    /**
     * 选择唯一输入，重新选择时替换原文件。
     * @param path 已校验的输入路径。
     */
    fun chooseFile(path: String) {
        if (active.value != null && ui.value.pickerPurpose == "recovery") return
        mutableUi.update { it.copy(chosenFiles = setOf(path)) }
        if (ui.value.pickerPurpose == "recovery") saved["chosenFiles"] = ArrayList(ui.value.chosenFiles)
    }

    /**
     * 确认文件后返回首页配置步骤。
     */
    fun confirmFiles(onSnapshot: (Boolean, String) -> Unit = { _, _ -> }, onBackup: (String) -> Unit = {}) {
        val purpose = ui.value.pickerPurpose
        if (active.value != null && purpose == "recovery" || ui.value.loading || ui.value.chosenFiles.size != 1) return
        val chosen = ui.value.chosenFiles.single()
        mutableUi.update { it.copy(loading = true) }
        fileJob = viewModelScope.launch {
            try {
                val folder = withContext(Dispatchers.IO) {
                    val file = File(chosen)
                    val parent = FileBrowser(StorageAccess.root()).checked(requireNotNull(file.parent))
                    check(file.isFile && file.canRead()) { "Selected file unavailable" }
                    parent.absolutePath
                }
                if (purpose == "backup-restore") {
                    mutableUi.update { it.copy(loading = false, chosenFiles = emptySet()) }
                    navigate(Screen.SETTINGS)
                    onBackup(chosen)
                    return@launch
                }
                if (purpose != "recovery") {
                    repository.rememberBrowserPath(folder, comparison = true)
                    mutableUi.update { it.copy(loading = false, chosenFiles = emptySet()) }
                    navigate(Screen.COMPARE)
                    onSnapshot(purpose == "snapshot-old", chosen)
                    return@launch
                }
                if (active.value != null) { mutableUi.update { it.copy(loading = false) }; return@launch }
                repository.rememberBrowserPath(folder)
                tasks.value.firstOrNull()?.let { repository.acknowledge(it.id) }
                saved["inputFiles"] = arrayListOf(chosen)
                saved["homeStep"] = 2
                mutableUi.update { it.copy(inputFiles = setOf(chosen), homeStep = 2, loading = false) }
                navigate(Screen.HOME)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { mutableUi.update { it.copy(loading = false) }; showError(R.string.error_read, error.message.orEmpty()) }
        }
    }

    /**
     * 切换首页中可编辑的步骤。
     * @param step 已满足前置条件的步骤。
     */
    fun selectHomeStep(step: Int) {
        if (active.value != null || step !in 1..3 || step > 1 && ui.value.inputFiles.size != 1) return
        val selection = if (ui.value.followsSettings) settings.value.selection else ui.value.selection
        if (step == 3 && !selection.hasContent) return
        saved["homeStep"] = step
        mutableUi.update { it.copy(homeStep = step) }
    }

    /**
     * 选择默认配置，开始时读取最新设置。
     * @param follow 是否跟随应用设置。
     */
    fun followSettings(follow: Boolean) {
        if (active.value != null || follow == ui.value.followsSettings) return
        saved["followsSettings"] = follow
        if (!follow) { editSelection(settings.value.selection); editSnapshot(settings.value.snapshot.generate) }
        mutableUi.update { it.copy(followsSettings = follow) }
    }

    /**
     * 使用首页确认的文件与配置启动后台任务。
     */
    fun recoverChosen() {
        if (BackupAccess.busy.value) return
        val selection = if (ui.value.followsSettings) settings.value.selection else ui.value.selection
        if (active.value != null || ui.value.inputFiles.size != 1 || !selection.hasContent) return
        val file = File(ui.value.inputFiles.single())
        val source = JSONObject().put("file", file.absolutePath).put("name", file.name)
        val snapshot = if (ui.value.followsSettings) settings.value.snapshot else SnapshotOptions(ui.value.generateSnapshot)
        repository.start(ImportDraft(file.name, listOf(source), selection, snapshot), "")
        mutableUi.update { it.copy(chosenFiles = emptySet(), inputFiles = emptySet(), homeStep = 3) }
        saved["chosenFiles"] = arrayListOf<String>()
        saved["inputFiles"] = arrayListOf<String>()
        saved["homeStep"] = 3
        navigate(Screen.HOME)
    }

    /**
     * 完成后开始新一轮文件选择。
     */
    fun newRecovery() {
        if (active.value != null) return
        tasks.value.firstOrNull()?.let { repository.acknowledge(it.id) }
        saved["homeStep"] = 1
        saved["followsSettings"] = true
        mutableUi.update { it.copy(homeStep = 1, followsSettings = true) }
    }

    /**
     * 更新本次编辑的恢复内容。
     * @param value 待确认的选项。
     */
    fun editSelection(value: RecoverySelection) {
        saved.saveRecoverySelection(value)
        mutableUi.update { it.copy(selection = value) }
    }

    /**
     * 保存本次快照开关。
     * @param value 是否生成快照。
     */
    fun editSnapshot(value: Boolean) {
        saved["generateSnapshot"] = value
        mutableUi.update { it.copy(generateSnapshot = value) }
    }

    /**
     * 指定内置选择器的输入用途。
     * @param purpose recovery、snapshot-old、snapshot-new 或 backup-restore。
     */
    fun preparePicker(purpose: String) {
        require(purpose in setOf("recovery", "snapshot-old", "snapshot-new", "backup-restore"))
        saved["pickerPurpose"] = purpose
        mutableUi.update { it.copy(pickerPurpose = purpose) }
    }

    /**
     * 保存应用设置。
     * @param value 新设置。
     */
    fun updateSettings(value: ToolSettings) = repository.updateSettings(value)

    /** 还原后刷新应用设置与跟随默认值的首页草稿。 */
    fun reloadSettings() {
        repository.reloadSettings()
        if (ui.value.followsSettings) mutableUi.update { it.copy(selection = settings.value.selection,
            generateSnapshot = settings.value.snapshot.generate) }
    }

    /**
     * 使用缓存的输入重新处理，无需重新挑选文件。
     * @param task 原任务摘要。
     */
    fun retry(task: TaskRecord) {
        if (active.value != null) return
        viewModelScope.launch {
            runCatching {
                val draft = repository.retryDraft(task.id)
                if (draft.sources.size != 1) { showError(R.string.single_file_only); return@launch }
                val selection = draft.selection ?: settings.value.selection
                if (!selection.hasContent) { showError(R.string.choose_recovery_content); return@launch }
                repository.start(draft.copy(selection = selection), "")
                saved["homeStep"] = 3
                saved["followsSettings"] = false
                saved.saveRecoverySelection(selection)
                mutableUi.update { it.copy(homeStep = 3, inputFiles = emptySet(), chosenFiles = emptySet(), selection = selection, followsSettings = false) }
                saved["inputFiles"] = arrayListOf<String>()
                saved["chosenFiles"] = arrayListOf<String>()
                navigate(Screen.HOME)
            }.onFailure { showError(R.string.error_retry, it.message.orEmpty()) }
        }
    }

    /**
     * 切换一个已结束任务的选择。
     * @param id 任务标识。
     */
    fun selectTask(id: String) {
        if (ui.value.deletingTasks || tasks.value.none { it.id == id && it.status != TaskStatus.RUNNING && it.id != active.value }) return
        mutableUi.update { it.copy(selectedTasks = if (id in it.selectedTasks) it.selectedTasks - id else it.selectedTasks + id) }
    }

    /**
     * 全选已结束任务，再次点击取消全选。
     */
    fun selectAllTasks() {
        if (ui.value.deletingTasks) return
        val ids = tasks.value.filter { it.status != TaskStatus.RUNNING && it.id != active.value }.map { it.id }.toSet()
        mutableUi.update { it.copy(selectedTasks = if (it.selectedTasks == ids) emptySet() else ids) }
    }

    /**
     * 退出记录多选模式。
     */
    fun clearTaskSelection() {
        if (!ui.value.deletingTasks) mutableUi.update { it.copy(selectedTasks = emptySet()) }
    }

    /**
     * 清理用户确认的已结束任务，失败时保留剩余选择。
     * @param ids 已确认的任务标识集合。
     */
    fun deleteTasks(ids: Set<String>) {
        if (ui.value.deletingTasks || ids.isEmpty()) return
        val confirmed = ids.toSet()
        mutableUi.update { it.copy(deletingTasks = true) }
        viewModelScope.launch {
            try {
                repository.delete(confirmed)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(R.string.error_cleanup, error.message.orEmpty())
            } finally {
                val remaining = tasks.value.map { it.id }.toSet()
                mutableUi.update { it.copy(deletingTasks = false, selectedTasks = it.selectedTasks.intersect(remaining)) }
            }
        }
    }

    /**
     * 清理已结束的全部工作区。
     */
    fun clearFinished() {
        if (active.value != null || ui.value.deletingTasks || BackupAccess.busy.value) return
        mutableUi.update { it.copy(deletingTasks = true) }
        viewModelScope.launch {
            try {
                repository.clearFinished()
                showError(R.string.cleanup_complete)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(R.string.error_cleanup, error.message.orEmpty())
            } finally {
                val remaining = tasks.value.map { it.id }.toSet()
                mutableUi.update { it.copy(deletingTasks = false, selectedTasks = it.selectedTasks.intersect(remaining)) }
            }
        }
    }

    /**
     * 关闭界面提示。
     */
    fun dismissNotice() { mutableUi.update { it.copy(notice = null, noticeDetail = "") } }

    /**
     * 设置资源化的可见提示。
     * @param message 字符串资源。
     * @param detail 可选诊断内容。
     */
    private fun showError(@StringRes message: Int, detail: String = "") { mutableUi.update { it.copy(notice = message, noticeDetail = detail.take(3000)) } }
}

/**
 * 从系统保存状态恢复本次恢复范围。
 * 使用示例：saved.restoreRecoverySelection(defaults)。
 * @param fallback 尚无有效保存值时的默认范围。
 * @return 本次选择的全部六个选项。
 */
internal fun SavedStateHandle.restoreRecoverySelection(fallback: RecoverySelection): RecoverySelection {
    val flags = get<Any>("recoverySelection") as? BooleanArray
    if (flags == null || flags.size != 6) return fallback
    return RecoverySelection(flags[0], flags[1], flags[2], flags[3], flags[4], flags[5])
}

/**
 * 使用系统支持的布尔数组保存本次恢复范围。
 * 使用示例：saved.saveRecoverySelection(selection)。
 * @param value 本次选择的全部选项。
 */
internal fun SavedStateHandle.saveRecoverySelection(value: RecoverySelection) {
    this["recoverySelection"] = booleanArrayOf(value.scripts, value.scenes, value.assets, value.plugins, value.config, value.keepRaw)
}
