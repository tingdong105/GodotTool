package com.winter.godot.tool.feature.backup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.state.ToggleableState
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.components.ConfirmDialog
import com.winter.godot.tool.ui.components.OperationProgressDialog
import com.winter.godot.tool.ui.components.PrimaryButton
import com.winter.godot.tool.ui.components.ToolDialog
import com.winter.godot.tool.ui.theme.Theme
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 按状态组合范围选择、二次确认、进度及结果弹窗。
 * @param state 备份会话状态。
 * @param onSelect 修改所选范围。
 * @param onReview 进入二次确认。
 * @param onDismiss 关闭范围选择。
 * @param onRevise 取消确认并返回选择。
 * @param onConfirm 执行确认后的操作。
 * @param onCancel 取消当前 IO 任务。
 * @param onFinished 完整进度退出后的回调。
 * @param onNotice 关闭结果提示。
 */
@Composable
fun BackupDialogs(state: BackupUiState, onSelect: (Set<BackupScope>) -> Unit, onReview: () -> Unit,
    onDismiss: () -> Unit, onRevise: () -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit,
    onFinished: (String) -> Unit, onNotice: () -> Unit) {
    if (state.choosing) BackupScopeDialog(state.restoring, state.available, state.selected, onSelect, onDismiss, onReview)
    if (state.confirming) {
        val selected = BackupScope.entries.filter { it in state.selected }.map { stringResource(it.label) }.joinToString("、")
        ConfirmDialog(stringResource(if (state.restoring) R.string.backup_restore_confirm else R.string.backup_export_confirm),
            stringResource(if (state.restoring) R.string.backup_restore_detail else R.string.backup_export_detail, selected),
            onRevise, onConfirm, stringResource(if (state.restoring) R.string.backup_restore else R.string.backup_export))
    }
    if (state.inspecting) ToolDialog(stringResource(R.string.backup_checking), onCancel) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        TextButton(stringResource(R.string.cancel), onCancel, Modifier.fillMaxWidth())
    }
    if (state.running) OperationProgressDialog(state.id, state.fraction, state.finished,
        stringResource(if (state.restoring) R.string.backup_restoring else R.string.backup_exporting),
        stringResource(if (state.restoring) R.string.backup_restore else R.string.backup_export), onCancel, onFinished,
        cancellable = !state.committing, allowFinishDismiss = false,
        detail = if (state.committing) stringResource(R.string.backup_committing) else null)
    state.notice?.let { notice -> ToolDialog(stringResource(R.string.notice), onNotice) {
        Text(stringResource(notice), style = Theme.typography.body)
        if (state.detail.isNotEmpty()) androidx.compose.foundation.text.selection.SelectionContainer {
            Text(state.detail, style = Theme.typography.detail)
        }
        PrimaryButton(stringResource(R.string.confirm), onNotice, Modifier.fillMaxWidth())
    } }
}

/**
 * 全选时折叠分类，取消全选后平滑展开。
 * @param restoring 是否为还原范围。
 * @param available 实际可用的范围。
 * @param selected 当前勾选。
 * @param onSelect 修改勾选。
 * @param onDismiss 关闭选择。
 * @param onContinue 进入二次确认。
 */
@Composable
fun BackupScopeDialog(restoring: Boolean, available: Set<BackupScope>, selected: Set<BackupScope>,
    onSelect: (Set<BackupScope>) -> Unit, onDismiss: () -> Unit, onContinue: () -> Unit) {
    val expanded = selected != available
    ToolDialog(stringResource(if (restoring) R.string.backup_restore_scope else R.string.backup_export_scope), onDismiss) {
        Column {
            val allState = when {
                selected.isEmpty() -> ToggleableState.Off
                selected == available -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            }
            BackupScopeRow(stringResource(R.string.backup_all), allState, available.isNotEmpty(), expanded = expanded) {
                onSelect(if (selected == available) emptySet() else available)
            }
            // 内容的逐帧高度同步驱动弹窗伸缩。
            AnimatedVisibility(visible = expanded,
                enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 400f), expandFrom = Alignment.Top) + fadeIn(tween(180)),
                exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 450f), shrinkTowards = Alignment.Top) + fadeOut(tween(150))) {
                Column {
                    HorizontalDivider(Modifier.padding(horizontal = Theme.spacing.xs))
                    for (scope in BackupScope.entries) {
                        BackupScopeRow(stringResource(scope.label), if (scope in selected) ToggleableState.On else ToggleableState.Off,
                            expanded && scope in available) { onSelect(if (scope in selected) selected - scope else selected + scope) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
            TextButton(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.backup_next), onContinue, Modifier.weight(1f), enabled = selected.isNotEmpty())
        }
    }
}

/**
 * 复选项整行可点，禁用范围保留可见。
 * @param title 范围名称。
 * @param state 勾选状态。
 * @param enabled 是否可用。
 * @param expanded 全部行的展开状态，分类行为空。
 * @param onClick 点击事件。
 */
@Composable
private fun BackupScopeRow(title: String, state: ToggleableState, enabled: Boolean, expanded: Boolean? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().triStateToggleable(state, enabled = enabled, role = Role.Checkbox, onClick = onClick)
        .padding(vertical = Theme.spacing.md, horizontal = Theme.spacing.xs), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
        Box(Modifier.clearAndSetSemantics {}) { Checkbox(state = state, onClick = onClick, enabled = enabled) }
        Text(title, modifier = Modifier.weight(1f), style = Theme.typography.body,
            color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSecondaryVariant)
        if (expanded != null) {
            val rotation by animateFloatAsState(if (expanded) 90f else 0f,
                spring(dampingRatio = 0.85f, stiffness = 400f), label = "backupScopeArrow")
            Icon(MiuixIcons.ChevronForward, null, Modifier.size(Theme.spacing.lg).graphicsLayer { rotationZ = rotation },
                tint = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantActions else MiuixTheme.colorScheme.disabledOnSurface)
        }
    }
}
