package com.winter.godot.tool.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.winter.godot.tool.R
import com.winter.godot.tool.feature.compare.CompareUiState
import com.winter.godot.tool.feature.compare.ComparisonRule
import com.winter.godot.tool.feature.compare.RuleGroup
import com.winter.godot.tool.feature.compare.RuleMatch
import com.winter.godot.tool.feature.compare.RuleTarget
import com.winter.godot.tool.feature.compare.sortComparisonRules
import com.winter.godot.tool.ui.components.ConfirmDialog
import com.winter.godot.tool.ui.components.EmptyContent
import com.winter.godot.tool.ui.components.PrimaryButton
import com.winter.godot.tool.ui.theme.Theme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.FloatingActionButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 新建规则仅开放三种文字匹配方式。
private val editableMatches = listOf(RuleMatch.CONTAINS, RuleMatch.PREFIX, RuleMatch.EXACT)

/**
 * 全局对比规则的列表及添加入口。
 * @param state 共享的规则加载、保存状态。
 * @param onSave 新增或替换规则。
 * @param onDelete 删除确认后的规则。
 * @param onRetry 重试读取规则文件。
 * @param onSelect 切换规则勾选状态。
 * @param group 当前管理的规则分组。
 */
@Composable
fun ComparisonRulesScreen(state: CompareUiState, onSave: (ComparisonRule?, ComparisonRule) -> Unit,
    onDelete: (Set<String>) -> Unit, onRetry: () -> Unit, onSelect: (String) -> Unit, group: RuleGroup = RuleGroup.FILTER) {
    val ruleList = state.ruleList(group)
    val rules = remember(state.rules, ruleList.sort, ruleList.reversed, group) {
        sortComparisonRules(state.rules.filter { it.exclude == group.exclude }, ruleList.sort, ruleList.reversed)
    }
    val selecting = ruleList.selectedIds.isNotEmpty()
    val list = rememberLazyListState()
    val editorStates = rememberSaveableStateHolder()
    val editorStateKey = "comparison-rule-editor-${group.name}"
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    var pendingDeletion by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var revision by rememberSaveable { mutableIntStateOf(state.rulesRevision) }
    var previousSort by rememberSaveable { mutableStateOf(ruleList.sort) }
    var previousReversed by rememberSaveable { mutableStateOf(ruleList.reversed) }
    val enabled = !state.rulesLoading && !state.rulesLoadFailed && !state.rulesSaving
    // 恢复时清理已关闭的编辑会话。
    LaunchedEffect(Unit) {
        if (!editorVisible) {
            editingId = null
            editorStates.removeState(editorStateKey)
        }
    }
    LaunchedEffect(ruleList.sort, ruleList.reversed) {
        if (previousSort != ruleList.sort || previousReversed != ruleList.reversed) {
            list.animateScrollToItem(0)
            previousSort = ruleList.sort
            previousReversed = ruleList.reversed
        }
    }
    LaunchedEffect(state.rulesRevision) {
        if (revision != state.rulesRevision) {
            if (state.rulesRevision > revision) {
                editorVisible = false
                pendingDeletion = arrayListOf()
            }
            revision = state.rulesRevision
        }
    }
    Box(Modifier.fillMaxSize()) {
        when {
            state.rulesLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.rulesLoadFailed -> Column(Modifier.align(Alignment.Center).padding(Theme.spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
                Text(stringResource(R.string.compare_rules_load_failed), color = MiuixTheme.colorScheme.error)
                TextButton(stringResource(R.string.retry), onRetry)
            }
            else -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = Theme.spacing.md, end = Theme.spacing.md,
                top = Theme.spacing.md, bottom = FloatingActionButtonDefaults.MinHeight + Theme.spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
                if (state.rulesError != null && editingId == null) item {
                    Text(stringResource(state.rulesError), color = MiuixTheme.colorScheme.error)
                }
                if (rules.isEmpty()) item {
                    EmptyContent(stringResource(if (group == RuleGroup.FILTER) R.string.compare_rules_empty else R.string.compare_focus_rules_empty), icon = R.drawable.ic_compare)
                }
                items(rules, key = { it.id }) { rule ->
                    ComparisonRuleRow(rule, rule.id in ruleList.selectedIds, selecting, enabled,
                        { editingId = rule.id; editorVisible = true }, { onSelect(rule.id) },
                        Modifier.animateItem(placementSpec = spring(dampingRatio = 0.75f, stiffness = 300f)))
                }
            }
        }
        if (!state.rulesLoading && !state.rulesLoadFailed) AnimatedContent(targetState = selecting,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Theme.spacing.lg),
            transitionSpec = {
                (slideInVertically(spring(dampingRatio = 0.6f, stiffness = 260f)) { it * 2 } + fadeIn(tween(180)))
                    .togetherWith(slideOutVertically(spring(dampingRatio = 0.75f, stiffness = 300f)) { it * 2 } + fadeOut(tween(180)))
                    .using(SizeTransform(clip = false))
            }, label = "rulesFab") { deleting ->
            val foreground = if (deleting) MiuixTheme.colorScheme.onError else MiuixTheme.colorScheme.onPrimary
            FloatingActionButton(onClick = {
                if (enabled && deleting == selecting) {
                    if (deleting) pendingDeletion = ArrayList(ruleList.selectedIds)
                    else { editingId = ""; editorVisible = true }
                }
            }, containerColor = if (deleting) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                modifier = Modifier.semantics { if (!enabled || deleting != selecting) disabled() }) {
                if (deleting && state.rulesSaving) CircularProgressIndicator(Modifier.size(Theme.sizes.icon),
                    colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = foreground, backgroundColor = foreground.copy(alpha = 0.2f)))
                else Icon(if (deleting) MiuixIcons.Delete else MiuixIcons.Add,
                    if (deleting) stringResource(R.string.tasks_delete_selected, ruleList.selectedIds.size) else stringResource(R.string.compare_add_rule),
                    Modifier.size(Theme.sizes.icon), tint = foreground)
            }
        }
    }
    val original = rules.firstOrNull { it.id == editingId }
    if (editingId == "" || original != null) editorStates.SaveableStateProvider(editorStateKey) {
        key(editingId) {
            ComparisonRuleEditor(original, editorVisible, state.rulesSaving, state.rulesError, { if (!state.rulesSaving) editorVisible = false },
                { editingId = null; editorStates.removeState(editorStateKey) }, { onSave(original, it) }, group)
        }
    }
    val confirmed = pendingDeletion.toSet().intersect(rules.map { it.id }.toSet())
    if (confirmed.isNotEmpty()) ConfirmDialog(
        title = stringResource(R.string.compare_delete_rule),
        detail = stringResource(if (group == RuleGroup.FILTER) R.string.compare_delete_rules_detail else R.string.compare_delete_focus_rules_detail, confirmed.size),
        onDismiss = { pendingDeletion = arrayListOf() },
        onConfirm = { if (enabled) { pendingDeletion = arrayListOf(); onDelete(confirmed) } },
        confirmText = stringResource(R.string.delete), confirmEnabled = enabled)
}

/**
 * 长按展开勾选框并平滑切换选中底色的规则条目。
 * @param rule 已保存规则。
 * @param checked 是否已勾选。
 * @param selecting 是否处于多选模式。
 * @param enabled 是否允许交互。
 * @param onOpen 打开编辑页。
 * @param onSelect 切换选择。
 * @param modifier 列表补位动画。
 */
@Composable
private fun ComparisonRuleRow(rule: ComparisonRule, checked: Boolean, selecting: Boolean, enabled: Boolean,
    onOpen: () -> Unit, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val background by animateColorAsState(if (checked) MiuixTheme.colorScheme.primary.copy(alpha = 0.1f)
        .compositeOver(MiuixTheme.colorScheme.surfaceContainer) else MiuixTheme.colorScheme.surfaceContainer,
        animationSpec = tween(180), label = "ruleSelectedColor")
    Card(modifier = modifier, colors = CardDefaults.defaultColors(color = background)) {
        Row(Modifier.fillMaxWidth().combinedClickable(enabled = enabled, role = if (selecting) Role.Checkbox else Role.Button,
            onClick = { if (selecting) onSelect() else onOpen() }, onLongClick = onSelect)
            .semantics { if (selecting) toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
            .padding(Theme.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(selecting, enter = expandHorizontally(spring(dampingRatio = 0.65f, stiffness = 300f)) + fadeIn(tween(180)),
                exit = shrinkHorizontally(spring(dampingRatio = 0.72f, stiffness = 300f)) + fadeOut(tween(150))) {
                Box(Modifier.padding(end = Theme.spacing.md)) {
                    Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off, onClick = onSelect, enabled = enabled)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Theme.spacing.xs)) {
                Text(rule.expression, style = Theme.typography.body)
                Text(stringResource(R.string.compare_rule_summary, ruleTargetLabel(rule.target), ruleMatchLabel(rule.match)),
                    style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (rule.note.isNotBlank()) Text(rule.note, style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

/**
 * 使用可恢复的弹窗表单编辑规则。
 * @param original 编辑前的规则，新增时为空。
 * @param show 是否显示弹窗。
 * @param saving 是否正在写入文件。
 * @param error 保存失败的字符串资源。
 * @param onDismiss 关闭表单。
 * @param onDismissFinished 关闭动画结束后释放表单。
 * @param onSave 提交表单。
 * @param group 当前管理的规则分组。
 */
@Composable
internal fun ComparisonRuleEditor(original: ComparisonRule?, show: Boolean, saving: Boolean, error: Int?, onDismiss: () -> Unit,
    onDismissFinished: () -> Unit, onSave: (ComparisonRule) -> Unit, group: RuleGroup = RuleGroup.FILTER) {
    var expression by rememberSaveable { mutableStateOf(original?.expression.orEmpty()) }
    var match by rememberSaveable { mutableStateOf(original?.match ?: RuleMatch.CONTAINS) }
    var target by rememberSaveable { mutableStateOf(original?.target ?: RuleTarget.ALL) }
    var note by rememberSaveable { mutableStateOf(original?.note.orEmpty()) }
    var submitted by remember { mutableStateOf(false) }
    val editable = original == null || original.match in editableMatches
    val interactive = show && !saving && !submitted
    val focus = LocalFocusManager.current
    val dismiss = { if (show && !saving && !submitted) { focus.clearFocus(); onDismiss() } }
    LaunchedEffect(show, saving, error, submitted) {
        if (show && submitted && !saving && error != null) submitted = false
    }
    OverlayDialog(show = show, title = stringResource(if (original == null) R.string.compare_add_rule else R.string.compare_edit_rule),
        maxWidth = Theme.sizes.dialogMax, onDismissRequest = dismiss, onDismissFinished = onDismissFinished) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Theme.spacing.lg)) {
            TextField(value = expression, onValueChange = { expression = it }, label = stringResource(R.string.compare_rule_expression),
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = editable && interactive)
            if (expression.trim().length > 256) Text(stringResource(R.string.compare_rule_too_long), color = MiuixTheme.colorScheme.error)
            RuleDropdownField(stringResource(R.string.compare_match_type), ruleMatchLabel(match), editableMatches.map { ruleMatchLabel(it) },
                editableMatches.indexOf(match), editable && interactive) { match = editableMatches[it] }
            RuleDropdownField(stringResource(R.string.compare_rule_target), ruleTargetLabel(target), RuleTarget.entries.map { ruleTargetLabel(it) },
                target.ordinal, editable && interactive) { target = RuleTarget.entries[it] }
            TextField(value = note, onValueChange = { note = it }, label = stringResource(R.string.compare_rule_note),
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, enabled = editable && interactive)
            if (!editable) Text(stringResource(R.string.compare_rule_legacy), style = Theme.typography.detail)
            error?.let { Text(stringResource(it), color = MiuixTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
                TextButton(stringResource(R.string.cancel), dismiss, Modifier.weight(1f), enabled = interactive)
                PrimaryButton(stringResource(R.string.compare_save_rule), {
                    if (show && !saving && !submitted && editable && expression.trim().length in 1..256) {
                        submitted = true
                        focus.clearFocus()
                        onSave(ComparisonRule(expression, match, group.exclude, target, note))
                    }
                }, Modifier.weight(1f), enabled = editable && interactive && expression.trim().length in 1..256)
            }
        }
    }
}

/**
 * 与输入框样式一致的只读下拉选择字段。
 * @param label 浮动字段名称。
 * @param value 当前选项文字。
 * @param options 可选项。
 * @param selectedIndex 当前选中位置。
 * @param enabled 是否允许打开菜单。
 * @param onSelected 更新选项。
 */
@Composable
private fun RuleDropdownField(label: String, value: String, options: List<String>, selectedIndex: Int,
    enabled: Boolean, onSelected: (Int) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val entry = DropdownEntry(options.mapIndexed { index, option ->
        DropdownItem(text = option, selected = index == selectedIndex, enabled = enabled, onClick = { onSelected(index) })
    })
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        TextField(value = value, onValueChange = {}, label = label, readOnly = true, enabled = enabled, singleLine = true,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {}, trailingIcon = {
                Row(Modifier.padding(end = Theme.spacing.lg)) { DropdownArrowEndAction(MiuixTheme.colorScheme.onSurfaceVariantActions) }
            })
        Box(Modifier.matchParentSize().clickable(enabled = enabled, role = Role.DropdownList,
            onClick = { focus.clearFocus(); expanded = true }).semantics { contentDescription = "$label，$value" })
        OverlayDropdownPopup(entry = entry, show = expanded, onDismiss = { expanded = false }, onDismissFinished = {},
            maxHeight = null, dropdownColors = DropdownDefaults.dropdownColors(), renderInRootScaffold = true)
    }
}

/**
 * 返回匹配类型的本地化名称。
 * @param target 规则的匹配范围。
 * @return 页面标签。
 */
@Composable
private fun ruleTargetLabel(target: RuleTarget): String = stringResource(when (target) {
    RuleTarget.ALL -> R.string.compare_rule_target_all
    RuleTarget.METHOD -> R.string.compare_rule_target_method
    RuleTarget.FIELD -> R.string.compare_rule_target_field
    RuleTarget.PATH -> R.string.compare_rule_target_path
})

/**
 * 返回新旧匹配方式的本地化名称。
 * @param match 已保存或待选择的匹配方式。
 * @return 页面标签。
 */
@Composable
private fun ruleMatchLabel(match: RuleMatch): String = stringResource(when (match) {
    RuleMatch.CONTAINS -> R.string.compare_match_contains
    RuleMatch.PREFIX -> R.string.compare_match_prefix
    RuleMatch.EXACT -> R.string.compare_match_exact
    RuleMatch.SUFFIX -> R.string.compare_match_suffix
    RuleMatch.WILDCARD -> R.string.compare_match_wildcard
    RuleMatch.REGEX -> R.string.compare_match_regex
})
