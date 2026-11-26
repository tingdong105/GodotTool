package com.winter.godot.tool.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.winter.godot.core.model.RecoverySelection
import com.winter.godot.tool.R
import com.winter.godot.tool.data.TaskLogLine
import com.winter.godot.tool.data.TaskRecord
import com.winter.godot.tool.data.TaskStatus
import com.winter.godot.tool.data.ToolSettings
import com.winter.godot.tool.feature.compare.SnapshotStatus
import com.winter.godot.tool.feature.recovery.RecoveryOptions
import com.winter.godot.tool.feature.recovery.recoveryMode
import com.winter.godot.tool.ui.LocalRootBottomInset
import com.winter.godot.tool.ui.LocalWideContent
import com.winter.godot.tool.ui.ToolUiState
import com.winter.godot.tool.ui.components.PrimaryButton
import com.winter.godot.tool.ui.components.TaskLogs
import com.winter.godot.tool.ui.components.TaskProgress
import com.winter.godot.tool.ui.components.ToolIcon
import com.winter.godot.tool.ui.components.statusResource
import com.winter.godot.tool.ui.rememberMovableToolContent
import com.winter.godot.tool.ui.theme.Theme
import com.winter.godot.tool.ui.toolContentPadding
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 手机保留纵向三步工作流，平板独立适配分栏。
 * @param state 首页草稿及当前步骤。
 * @param settings 默认处理配置。
 * @param latest 当前或最近任务。
 * @param busy 后台仍占用处理核心。
 * @param onStep 切换可编辑步骤。
 * @param onChoose 打开内置文件选择器。
 * @param onFollow 选择是否跟随设置。
 * @param onSelection 编辑本次配置。
 * @param onStart 提交任务。
 * @param onNew 开始新一轮处理。
 * @param onHistory 查看处理记录。
 * @param onCopyPath 复制已生成产物的完整路径。
 * @param logs 关键处理日志。
 * @param outputAvailable 公开产物已通过后台可用性检查。
 */
@Composable
fun HomeScreen(state: ToolUiState, settings: ToolSettings, latest: TaskRecord?, busy: Boolean,
    onStep: (Int) -> Unit, onChoose: () -> Unit, onFollow: (Boolean) -> Unit,
    onSelection: (RecoverySelection) -> Unit, onStart: () -> Unit, onNew: () -> Unit,
    onHistory: () -> Unit, onCopyPath: (String) -> Unit, logs: List<TaskLogLine> = emptyList(), onSnapshot: (Boolean) -> Unit = {},
    outputAvailable: Boolean = false) {
    val wide = LocalWideContent.current
    // 单双栏共用步骤锚点。
    val steps = rememberLazyListState()
    val step = if (busy) 3 else state.homeStep
    val selection = if (state.followsSettings) settings.selection else state.selection
    val running = latest?.status == TaskStatus.RUNNING
    val showingTask = latest != null && (busy || step == 3 && state.inputFiles.isEmpty() && !latest.acknowledged)
    val name = remember(state.inputFiles) { state.inputFiles.singleOrNull()?.substringAfterLast('/') }
    val fileSummary = when {
        showingTask -> latest.name
        name != null -> name
        else -> stringResource(R.string.supported_packages)
    }
    val configSummary = when {
        showingTask -> latest.selection?.let { stringResource(recoveryMode(it)) } ?: stringResource(R.string.original_configuration)
        state.followsSettings -> stringResource(R.string.follow_configuration, stringResource(recoveryMode(selection)))
        else -> stringResource(recoveryMode(selection))
    }
    val configurationMargin = PaddingValues(start = Theme.spacing.md, end = Theme.spacing.md,
        top = BasicComponentDefaults.InsideMargin.calculateTopPadding(), bottom = BasicComponentDefaults.InsideMargin.calculateBottomPadding())
    val fileStep: @Composable () -> Unit = {
        HomeStep(1, stringResource(R.string.select_file), fileSummary, expanded = step == 1,
            completed = step > 1, enabled = !busy, onClick = { onStep(1) }) {
            if (name != null) {
                SelectedFile(name, onChoose)
                PrimaryButton(stringResource(R.string.next_step), { onStep(2) }, Modifier.fillMaxWidth())
            } else {
                Text(stringResource(R.string.supported_packages), style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                PrimaryButton(stringResource(R.string.select_file), onChoose, Modifier.fillMaxWidth(), enabled = !busy, icon = MiuixIcons.Folder)
            }
        }
    }
    val configurationStep: @Composable () -> Unit = {
        HomeStep(2, stringResource(R.string.choose_configuration), configSummary, expanded = step == 2,
            completed = step > 2, enabled = !busy && name != null, onClick = { onStep(2) }) {
            SwitchPreference(state.followsSettings, onFollow, stringResource(R.string.follow_settings),
                summary = if (state.followsSettings) stringResource(recoveryMode(settings.selection)) else null,
                insideMargin = configurationMargin)
            AnimatedVisibility(!state.followsSettings, enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
                exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 450f)) + fadeOut()) {
                Column {
                    RecoveryOptions(selection, onSelection, embedded = true, insideMargin = configurationMargin)
                    SwitchPreference(state.generateSnapshot, onSnapshot, stringResource(R.string.generate_snapshot), insideMargin = configurationMargin)
                }
            }
            PrimaryButton(stringResource(R.string.next_step), { onStep(3) }, Modifier.fillMaxWidth(), enabled = selection.hasContent)
        }
    }
    val processingStep: @Composable () -> Unit = {
        HomeStep(3, stringResource(R.string.start_processing), if (showingTask) stringResource(statusResource(latest))
            else stringResource(if (step == 3) R.string.ready_to_process else R.string.waiting_configuration),
            expanded = step == 3, completed = showingTask && !running && latest.status in setOf(TaskStatus.SUCCEEDED, TaskStatus.PARTIAL),
            enabled = false, onClick = {}) {
            if (showingTask) {
                TaskProgress(latest)
                if (!running) {
                    ResultSummary(latest)
                    val ready = latest.status in setOf(TaskStatus.SUCCEEDED, TaskStatus.PARTIAL)
                    val outputPath = remember(latest.summary) {
                        runCatching { JSONObject(latest.summary).optString("outputPath") }.getOrDefault("")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(stringResource(if (ready) R.string.copy_path else R.string.history),
                            { if (ready) onCopyPath(outputPath) else onHistory() }, Modifier.weight(1f).heightIn(min = Theme.sizes.largeIcon),
                            enabled = !ready || outputAvailable && outputPath.isNotBlank())
                        PrimaryButton(stringResource(R.string.got_it), onNew, Modifier.weight(1f).heightIn(min = Theme.sizes.largeIcon), enabled = !busy, icon = MiuixIcons.Basic.Check)
                    }
                }
            } else {
                if (name != null) SelectedFile(name)
                val unpackContent = when (recoveryMode(selection)) {
                    R.string.full_project_preset -> stringResource(R.string.unpack_full_files)
                    R.string.scripts_only_preset -> stringResource(R.string.unpack_script_files)
                    else -> if (selection.hasContent) stringResource(R.string.unpack_selected_files, listOfNotNull(
                        if (selection.scripts) stringResource(R.string.project_scripts) else null,
                        if (selection.scenes) stringResource(R.string.scene_resources) else null,
                        if (selection.assets) stringResource(R.string.project_assets) else null,
                        if (selection.config) stringResource(R.string.project_configuration) else null,
                        if (selection.plugins) stringResource(R.string.project_plugins) else null,
                    ).joinToString("、")) else null
                }
                val processingContent = listOfNotNull(
                    unpackContent,
                    if (selection.keepRaw) stringResource(R.string.keep_raw) else null,
                    if (if (state.followsSettings) settings.snapshot.generate else state.generateSnapshot)
                        stringResource(R.string.generate_snapshot) else null,
                )
                if (processingContent.isNotEmpty()) Text(stringResource(R.string.processing_contents, processingContent.joinToString(" + ")),
                    style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                PrimaryButton(stringResource(R.string.start_processing), onStart, Modifier.fillMaxWidth(), enabled = !busy && name != null && selection.hasContent, icon = MiuixIcons.Play)
            }
        }
    }
    val logCard = rememberMovableToolContent {
        if (showingTask) {
            Card(Modifier.fillMaxWidth().then(if (wide) Modifier.fillMaxHeight() else Modifier).animateContentSize(spring(dampingRatio = 0.85f, stiffness = 450f)), cornerRadius = Theme.sizes.corner) {
                Column(Modifier.then(if (wide) Modifier.fillMaxHeight() else Modifier).padding(Theme.spacing.lg), verticalArrangement = Arrangement.spacedBy(Theme.spacing.lg)) {
                    Text(stringResource(R.string.processing_logs), style = Theme.typography.title)
                    if (wide) TaskLogs(latest, logs, Modifier.weight(1f), fillHeight = true)
                    else TaskLogs(latest, logs)
                }
            }
        }
    }
    val padding = toolContentPadding(PaddingValues(start = Theme.spacing.md, end = Theme.spacing.md,
        top = Theme.spacing.sm, bottom = Theme.spacing.sm + LocalRootBottomInset.current))
    Row(Modifier.fillMaxSize().then(if (wide) Modifier.padding(horizontal = Theme.spacing.md) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = steps,
            contentPadding = if (wide) PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()) else padding,
            verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
            item(key = "file") { fileStep() }
            item(key = "configuration") { configurationStep() }
            item(key = "processing") { processingStep() }
            if (!wide && showingTask) item(key = "logs") { logCard() }
        }
        if (wide) Box(Modifier.weight(1f).fillMaxHeight().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) { logCard() }
    }
}

/**
 * 带步骤编号及弹性展开的工作流卡片。
 * @param number 步骤序号。
 * @param title 步骤标题。
 * @param summary 收起时保留的选择摘要。
 * @param expanded 是否展开。
 * @param completed 前置工作是否完成。
 * @param enabled 是否允许返回编辑。
 * @param onClick 展开当前步骤。
 * @param content 展开后的操作内容。
 */
@Composable
private fun HomeStep(number: Int, title: String, summary: String, expanded: Boolean, completed: Boolean,
    enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val badge by animateColorAsState(if (expanded || completed) colors.primary else colors.surfaceContainerHigh, label = "stepColor")
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, tween(180, easing = FastOutSlowInEasing), label = "stepArrow")
    val description = stringResource(R.string.step_description, number, title)
    Card(Modifier.fillMaxWidth(), cornerRadius = Theme.sizes.corner) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(Theme.spacing.lg).semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
            Box(Modifier.size(Theme.spacing.xxl).clip(CircleShape).background(badge), contentAlignment = Alignment.Center) {
                StepBadge(number, completed, color = if (expanded || completed) colors.onPrimary else colors.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Theme.spacing.xs)) {
                Text(title, style = Theme.typography.title, color = if (expanded || completed || enabled) colors.onSurface else colors.onSurfaceVariantSummary)
                if (!expanded) Text(summary, style = Theme.typography.detail, color = colors.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (enabled) Icon(MiuixIcons.ChevronForward, null, Modifier.size(Theme.spacing.lg).graphicsLayer { rotationZ = rotation }, tint = colors.onSurfaceVariantActions)
        }
        AnimatedVisibility(expanded, enter = expandVertically(spring(dampingRatio = 0.78f, stiffness = 400f)) + fadeIn(),
            exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 450f)) + fadeOut()) {
            Column(Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.8f, stiffness = 420f))
                .padding(horizontal = Theme.spacing.lg).padding(bottom = Theme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) { content() }
        }
    }
}

/**
 * 展示唯一输入文件与可选的重选入口。
 * @param name 所选文件名称。
 * @param onChoose 可选的重新选择入口。
 */
@Composable
private fun SelectedFile(name: String, onChoose: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.spacing.sm)) {
        ToolIcon(if (name.endsWith(".apk", ignoreCase = true)) R.drawable.ic_android else R.drawable.ic_file)
        Text(name, style = Theme.typography.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onChoose != null) IconButton(onClick = onChoose, modifier = Modifier.size(Theme.sizes.largeIcon)) {
            Icon(MiuixIcons.Refresh, stringResource(R.string.change_file), Modifier.size(Theme.spacing.lg + Theme.spacing.xs))
        }
    }
}

/**
 * 显示恢复统计或可操作的失败说明。
 * @param task 已结束的任务。
 */
@Composable
private fun ResultSummary(task: TaskRecord) {
    val summary = remember(task.summary) { runCatching { JSONObject(task.summary) }.getOrNull() }
    val ready = task.status in setOf(TaskStatus.SUCCEEDED, TaskStatus.PARTIAL)
    Text(if (ready && summary != null) stringResource(R.string.recovery_totals, summary.optInt("resources"), summary.optInt("scripts"))
        else stringResource(com.winter.godot.tool.ui.components.errorResource(task.errorCode)),
        style = Theme.typography.detail, color = if (ready) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.error)
    SnapshotStatus(summary?.optString("snapshotStatus").orEmpty(), summary?.optInt("snapshotFailures") ?: 0)
}
