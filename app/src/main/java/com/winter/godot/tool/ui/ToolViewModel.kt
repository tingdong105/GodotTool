package com.winter.godot.tool.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.winter.godot.tool.data.WorkspaceEntry
import com.winter.godot.tool.feature.files.BrowserFile
import com.winter.godot.tool.feature.files.FileBrowser
import com.winter.godot.tool.feature.files.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * 文件预览与临时交互状态。
 * @author Tingdong
 * @date 2026-11-21
 */
data class ToolUiState(
    val screen: Screen = Screen.HOME,
    val selection: RecoverySelection = RecoverySelection(),
    val browserPath: String = "",
    val browserFiles: List<BrowserFile> = emptyList(),
    val chosenFiles: Set<String> = emptySet(),
    val loading: Boolean = false,
    val workspaceId: String? = null,
    val entries: List<WorkspaceEntry> = emptyList(),
    val previewPath: String? = null,
    val previewText: String = "",
    val previewImage: Bitmap? = null,
    val previewLoading: Boolean = false,
    val truncated: Boolean = false,
    val binary: Boolean = false,
    @param:StringRes val notice: Int? = null,
    val noticeDetail: String = "",
)

/**
 * 工作台、导入与预览的界面状态模型。
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
    private val mutableUi = MutableStateFlow(ToolUiState(screen = backStack.value.last(), selection = saved.restoreRecoverySelection(settings.value.selection),
        browserPath = saved.get<String>("browserPath").orEmpty(), chosenFiles = saved.get<ArrayList<String>>("chosenFiles").orEmpty().take(128).toSet()))
    val ui = mutableUi.asStateFlow()
    private var previewJob: Job? = null
    private var fileJob: Job? = null

    init {
        val id = saved.get<String>("workspaceId")
        if (backStack.value.last() == Screen.WORKSPACE) {
            if (id != null) openWorkspace(id) else navigate(Screen.HOME)
        } else if (ui.value.screen in listOf(Screen.FILES, Screen.RECOVERY)) browseFiles(ui.value.browserPath)
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
        if (screen !in listOf(Screen.FILES, Screen.RECOVERY)) fileJob?.cancel()
        if (screen != Screen.WORKSPACE) { saved["workspaceId"] = null; previewJob?.cancel() }
        mutableUi.update { it.copy(screen = screen, selection = if (screen == Screen.RECOVERY || screen == Screen.DEFAULTS) settings.value.selection else it.selection, previewPath = null, previewText = "", previewImage = null, notice = null,
            workspaceId = if (screen == Screen.WORKSPACE) it.workspaceId else null,
            entries = if (screen == Screen.WORKSPACE) it.entries else emptyList(), loading = false) }
        saved.saveRecoverySelection(ui.value.selection)
        if (screen == Screen.FILES) browseFiles(ui.value.browserPath)
    }

    /**
     * 处理预览、工作区与顶层页面的返回。
     */
    fun back() {
        if (ui.value.previewPath != null) {
            previewJob?.cancel()
            mutableUi.update { it.copy(previewPath = null, previewImage = null, previewText = "") }
        } else if (ui.value.screen == Screen.FILES && ui.value.browserPath.isNotEmpty() && ui.value.browserPath != StorageAccess.root().absolutePath) {
            browseFiles(File(ui.value.browserPath).parent ?: StorageAccess.root().absolutePath)
        } else if (navigation.back()) showScreen(backStack.value.last())
    }

    /**
     * 异步读取内置文件浏览器目录。
     * @param path 共享存储内的目录，空值使用根目录。
     */
    fun browseFiles(path: String = "") {
        val target = path.ifEmpty { StorageAccess.root().absolutePath }
        mutableUi.update { it.copy(loading = true) }
        fileJob?.cancel()
        fileJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val browser = FileBrowser(StorageAccess.root())
                    browser.checked(target).absolutePath to browser.list(target)
                }
                mutableUi.update { it.copy(browserPath = result.first, browserFiles = result.second, loading = false) }
                saved["browserPath"] = result.first
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { mutableUi.update { it.copy(loading = false) }; showError(R.string.error_read, error.message.orEmpty()) }
        }
    }

    /**
     * 在内置浏览器中选择输入，可同时选择 APK 分包。
     * @param path 已校验的输入路径。
     */
    fun chooseFile(path: String) {
        mutableUi.update { state ->
            state.copy(chosenFiles = if (path in state.chosenFiles) state.chosenFiles - path else (state.chosenFiles + path).take(128).toSet())
        }
        saved["chosenFiles"] = ArrayList(ui.value.chosenFiles)
    }

    /**
     * 将所选文件交给后台恢复，NM 密钥由适配器自动识别。
     */
    fun recoverChosen() {
        if (active.value != null || ui.value.chosenFiles.isEmpty() || !ui.value.selection.hasContent) return
        val files = ui.value.chosenFiles.toList()
        val sources = files.map { JSONObject().put("file", it).put("name", File(it).name) }
        repository.start(ImportDraft(File(files.first()).name, sources, ui.value.selection), "")
        mutableUi.update { it.copy(chosenFiles = emptySet()) }
        saved["chosenFiles"] = arrayListOf<String>()
        navigate(Screen.HOME)
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
     * 保存应用设置。
     * @param value 新设置。
     */
    fun updateSettings(value: ToolSettings) = repository.updateSettings(value)

    /**
     * 取消当前恢复或导出任务。
     */
    fun cancel() = repository.cancel()

    /**
     * 读取可用的工作区文件索引。
     * @param id 历史任务标识。
     */
    fun openWorkspace(id: String) {
        previewJob?.cancel()
        navigation.navigate(Screen.WORKSPACE)
        saved["workspaceId"] = id
        saved["screen"] = Screen.WORKSPACE.name
        mutableUi.update { it.copy(screen = Screen.WORKSPACE, workspaceId = id, entries = emptyList(), previewPath = null, previewImage = null, loading = true) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val task = tasks.value.firstOrNull { it.id == id }
                if (task != null && task.status != TaskStatus.RUNNING && active.value != id) repository.workspaces.reconcileInterrupted(id, task.createdAt)
                repository.workspaces.index(id)
            } }
                .onSuccess { entries -> if (ui.value.workspaceId == id) mutableUi.update { it.copy(entries = entries, loading = false) } }
                .onFailure { error -> if (ui.value.workspaceId == id) {
                    mutableUi.update { it.copy(loading = false) }
                    showError(R.string.error_workspace, error.message.orEmpty())
                } }
        }
    }

    /**
     * 加载大小受限的文本、图片或十六进制预览。
     * @param entry 索引内文件。
     */
    fun preview(entry: WorkspaceEntry) {
        val id = ui.value.workspaceId ?: return
        if (entry.extension in setOf("gdc", "gde")) {
            val relative = entry.path.removePrefix("raw/").removePrefix("project/").removePrefix("recovered/").substringBeforeLast('.') + ".gd"
            ui.value.entries.firstOrNull { it.path == "project/$relative" || it.path == "recovered/$relative" }?.let { preview(it); return }
        }
        previewJob?.cancel()
        mutableUi.update { it.copy(previewPath = entry.path, previewLoading = true, previewText = "", previewImage = null, truncated = false, binary = false) }
        previewJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = repository.workspaces.file(id, entry.path)
                    if (entry.category == "images") {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                        val image = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                        if (image != null) return@withContext PreviewData(image = image)
                    }
                    val data = file.inputStream().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16384)
                        var remaining = 2 * 1024 * 1024
                        while (remaining > 0) {
                            val count = input.read(buffer, 0, minOf(remaining, buffer.size))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                        output.toByteArray()
                    }
                    val truncated = file.length() > data.size
                    val text = runCatching {
                        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        val characters = CharBuffer.allocate(data.size)
                        val result = decoder.decode(ByteBuffer.wrap(data), characters, !truncated)
                        if (result.isError) result.throwException()
                        if (!truncated) decoder.flush(characters)
                        characters.flip().toString()
                    }.getOrNull()
                    if (text != null && '\u0000' !in text) PreviewData(text = text, truncated = truncated)
                    else PreviewData(text = data.take(512).chunked(16).mapIndexed { index, row -> "%08x  ".format(index * 16) + row.joinToString(" ") { "%02x".format(it.toInt() and 255) } }.joinToString("\n"), binary = true, truncated = file.length() > 512)
                }
            }.onSuccess { preview -> mutableUi.update { it.copy(previewLoading = false, previewText = preview.text, previewImage = preview.image, truncated = preview.truncated, binary = preview.binary) } }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) { mutableUi.update { state -> state.copy(previewLoading = false) }; showError(R.string.error_preview, it.message.orEmpty()) } }
        }
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
                if (!(draft.selection ?: settings.value.selection).hasContent) { showError(R.string.choose_recovery_content); return@launch }
                repository.start(draft, "")
                navigate(Screen.HOME)
            }.onFailure { showError(R.string.error_retry, it.message.orEmpty()) }
        }
    }

    /**
     * 清理一个已结束任务。
     * @param id 用户确认的任务标识。
     */
    fun delete(id: String) {
        viewModelScope.launch { runCatching { repository.delete(id) }.onFailure { showError(R.string.error_cleanup, it.message.orEmpty()) } }
    }

    /**
     * 清理已结束的全部工作区。
     */
    fun clearFinished() {
        viewModelScope.launch {
            runCatching { repository.clearFinished() }.onSuccess { showError(R.string.cleanup_complete) }.onFailure { showError(R.string.error_cleanup, it.message.orEmpty()) }
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
 * IO 线程生成的轻量文件预览。
 * @author Tingdong
 * @date 2026-11-21
 */
private data class PreviewData(val text: String = "", val image: Bitmap? = null, val truncated: Boolean = false, val binary: Boolean = false)

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
