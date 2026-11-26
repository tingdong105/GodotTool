package com.winter.godot.tool.feature.compare

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.LocalRootBottomInset
import com.winter.godot.tool.ui.LocalWideContent
import com.winter.godot.tool.ui.ToolDevice
import com.winter.godot.tool.ui.components.ConfirmDialog
import com.winter.godot.tool.ui.components.EmptyContent
import com.winter.godot.tool.ui.components.PrimaryButton
import com.winter.godot.tool.ui.components.SectionTitle
import com.winter.godot.tool.ui.components.ToolIcon
import com.winter.godot.tool.ui.theme.Theme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.FloatingActionButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.util.Date

/**
 * 选择快照并管理独立的对比历史。
 * @param state 对比与历史状态。
 * @param onChoose 选择旧版或新版。
 * @param onStart 开始比较。
 * @param onCancel 取消解析或对比。
 * @param onOpen 打开历史。
 * @param onSelect 切换历史选择。
 * @param onDelete 删除已选记录。
 * @param onProgressFinished 进度对话框关闭后确认完成。
 */
@Composable
fun CompareScreen(state: CompareUiState, onChoose: (Boolean) -> Unit, onStart: () -> Unit, onCancel: () -> Unit,
    onOpen: (String) -> Unit, onSelect: (String) -> Unit, onDelete: () -> Unit, onProgressFinished: (String) -> Unit = {}) {
    var deleting by remember { mutableStateOf(false) }
    val wide = LocalWideContent.current
    val historyList = rememberLazyListState()
    val inputScroll = rememberScrollState()
    val selecting = state.selected.isNotEmpty()
    val navigationInset = LocalRootBottomInset.current
    val bottomPadding by animateDpAsState(if (selecting) FloatingActionButtonDefaults.MinHeight + Theme.spacing.xxl else Theme.spacing.md,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f), label = "historyBottomPadding")
    LaunchedEffect(selecting) { if (!selecting) deleting = false }
    val history: LazyListScope.() -> Unit = {
        item(key = "history-title") {
            SectionTitle(stringResource(R.string.compare_history))
        }
        if (state.historyLoading) item(key = "history-loading") {
            Box(Modifier.fillParentMaxHeight(0.5f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        else if (state.histories.isEmpty()) item(key = "history-empty") {
            EmptyContent(stringResource(R.string.compare_empty), icon = R.drawable.ic_compare)
        }
        items(state.histories, key = { it.id }) { record ->
            HistoryRow(record, record.id in state.selected, state.selected.isNotEmpty(), { onOpen(record.id) }, { onSelect(record.id) })
        }
    }
    val historyPane: @Composable (Modifier, Boolean) -> Unit = { modifier, showInputs ->
        Box(modifier) {
            LazyColumn(Modifier.fillMaxSize(), state = historyList,
                contentPadding = PaddingValues(start = Theme.spacing.md, top = Theme.spacing.md, end = Theme.spacing.md, bottom = bottomPadding + navigationInset),
                verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
                if (showInputs) {
                    item(key = "inputs") { ComparisonInputs(state, onChoose, onStart) }
                    state.error?.let { error -> item(key = "error") { Text(stringResource(error), color = MiuixTheme.colorScheme.error) } }
                }
                history()
            }
            ComparisonDeleteAction(selecting, { if (state.selected.isNotEmpty()) deleting = true },
                Modifier.align(Alignment.BottomEnd).padding(bottom = navigationInset).padding(Theme.spacing.lg))
        }
    }
    if (ToolDevice.isPhone) {
        historyPane(Modifier.fillMaxSize(), true)
    } else {
        Row(Modifier.fillMaxSize()) {
            if (wide) Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(inputScroll)
                .padding(start = Theme.spacing.md, top = Theme.spacing.md, end = Theme.spacing.sm, bottom = Theme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
                ComparisonInputs(state, onChoose, onStart)
                state.error?.let { error -> Text(stringResource(error), color = MiuixTheme.colorScheme.error) }
            }
            historyPane(Modifier.weight(1f).fillMaxHeight(), !wide)
        }
    }
    if (deleting && selecting) ConfirmDialog(
        title = stringResource(R.string.compare_delete_title),
        detail = stringResource(R.string.compare_delete_confirm, state.selected.size),
        onDismiss = { deleting = false },
        onConfirm = { deleting = false; if (state.selected.isNotEmpty()) onDelete() },
        confirmText = stringResource(R.string.compare_confirm_delete))
    state.progress?.let { progress -> key(progress.id) { ComparisonProgressDialog(progress, onCancel, onProgressFinished) } }
}

/**
 * 历史区域内弹性显示删除按钮。
 * @param selecting 是否处于多选状态。
 * @param onDelete 请求删除确认。
 * @param modifier 按钮在历史区域中的位置。
 */
@Composable
private fun ComparisonDeleteAction(selecting: Boolean, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(selecting, modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.65f, stiffness = 400f)) { it * 2 } + fadeIn(),
        exit = slideOutVertically(spring(dampingRatio = 0.8f, stiffness = 450f)) { it * 2 } + fadeOut()) {
        FloatingActionButton(onClick = onDelete, containerColor = MiuixTheme.colorScheme.error) {
            Icon(MiuixIcons.Delete, stringResource(R.string.delete), Modifier.size(Theme.sizes.icon), tint = MiuixTheme.colorScheme.onError)
        }
    }
}

/**
 * 组合快照输入与比较操作。
 * @param state 当前对比状态。
 * @param onChoose 选择版本快照。
 * @param onStart 开始比较。
 */
@Composable
private fun ComparisonInputs(state: CompareUiState, onChoose: (Boolean) -> Unit, onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
        SnapshotCard(R.string.compare_old, state.before, state.loadingBefore, !state.busy) { onChoose(true) }
        SnapshotCard(R.string.compare_new, state.after, state.loadingAfter, !state.busy) { onChoose(false) }
        PrimaryButton(stringResource(R.string.compare_start), onStart, Modifier.fillMaxWidth(), enabled = !state.busy && state.before != null && state.after != null &&
            !state.rulesLoading && !state.rulesLoadFailed && !state.rulesSaving)
    }
}

/**
 * 显示版本方向与时间的历史条目，长按展开多选状态。
 * @param record 记录摘要。
 * @param selected 当前是否选中。
 * @param selecting 是否处于多选模式。
 * @param onOpen 打开结果。
 * @param onSelect 切换选择。
 */
@Composable
private fun HistoryRow(record: ComparisonRecord, selected: Boolean, selecting: Boolean, onOpen: () -> Unit, onSelect: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val date = remember(record.createdAt) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.createdAt)) }
    val background by animateColorAsState(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.1f).compositeOver(MiuixTheme.colorScheme.surfaceContainer)
        else MiuixTheme.colorScheme.surfaceContainer, label = "historySelectedColor")
    Card(colors = CardDefaults.defaultColors(color = background)) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) onSelect() else onOpen() }, onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); onSelect()
        }, role = if (selecting) Role.Checkbox else Role.Button).padding(Theme.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(selecting, enter = expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
                exit = shrinkHorizontally(spring(dampingRatio = 0.85f, stiffness = 450f)) + fadeOut()) {
                Box(Modifier.padding(end = Theme.spacing.md)) {
                    Checkbox(state = if (selected) ToggleableState.On else ToggleableState.Off, onClick = { onSelect() })
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Theme.spacing.sm)) {
                Text(ComparisonText.title(record), style = Theme.typography.body)
                Text(date, style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            AnimatedVisibility(!selecting, enter = expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
                exit = shrinkHorizontally(spring(dampingRatio = 0.85f, stiffness = 450f)) + fadeOut()) {
                Box(Modifier.padding(start = Theme.spacing.md)) { ToolIcon(R.drawable.ic_chevron) }
            }
        }
    }
}

/**
 * 显示一个快照输入与分析状态。
 * @param title 版本方向标签。
 * @param input 已导入快照。
 * @param loading 是否正在读取。
 * @param enabled 是否允许选择。
 * @param onChoose 打开选择器。
 */
@Composable
private fun SnapshotCard(@StringRes title: Int, input: SnapshotInput?, loading: Boolean, enabled: Boolean, onChoose: () -> Unit) {
    val description = if (input == null) stringResource(if (loading) R.string.compare_importing else R.string.compare_choose)
    else listOf(input.header.name.ifEmpty { input.name }, input.header.version,
        stringResource(if (input.header.complete) R.string.compare_complete else R.string.compare_incomplete)).filter { it.isNotEmpty() }.joinToString(" · ")
    Card { ArrowPreference(title = stringResource(title), summary = description, enabled = enabled, onClick = onChoose) }
}

/**
 * 获取变化类别的本地化名称。
 * @param kind 变化类别。
 * @param mode 查看范围。
 * @return 字符串资源。
 */
@StringRes
fun changeLabel(kind: ChangeKind, mode: ComparisonMode = ComparisonMode.METHODS): Int = when {
    mode == ComparisonMode.CONSTANTS && kind == ChangeKind.ADDED -> R.string.compare_member_added
    mode == ComparisonMode.CONSTANTS && kind == ChangeKind.MODIFIED -> R.string.compare_member_modified
    mode == ComparisonMode.CONSTANTS && kind == ChangeKind.REMOVED -> R.string.compare_member_removed
    else -> when (kind) {
        ChangeKind.ADDED -> R.string.compare_added
        ChangeKind.MODIFIED -> R.string.compare_modified
        ChangeKind.REMOVED -> R.string.compare_removed
        ChangeKind.OUTSIDE -> R.string.compare_outside
        ChangeKind.UNKNOWN -> R.string.compare_unknown
    }
}

/**
 * 在解包结果中显示快照状态。
 * @param status 快照生成状态。
 * @param failures 无法分析数量。
 */
@Composable
fun SnapshotStatus(status: String, failures: Int) {
    val label = when (status) { "complete" -> R.string.snapshot_generated; "partial" -> R.string.snapshot_partial; "failed" -> R.string.snapshot_failed; else -> return }
    Text(stringResource(label, failures), style = Theme.typography.detail,
        color = if (status == "complete") MiuixTheme.colorScheme.onSurfaceVariantSummary else Theme.status.warning)
}
