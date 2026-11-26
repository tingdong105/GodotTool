package com.winter.godot.tool.feature.settings

import android.os.Build
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.winter.godot.tool.R
import com.winter.godot.tool.data.ThemeMode
import com.winter.godot.tool.data.ToolSettings
import com.winter.godot.tool.feature.compare.SnapshotOptions
import com.winter.godot.tool.feature.recovery.RecoveryOptions
import com.winter.godot.tool.ui.AdaptiveColumns
import com.winter.godot.tool.ui.LocalRootBottomInset
import com.winter.godot.tool.ui.ToolDevice
import com.winter.godot.tool.ui.components.SectionTitle
import com.winter.godot.tool.ui.rememberMovableToolContent
import com.winter.godot.tool.ui.theme.Theme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分组管理外观、恢复偏好、对比、备份和关于入口。
 * @param onAbout 打开关于软件页面。
 * @param onComparisonRules 打开对比过滤规则设置。
 * @param onComparisonFocusRules 打开对比关心规则设置。
 * @param onBackup 选择本地备份范围。
 * @param onRestore 选择本地备份 ZIP。
 * @param backupEnabled 是否可启动备份维护。
 * @param onChange 即时保存外观与解包选项。
 * @param onThemeChange 立即提交主题及点击位置的窗口坐标。
 * @param onThemePrepare 打开菜单前准备不含菜单的截图。
 * @param onThemeDismiss 取消选择时释放截图。
 */
@Composable
fun SettingsScreen(settings: ToolSettings, onChange: (ToolSettings) -> Unit,
    onThemeChange: (ThemeMode, Offset) -> Unit = { mode, _ -> onChange(settings.copy(theme = mode)) },
    onThemePrepare: suspend () -> Boolean = { true }, onThemeDismiss: () -> Unit = {}, onAbout: () -> Unit = {}, onComparisonRules: () -> Unit = {}, onComparisonFocusRules: () -> Unit = {},
    onBackup: () -> Unit = {}, onRestore: () -> Unit = {}, backupEnabled: Boolean = true) {
    val phoneList = rememberLazyListState()
    val tabletScroll = rememberScrollState()
    val glassSupported = Build.VERSION.SDK_INT >= 33 && isRuntimeShaderSupported()
    val bottomInset = LocalRootBottomInset.current
    val appearance = rememberMovableToolContent {
        SectionTitle(stringResource(R.string.theme))
        Card {
            ThemeModePreference(settings.theme, onThemeChange, onThemePrepare, onThemeDismiss)
            SwitchPreference(settings.floatingBottomBar, { onChange(settings.copy(floatingBottomBar = it)) },
                stringResource(R.string.floating_bottom_bar))
            AnimatedVisibility(settings.floatingBottomBar) {
                Column {
                    SwitchPreference(checked = settings.liquidGlass && glassSupported,
                        onCheckedChange = { onChange(settings.copy(liquidGlass = it)) },
                        title = stringResource(R.string.liquid_glass), enabled = glassSupported)
                }
            }
        }
    }
    val recovery = rememberMovableToolContent {
        SectionTitle(stringResource(R.string.recovery_content))
        Card {
            RecoveryOptions(settings.selection, { onChange(settings.withSelection(it)) }, embedded = true)
            SwitchPreference(settings.snapshot.generate, { onChange(settings.copy(snapshot = SnapshotOptions(it))) }, stringResource(R.string.generate_snapshot))
        }
    }
    val comparison = rememberMovableToolContent {
        SectionTitle(stringResource(R.string.compare))
        Card {
            ArrowPreference(title = stringResource(R.string.compare_filter_rules), onClick = onComparisonRules)
            ArrowPreference(title = stringResource(R.string.compare_focus_rules), onClick = onComparisonFocusRules)
        }
    }
    val backup = rememberMovableToolContent {
        SectionTitle(stringResource(R.string.backup_group))
        Card {
            ArrowPreference(title = stringResource(R.string.backup_export), enabled = backupEnabled, onClick = onBackup)
            ArrowPreference(title = stringResource(R.string.backup_restore), enabled = backupEnabled, onClick = onRestore)
        }
    }
    val about = rememberMovableToolContent {
        SectionTitle(stringResource(R.string.about))
        Card {
            ArrowPreference(title = stringResource(R.string.about_software), onClick = onAbout)
        }
    }
    if (ToolDevice.isPhone) {
        LazyColumn(state = phoneList, contentPadding = PaddingValues(start = Theme.spacing.md, end = Theme.spacing.md,
            top = Theme.spacing.sm, bottom = Theme.spacing.sm + bottomInset)) {
            item { appearance() }
            item { recovery() }
            item { comparison() }
            item { backup() }
            item { about() }
        }
    } else {
        AdaptiveColumns(scrollState = tabletScroll, spacing = 0.dp, contentPadding = PaddingValues(start = Theme.spacing.md, end = Theme.spacing.md,
            top = Theme.spacing.sm, bottom = Theme.spacing.sm + bottomInset),
            primary = { appearance(); recovery() }, secondary = { comparison(); backup(); about() })
    }
}

/**
 * 预备旧画面，并在点选时立即提交主题与原始触摸坐标。
 * @param mode 当前主题偏好。
 * @param onSelected 选中的主题和点击位置的窗口坐标。
 * @param onPrepare 打开菜单前准备截图。
 * @param onDismiss 取消选择时释放截图。
 */
@Composable
private fun ThemeModePreference(mode: ThemeMode, onSelected: (ThemeMode, Offset) -> Unit,
    onPrepare: suspend () -> Boolean, onDismiss: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var touchOrigin by remember { mutableStateOf<Offset?>(null) }
    var anchorCenter by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val discard by rememberUpdatedState(onDismiss)
    DisposableEffect(Unit) { onDispose { discard() } }
    val view = LocalView.current
    val screenLocation = remember { IntArray(2) }
    val windowLocation = remember { IntArray(2) }
    val recordTouch: (MotionEvent) -> Unit = remember(view) {
        { event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                view.getLocationOnScreen(screenLocation)
                view.getLocationInWindow(windowLocation)
                // 原始屏幕坐标只减窗口偏移，不受菜单缩放和行重组影响。
                touchOrigin = Offset(event.rawX - screenLocation[0] + windowLocation[0],
                    event.rawY - screenLocation[1] + windowLocation[1])
            }
        }
    }
    val labels = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark))
    val haptic = LocalHapticFeedback.current
    val actionColor = MiuixTheme.colorScheme.onSurfaceVariantActions
    BasicComponent(title = stringResource(R.string.appearance_mode), role = Role.DropdownList,
        modifier = Modifier.onGloballyPositioned { anchorCenter = it.boundsInWindow().center },
        holdDownState = expanded || opening, onClick = {
            if (!opening && !expanded) {
                opening = true
                touchOrigin = null
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                scope.launch {
                    try {
                        if (onPrepare()) {
                            expanded = true
                        }
                    } finally {
                        opening = false
                    }
                }
            }
        }, endActions = {
            Text(labels[mode.ordinal], modifier = Modifier.padding(end = Theme.spacing.sm)
                .align(Alignment.CenterVertically).weight(1f, fill = false),
                fontSize = MiuixTheme.textStyles.body2.fontSize, color = actionColor, textAlign = TextAlign.End)
            DropdownArrowEndAction(actionColor)
            if (expanded) OverlayListPopup(show = true, alignment = PopupPositionProvider.Align.End,
                popupModifier = Modifier.motionEventSpy(recordTouch),
                onDismissRequest = { expanded = false; onDismiss() }) {
                ListPopupColumn {
                    ThemeMode.entries.forEach { option ->
                        DropdownImpl(text = labels[option.ordinal], optionSize = ThemeMode.entries.size,
                            isSelected = mode == option, index = option.ordinal, onSelectedIndexChange = {
                                if (expanded) {
                                    val origin = touchOrigin ?: anchorCenter
                                    expanded = false
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    if (option != mode) onSelected(option, origin) else onDismiss()
                                }
                            })
                    }
                }
            }
        })
}
