package com.winter.godot.tool.feature.tasks

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.winter.godot.tool.R
import com.winter.godot.tool.data.TaskRecord
import com.winter.godot.tool.data.TaskStatus
import com.winter.godot.tool.ui.components.EmptyContent
import com.winter.godot.tool.ui.components.PrimaryButton
import com.winter.godot.tool.ui.components.TaskProgress
import com.winter.godot.tool.ui.components.ToolDialog
import com.winter.godot.tool.ui.components.ToolIcon
import com.winter.godot.tool.ui.components.errorResource
import com.winter.godot.tool.ui.components.stageResource
import com.winter.godot.tool.ui.components.statusResource
import com.winter.godot.tool.ui.theme.Theme
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.FloatingActionButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.util.Date

/**
 * 按任务显示状态和时间，点击记录查看结果、诊断及操作。
 * @param tasks 处理记录。
 * @param active 当前任务标识。
 * @param onDelete 删除已确认的记录。
 * @param selected 已选记录。
 * @param busy 是否正在删除。
 * @param onSelect 切换记录选择。
 */
@Composable
fun TasksScreen(tasks: List<TaskRecord>, active: String?, onDelete: (Set<String>) -> Unit,
    selected: Set<String> = emptySet(), busy: Boolean = false, onSelect: (String) -> Unit = {}) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyPathLabel = stringResource(R.string.copy_path)
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeletion by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val selectable = tasks.filter { it.status != TaskStatus.RUNNING && it.id != active }.map { it.id }.toSet()
    val selection = selected.intersect(selectable)
    val selecting = selection.isNotEmpty()
    val bottomPadding by animateDpAsState(
        if (selecting) FloatingActionButtonDefaults.MinHeight + Theme.spacing.xxl else Theme.spacing.md,
        spring(dampingRatio = 0.9f, stiffness = 300f), label = "tasksBottomPadding")
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Theme.spacing.md, end = Theme.spacing.md,
            top = Theme.spacing.sm, bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(Theme.spacing.sm)) {
            if (tasks.isEmpty()) item(key = "empty") {
                EmptyContent(stringResource(R.string.no_tasks), stringResource(R.string.no_tasks_detail), R.drawable.ic_tasks)
            }
            items(tasks, key = { it.id }) { task ->
                Box(Modifier.animateItem(fadeInSpec = tween(180), placementSpec = spring(dampingRatio = 0.78f, stiffness = 300f),
                    fadeOutSpec = tween(180))) {
                    if (task.status == TaskStatus.RUNNING) {
                        Card {
                            BasicComponent(title = task.name, summary = stringResource(stageResource(task.stage)))
                            TaskProgress(task, Modifier.padding(horizontal = Theme.spacing.lg, vertical = Theme.spacing.md))
                        }
                    } else {
                        TaskHistoryRow(task, task.id in selection, selecting, !busy && task.id in selectable,
                            { detail = task.id }, { onSelect(task.id) })
                    }
                }
            }
        }
        AnimatedVisibility(selecting, modifier = Modifier.align(Alignment.BottomEnd).padding(Theme.spacing.lg),
            enter = slideInVertically(spring(dampingRatio = 0.6f, stiffness = 260f)) { it * 2 } + fadeIn(tween(180)),
            exit = slideOutVertically(spring(dampingRatio = 0.75f, stiffness = 300f)) { it * 2 } + fadeOut(tween(180))) {
            FloatingActionButton(onClick = { if (!busy && selecting) pendingDeletion = ArrayList(selection) },
                modifier = Modifier.semantics { if (busy) disabled() }) {
                if (busy) CircularProgressIndicator(Modifier.size(Theme.sizes.icon))
                else Icon(MiuixIcons.Delete, stringResource(R.string.tasks_delete_selected, selection.size),
                    Modifier.size(Theme.sizes.icon), tint = MiuixTheme.colorScheme.onPrimary)
            }
        }
    }
    tasks.firstOrNull { it.id == detail }?.let { task ->
        val summary = remember(task.summary) { runCatching { JSONObject(task.summary) }.getOrDefault(JSONObject()) }
        val output = summary.optString("outputPath")
        val errorCodes = listOf(task.errorCode, summary.optString("errorCode")).filter { it.isNotBlank() && it != "CANCELED" }.distinct()
        val errors = errorCodes.map(::errorResource).distinct()
        val diagnostic = listOf(summary.optString("diagnostic"), task.errorDetail).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
        val hasConversionFailures = summary.optInt("failedScripts") + summary.optInt("failedConversions") > 0
        val warning = summary.optString("warning")
        val snapshotStatus = summary.optString("snapshotStatus").ifBlank {
            when (warning) { "SNAPSHOT_FAILED" -> "failed"; "SNAPSHOT_PARTIAL" -> "partial"; else -> "" }
        }
        val warningLabel = when (warning) {
            "CONFIG_CONVERSION_FAILED" -> R.string.task_warning_config
            "RECOVERY_UNSUPPORTED" -> R.string.task_warning_unsupported
            "", "SNAPSHOT_FAILED", "SNAPSHOT_PARTIAL" -> null
            "RECOVERY_CANCELED" -> if (task.status == TaskStatus.CANCELED) null else R.string.task_warning_canceled
            else -> if (errors.isNotEmpty() || diagnostic.isNotEmpty() || hasConversionFailures) null else when (warning) {
                "RECOVERY_INTERRUPTED", "NATIVE_RECOVERY_INTERRUPTED" -> R.string.task_warning_interrupted
                "SCRIPT_RECOVERY_FAILED" -> R.string.task_warning_scripts
                "RECOVERY_FINALIZATION_FAILED" -> R.string.task_warning_finalization
                else -> R.string.task_warning_partial
            }
        }
        ToolDialog(task.name, { detail = null }) {
            Text(stringResource(statusResource(task)), style = Theme.typography.title)
            if (hasConversionFailures)
                Text(stringResource(R.string.conversion_failures, summary.optInt("failedScripts"), summary.optInt("failedConversions")))
            warningLabel?.let { Text(stringResource(it)) }
            when (snapshotStatus) {
                "partial" -> Text(stringResource(R.string.snapshot_partial, summary.optInt("snapshotFailures")))
                "failed" -> Text(stringResource(R.string.snapshot_failed))
            }
            errors.forEach { Text(stringResource(it)) }
            if (diagnostic.isNotEmpty()) SelectionContainer { Text(diagnostic, style = Theme.typography.code) }
            if (output.isNotBlank()) SelectionContainer { Text(output, style = Theme.typography.detail) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
                TextButton(copyPathLabel, {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copyPathLabel, output))) }
                }, Modifier.weight(1f), enabled = output.isNotBlank())
                PrimaryButton(stringResource(R.string.close), { detail = null }, Modifier.weight(1f))
            }
        }
    }
    val confirmedIds = pendingDeletion.toSet().intersect(selectable)
    if (confirmedIds.isNotEmpty() && !busy) ToolDialog(stringResource(R.string.tasks_delete_title), { pendingDeletion = arrayListOf() }) {
        Text(stringResource(R.string.tasks_delete_detail, confirmedIds.size), style = Theme.typography.body)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
            TextButton(stringResource(R.string.cancel), { pendingDeletion = arrayListOf() }, Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.delete), {
                pendingDeletion = arrayListOf()
                onDelete(confirmedIds)
            }, Modifier.weight(1f))
        }
    }
}

/**
 * 多选顶栏的全选图标，选满后高亮并支持取消全选。
 * @param allSelected 是否已选满可删除记录。
 * @param enabled 是否允许切换选择。
 * @param onClick 全选或取消全选。
 */
@Composable
fun TasksSelectAllButton(allSelected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(if (allSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        animationSpec = tween(180), label = "tasksSelectAllTint")
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(Theme.sizes.largeIcon).semantics { selected = allSelected }) {
        Icon(MiuixIcons.SelectAll, stringResource(if (allSelected) R.string.tasks_deselect_all else R.string.tasks_select_all),
            Modifier.size(Theme.sizes.icon), tint = if (enabled) tint else MiuixTheme.colorScheme.disabledOnSurface)
    }
}

/**
 * 平滑展开勾选框并响应长按多选的记录条目。
 * @param task 任务摘要。
 * @param checked 是否已选中。
 * @param selecting 是否处于多选模式。
 * @param enabled 是否允许选择和打开。
 * @param onOpen 打开详情。
 * @param onSelect 切换选择。
 */
@Composable
private fun TaskHistoryRow(task: TaskRecord, checked: Boolean, selecting: Boolean, enabled: Boolean,
    onOpen: () -> Unit, onSelect: () -> Unit) {
    val date = remember(task.createdAt) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.createdAt)) }
    val background by animateColorAsState(if (checked) MiuixTheme.colorScheme.primary.copy(alpha = 0.1f).compositeOver(MiuixTheme.colorScheme.surfaceContainer)
        else MiuixTheme.colorScheme.surfaceContainer, animationSpec = tween(180), label = "taskSelectedColor")
    Card(colors = CardDefaults.defaultColors(color = background)) {
        Row(Modifier.fillMaxWidth().combinedClickable(enabled = enabled, role = if (selecting) Role.Checkbox else Role.Button,
            onClick = { if (selecting) onSelect() else onOpen() }, onLongClick = onSelect)
            .semantics { if (selecting) toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
            .padding(Theme.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(selecting, enter = expandHorizontally(spring(dampingRatio = 0.72f, stiffness = 280f)) + fadeIn(tween(180)),
                exit = shrinkHorizontally(spring(dampingRatio = 0.72f, stiffness = 280f)) + fadeOut(tween(150))) {
                Box(Modifier.padding(end = Theme.spacing.md)) {
                    Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off, onClick = onSelect, enabled = enabled)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Theme.spacing.sm)) {
                Text(task.name, style = Theme.typography.body)
                Text(date, style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Text(stringResource(statusResource(task)), Modifier.padding(start = Theme.spacing.md), style = Theme.typography.detail)
            AnimatedVisibility(!selecting, enter = expandHorizontally(spring(dampingRatio = 0.72f, stiffness = 280f)) + fadeIn(tween(180)),
                exit = shrinkHorizontally(spring(dampingRatio = 0.72f, stiffness = 280f)) + fadeOut(tween(150))) {
                ToolIcon(R.drawable.ic_chevron, Modifier.padding(start = Theme.spacing.md))
            }
        }
    }
}
