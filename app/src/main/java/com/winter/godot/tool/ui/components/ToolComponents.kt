package com.winter.godot.tool.ui.components

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.winter.godot.tool.R
import com.winter.godot.tool.data.TaskRecord
import com.winter.godot.tool.data.TaskStatus
import com.winter.godot.tool.ui.ToolDevice
import com.winter.godot.tool.ui.theme.Theme
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 显示已有矢量资源图标。
 * @param resource 图标资源。
 * @param description 无障碍描述，装饰图标为空。
 * @param modifier 布局修饰。
 */
@Composable
fun ToolIcon(@DrawableRes resource: Int, modifier: Modifier = Modifier, description: String? = null) {
    Icon(imageVector = toolIconVector(resource), contentDescription = description, modifier = modifier.size(Theme.sizes.icon),
        tint = if (resource == R.drawable.ic_android) Theme.status.success else LocalContentColor.current)
}

/**
 * 优先使用 miuix 图标，缺少的文件类型才读取同风格资源。
 * @param resource 图标语义资源。
 */
@Composable
fun toolIconVector(@DrawableRes resource: Int): ImageVector = when (resource) {
    R.drawable.ic_back -> MiuixIcons.Back
    R.drawable.ic_settings -> MiuixIcons.Settings
    R.drawable.ic_export -> MiuixIcons.Share
    R.drawable.ic_chevron -> MiuixIcons.Basic.ArrowRight
    else -> ImageVector.vectorResource(resource)
}

/**
 * 统一的主操作按钮。
 * @param text 操作文案。
 * @param onClick 操作事件。
 * @param enabled 可用状态。
 * @param modifier 布局修饰。
 * @param icon 可选前置图标。
 */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.65f, stiffness = 500f), label = "buttonPress")
    Button(onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale }, enabled = enabled,
        interactionSource = interaction, colors = ButtonDefaults.buttonColorsPrimary()) {
        if (icon != null) Icon(icon, null, Modifier.padding(end = Theme.spacing.sm).size(Theme.spacing.lg))
        Text(text, style = MiuixTheme.textStyles.button)
    }
}

/**
 * 文件列表中的 miuix 搜索输入框。
 * @param query 当前查询。
 * @param label 搜索范围提示。
 * @param onChange 更新过滤条件。
 */
@Composable
fun FileSearch(query: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    InputField(query = query, onQueryChange = onChange, label = label, expanded = focused,
        onExpandedChange = { focused = it }, onSearch = { focus.clearFocus() },
        modifier = modifier.onFocusChanged { focused = it.isFocused })
}

/**
 * 有明确空态说明的区域。
 * @param title 空态标题。
 * @param detail 可选操作引导。
 * @param icon 矢量图标。
 */
@Composable
fun EmptyContent(title: String, detail: String = "", @DrawableRes icon: Int = R.drawable.ic_folder) {
    Column(Modifier.fillMaxWidth().padding(Theme.spacing.xxl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
        ToolIcon(icon, modifier = Modifier.size(Theme.sizes.largeIcon))
        Text(title, style = Theme.typography.title)
        if (detail.isNotEmpty()) Text(detail, style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/**
 * 加载未知总量的内容。
 */
@Composable
fun LoadingContent() { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }

/**
 * 分组标题与下方标准列表的正文左侧对齐。
 * @param text 分组名或当前目录。
 */
@Composable
fun SectionTitle(text: String) {
    SmallTitle(text, insideMargin = PaddingValues(horizontal = Theme.spacing.lg, vertical = Theme.spacing.sm))
}

/**
 * 手机与平板共享的 miuix 对话框容器。
 * @param title 对话框标题。
 * @param onDismiss 关闭事件。
 * @param content 自定义内容。
 */
@Composable
fun ToolDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    OverlayDialog(show = true, title = title, maxWidth = if (ToolDevice.isPad) Theme.sizes.dialogMax else DialogDefaults.MaxWidth, onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Theme.spacing.lg)) {
            content()
        }
    }
}

/**
 * 需用户确认的清理对话框。
 * @param title 标题。
 * @param detail 清理内容。
 * @param onDismiss 取消回调。
 * @param onConfirm 确认回调。
 */
@Composable
fun ConfirmDialog(title: String, detail: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ToolDialog(title, onDismiss) {
        Text(detail, style = Theme.typography.body)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
            TextButton(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.confirm), onConfirm, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 本地化文件大小。
 * @param bytes 文件字节数。
 * @return 系统格式化的大小。
 */
@Composable
fun fileSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0))

/**
 * 将任务状态映射为字符串资源。
 * @param status 任务状态。
 * @return 本地化状态资源。
 */
@StringRes
fun statusResource(status: TaskStatus): Int = when (status) {
    TaskStatus.RUNNING -> R.string.status_running
    TaskStatus.SUCCEEDED -> R.string.status_succeeded
    TaskStatus.PARTIAL -> R.string.status_partial
    TaskStatus.FAILED -> R.string.status_failed
    TaskStatus.CANCELED -> R.string.status_canceled
    TaskStatus.INTERRUPTED -> R.string.status_interrupted
}

/**
 * 为源码恢复中断提供明确的任务状态。
 * @param task 已持久化的任务。
 * @return 中文状态资源。
 */
@StringRes
fun statusResource(task: TaskRecord): Int {
    val summary = runCatching { JSONObject(task.summary) }.getOrNull()
    val warning = summary?.optString("warning").orEmpty()
    return if (task.status == TaskStatus.PARTIAL && (warning in setOf("RECOVERY_INTERRUPTED", "NATIVE_RECOVERY_INTERRUPTED", "SCRIPT_RECOVERY_FAILED", "RECOVERY_UNSUPPORTED") || (summary?.optInt("failedScripts") ?: 0) > 0))
        R.string.script_recovery_incomplete else statusResource(task.status)
}

/**
 * 将核心阶段映射为字符串资源。
 * @param stage 核心阶段。
 * @return 本地化阶段资源。
 */
@StringRes
fun stageResource(stage: String): Int = when (stage) {
    "import" -> R.string.stage_import
    "probe" -> R.string.stage_probe
    "decrypt" -> R.string.stage_decrypt
    "extract" -> R.string.stage_extract
    "recover" -> R.string.stage_recover
    "snapshot" -> R.string.stage_snapshot
    "checkpoint" -> R.string.log_checkpoint
    "publish" -> R.string.stage_publish
    "assemble" -> R.string.stage_assemble
    "export" -> R.string.stage_export
    "done" -> R.string.stage_done
    else -> R.string.stage_prepare
}

/**
 * 将诊断代码映射为可操作的中文错误。
 * @param code 核心诊断代码。
 * @return 本地化错误资源。
 */
@StringRes
fun errorResource(code: String): Int = when (code) {
    "SINGLE_INPUT_REQUIRED" -> R.string.single_file_only
    "BAD_KEY" -> R.string.error_bad_key
    "EMPTY_SELECTION" -> R.string.choose_recovery_content
    "NO_MATCHING_CONTENT" -> R.string.no_matching_content
    "OPEN_FAILED", "MISSING_ENTRY", "TRUNCATED", "NM_BAD_INDEX", "NM_BAD_CIPHER" -> R.string.error_open
    "CHECKSUM" -> R.string.error_checksum
    "NO_SPACE" -> R.string.error_space
    "NM_BUILD_UNSUPPORTED", "NM_BAD_ENGINE", "NM_KEY_NOT_FOUND" -> R.string.error_nm_build
    "STORAGE_PERMISSION" -> R.string.storage_permission_detail
    "UNSAFE_PATH", "DUPLICATE_PATH", "SPLIT_CONFLICT" -> R.string.error_unsafe_path
    "ENGINE_INIT", "ENGINE_EXIT", "ENGINE_STOPPED", "CORE_MISSING", "ENGINE_START_TIMEOUT" -> R.string.error_engine
    "TIMEOUT" -> R.string.error_timeout
    "READ_PERMISSION" -> R.string.error_read
    "EXPORT_PERMISSION", "EXPORT_EXISTS", "EMPTY_EXPORT" -> R.string.error_export
    "UNSUPPORTED_INPUT" -> R.string.error_input
    "ENTRY_LIMIT", "SIZE_LIMIT" -> R.string.error_limit
    "SERVICE_START" -> R.string.error_service
    else -> R.string.error_io
}
