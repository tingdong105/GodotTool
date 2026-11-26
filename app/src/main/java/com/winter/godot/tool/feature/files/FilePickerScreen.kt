package com.winter.godot.tool.feature.files

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.LocalToolContentPadding
import com.winter.godot.tool.ui.ToolUiState
import com.winter.godot.tool.ui.components.EmptyContent
import com.winter.godot.tool.ui.components.LoadingContent
import com.winter.godot.tool.ui.components.PrimaryButton
import com.winter.godot.tool.ui.components.ToolIcon
import com.winter.godot.tool.ui.components.fileSize
import com.winter.godot.tool.ui.theme.Theme
import com.winter.godot.tool.ui.toolContentPadding
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 连续浏览共享存储，以方向转场切换目录并保留各目录滚动位置。
 * @param state 当前目录和选择状态。
 * @param enabled 是否允许选择文件。
 * @param onBrowse 进入或退出目录。
 * @param onChoose 将单选输入切换为当前文件。
 * @param onConfirm 确认文件并返回首页配置。
 * @param onRefresh 重新读取当前目录。
 */
@Composable
fun FilePickerScreen(state: ToolUiState, enabled: Boolean, onBrowse: (String) -> Unit, onChoose: (String) -> Unit, onConfirm: () -> Unit,
    onRefresh: () -> Unit = {}) {
    val directories = rememberSaveableStateHolder()
    val directory = remember(state.browserPath, state.browserFiles) { state.browserPath to state.browserFiles }
    var showLoading by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading, state.browserRefreshing) {
        showLoading = false
        if (state.loading && !state.browserRefreshing) { delay(180); showLoading = true }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).clipToBounds()) {
            AnimatedContent(targetState = directory, contentKey = { it.first }, label = "directoryNavigation",
                transitionSpec = {
                    if (initialState.first.isEmpty()) fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                    else {
                        val direction = if (initialState.first.startsWith(targetState.first.trimEnd('/') + "/")) -1 else 1
                        (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { direction * it / 5 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -direction * it / 5 } + fadeOut(tween(180)))
                    }
                }, modifier = Modifier.fillMaxSize()) { (path, files) ->
                directories.SaveableStateProvider(path) {
                    Column(Modifier.fillMaxSize()) {
                        val interactive = path == state.browserPath && !state.loading
                        val refreshing = state.browserRefreshing && path == state.browserPath
                        val refreshState = rememberPullToRefreshState()
                        PullToRefreshBox(isRefreshing = refreshing, state = refreshState,
                            onRefresh = { if (interactive && path.isNotEmpty()) onRefresh() }, modifier = Modifier.weight(1f),
                            enabled = path == state.browserPath && path.isNotEmpty() && (!state.loading || refreshing),
                            indicator = {
                                PullToRefreshDefaults.Indicator(state = refreshState, isRefreshing = refreshing,
                                    modifier = Modifier.align(Alignment.TopCenter).padding(top = LocalToolContentPadding.current.calculateTopPadding()), containerColor = MiuixTheme.colorScheme.surface,
                                    color = MiuixTheme.colorScheme.primary)
                            }) {
                            LazyColumn(Modifier.fillMaxSize().selectableGroup(), contentPadding = toolContentPadding(PaddingValues(bottom = Theme.spacing.sm)),
                                overscrollEffect = null) {
                                if (files.isEmpty() && path.isNotEmpty() && (!state.loading || state.browserRefreshing)) {
                                    item(key = "empty") { EmptyContent(stringResource(R.string.empty_directory)) }
                                }
                                items(files, key = { it.path }) { entry ->
                                    val row = Modifier.animateItem()
                                    val apk = !entry.directory && entry.name.endsWith(".apk", ignoreCase = true)
                                    val startIcon: @Composable () -> Unit = {
                                        Box(Modifier.padding(end = Theme.spacing.md).size(Theme.spacing.xxl), contentAlignment = Alignment.Center) {
                                            ToolIcon(when { entry.directory -> R.drawable.ic_folder; apk -> R.drawable.ic_android; else -> R.drawable.ic_file },
                                                Modifier.size(if (apk) Theme.spacing.xxl else Theme.sizes.icon))
                                        }
                                    }
                                    if (entry.directory) ArrowPreference(title = entry.name, modifier = row, startAction = startIcon,
                                        enabled = interactive, onClick = { onBrowse(entry.path) })
                                    else BasicComponent(enabled = enabled && interactive, role = Role.RadioButton,
                                        modifier = row.semantics { selected = entry.path in state.chosenFiles }, startAction = startIcon,
                                        onClick = { onChoose(entry.path) }, endActions = {
                                            RadioButton(selected = entry.path in state.chosenFiles, onClick = null, enabled = enabled && interactive)
                                        }) {
                                        Text(entry.name, style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Medium,
                                            color = if (enabled && interactive) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.disabledOnSecondaryVariant)
                                        Text(stringResource(R.string.file_metadata, fileUpdatedAt(entry.modifiedAt), fileSize(entry.size)),
                                            style = Theme.typography.detail,
                                            color = if (enabled && interactive) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSecondaryVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (showLoading || state.loading && state.browserPath.isEmpty()) LoadingContent()
        }
        PrimaryButton(stringResource(R.string.confirm_file), onConfirm,
            Modifier.fillMaxWidth().padding(Theme.spacing.md),
            enabled = enabled && state.chosenFiles.size == 1 && !state.loading, icon = MiuixIcons.Basic.Check)
    }
}

/**
 * 固定显示最多两行路径并拦截点击。
 * @param path 当前浏览目录的绝对路径。
 */
@Composable
fun FilePickerPath(path: String) {
    val relative = path.removePrefix(StorageAccess.root().absolutePath).trim('/')
    Text(text = listOf(stringResource(R.string.shared_storage), relative).filter { it.isNotEmpty() }.joinToString(" / "),
        modifier = Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = Theme.spacing.lg, vertical = Theme.spacing.sm),
        style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.onBackgroundVariant,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/**
 * 顶栏排序菜单，排序依据单选，独立选项使用复选语义。
 * @param order 当前排序依据。
 * @param reversed 是否逆向排列。
 * @param directoryOnly 是否仅作用于当前目录。
 * @param onChange 更新排序选项。
 * @param enabled 是否允许修改排序。
 */
@Composable
fun FileSortButton(order: BrowserSort, reversed: Boolean, directoryOnly: Boolean, onChange: (BrowserSort, Boolean, Boolean) -> Unit, enabled: Boolean = true) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.size(Theme.sizes.largeIcon)) {
            Icon(MiuixIcons.Sort, stringResource(R.string.sort_files), Modifier.size(Theme.sizes.icon))
        }
        OverlayListPopup(show = expanded, onDismissRequest = { expanded = false }, alignment = PopupPositionProvider.Align.End) {
            ListPopupColumn {
                val orders = listOf(
                    DropdownItem(stringResource(R.string.sort_by_name), selected = order == BrowserSort.NAME,
                        onClick = { onChange(BrowserSort.NAME, reversed, directoryOnly); expanded = false }),
                    DropdownItem(stringResource(R.string.sort_by_modified), selected = order == BrowserSort.MODIFIED,
                        onClick = { onChange(BrowserSort.MODIFIED, reversed, directoryOnly); expanded = false }),
                    DropdownItem(stringResource(R.string.sort_by_size), selected = order == BrowserSort.SIZE,
                        onClick = { onChange(BrowserSort.SIZE, reversed, directoryOnly); expanded = false }),
                )
                Column(Modifier.selectableGroup()) {
                    orders.forEachIndexed { index, item ->
                        DropdownImpl(item = item, optionSize = orders.size, isSelected = item.selected, index = index,
                            isFirst = index == 0, isLast = false, onSelectedIndexChange = { item.onClick?.invoke() })
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = Theme.spacing.lg + Theme.spacing.xs, vertical = Theme.spacing.xs))
                val toggles = listOf(
                    Triple(stringResource(R.string.sort_reversed), reversed, { onChange(order, !reversed, directoryOnly) }),
                    Triple(stringResource(R.string.sort_current_directory), directoryOnly, { onChange(order, reversed, !directoryOnly) }),
                )
                toggles.forEachIndexed { index, (label, checked, onToggle) ->
                    // 替换库默认单选语义，保留菜单外观。
                    Box(Modifier.clearAndSetSemantics {
                        text = AnnotatedString(label)
                        role = Role.Checkbox
                        toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                        onClick { onToggle(); true }
                    }) {
                        DropdownImpl(item = DropdownItem(label, selected = checked), optionSize = toggles.size,
                            isSelected = checked, index = index, isFirst = false, isLast = index == toggles.lastIndex,
                            onSelectedIndexChange = { onToggle() })
                    }
                }
            }
        }
    }
}

/**
 * 按系统日期与时间格式显示文件更新时间。
 * @param timestamp 文件修改时间戳。
 * @return 本地化更新时间，缺失时显示未知。
 */
@Composable
private fun fileUpdatedAt(timestamp: Long): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val unknown = stringResource(R.string.unknown_update_time)
    return remember(timestamp, configuration) {
        if (timestamp <= 0) unknown
        else "${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))} ${DateFormat.getTimeFormat(context).format(Date(timestamp))}"
    }
}
