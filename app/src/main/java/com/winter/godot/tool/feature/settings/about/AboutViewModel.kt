package com.winter.godot.tool.feature.settings.about

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 关于页的安装版本与离线许可状态。
 * @author Tingdong
 * @date 2026-11-25
 */
data class AboutUiState(
    val versionName: String? = null,
    val versionCode: Long = 0,
    val licenses: List<String>? = null,
    val licenseText: String? = null,
    val licenseFailed: Boolean = false,
)

/**
 * 后台读取当前安装版本和离线许可。
 * @author Tingdong
 * @date 2026-11-25
 */
class AboutViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val SOURCE_URL = "https://github.com/tingdong105/GodotTool"
        const val DEVELOPER_URL = "https://github.com/tingdong105/"
        const val DEVELOPER_AVATAR_URL = "https://q1.qlogo.cn/g?b=qq&nk=3530793519&s=640"
    }

    private val mutableUi = MutableStateFlow(AboutUiState())
    val ui = mutableUi.asStateFlow()
    private var licenseJob: Job? = null

    init {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { application.packageManager.getPackageInfo(application.packageName, 0) }.getOrNull()
            }
            mutableUi.update { it.copy(versionName = info?.versionName, versionCode = info?.longVersionCode ?: 0) }
        }
    }

    /**
     * 首次进入或重试时读取许可，成功结果在当前进程缓存。
     */
    fun loadLicenses() {
        if (licenseJob?.isActive == true || mutableUi.value.licenses != null) return
        mutableUi.update { it.copy(licenseFailed = false) }
        licenseJob = viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    val assets = getApplication<Application>().assets
                    val text = assets.open("txt/licenses.txt").bufferedReader().use { it.readText() }
                    val notice = assets.open("txt/about-effect-notice.txt").bufferedReader().use { it.readText() }
                    val imageNotice = assets.open("txt/coil-notice.txt").bufferedReader().use { it.readText() }
                    val glassNotice = assets.open("txt/navigation-glass-notice.txt").bufferedReader().use { it.readText() }
                    val complete = notice + "\n\n" + imageNotice + "\n\n" + glassNotice + "\n\n" + text
                    complete to splitLicenseText(complete)
                }
                mutableUi.update { it.copy(licenseText = content.first, licenses = content.second) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUi.update { it.copy(licenseFailed = true) }
            }
        }
    }
}

/**
 * 按行拆分长许可，保留所有原文和换行。
 * 使用示例：splitLicenseText(licenseText)。
 * @param text 完整许可原文。
 * @return 每段最多约四千字符的连续文本。
 */
internal suspend fun splitLicenseText(text: String): List<String> {
    require(text.isNotBlank()) { "Empty license asset" }
    val blocks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        coroutineContext.ensureActive()
        var end = minOf(start + 4096, text.length)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline >= start) end = newline + 1
            else if (text[end - 1].isHighSurrogate()) end--
        }
        blocks += text.substring(start, end)
        start = end
    }
    return blocks
}
