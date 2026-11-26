package com.winter.godot.tool.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.winter.godot.core.model.RecoverySelection
import com.winter.godot.tool.R
import com.winter.godot.tool.data.TaskRecord
import com.winter.godot.tool.data.TaskStatus
import com.winter.godot.tool.data.ToolSettings
import com.winter.godot.tool.data.WorkspaceEntry
import com.winter.godot.tool.feature.compare.ChangeKind
import com.winter.godot.tool.feature.compare.CompareScreen
import com.winter.godot.tool.feature.compare.CompareUiState
import com.winter.godot.tool.feature.compare.ComparisonMode
import com.winter.godot.tool.feature.compare.ComparisonRecord
import com.winter.godot.tool.feature.compare.ComparisonResultScreen
import com.winter.godot.tool.feature.compare.ComparisonRow
import com.winter.godot.tool.feature.compare.ComparisonRule
import com.winter.godot.tool.feature.compare.ComparisonRuleListState
import com.winter.godot.tool.feature.compare.MethodChange
import com.winter.godot.tool.feature.compare.RuleGroup
import com.winter.godot.tool.feature.compare.RuleMatch
import com.winter.godot.tool.feature.compare.RuleSort
import com.winter.godot.tool.feature.compare.RuleTarget
import com.winter.godot.tool.feature.compare.SnapshotHeader
import com.winter.godot.tool.feature.files.BrowserFile
import com.winter.godot.tool.feature.files.FilePickerScreen
import com.winter.godot.tool.feature.files.FileSortButton
import com.winter.godot.tool.feature.files.sortBrowserFiles
import com.winter.godot.tool.feature.home.HomeScreen
import com.winter.godot.tool.feature.recovery.RecoveryOptions
import com.winter.godot.tool.feature.settings.ComparisonRuleEditor
import com.winter.godot.tool.feature.settings.ComparisonRulesScreen
import com.winter.godot.tool.feature.settings.ComparisonRulesSortButton
import com.winter.godot.tool.feature.settings.SettingsScreen
import com.winter.godot.tool.feature.settings.about.AboutScreen
import com.winter.godot.tool.feature.settings.about.AboutUiState
import com.winter.godot.tool.feature.settings.about.LicensesScreen
import com.winter.godot.tool.feature.tasks.TasksScreen
import com.winter.godot.tool.feature.tasks.TasksSelectAllButton
import com.winter.godot.tool.feature.workspace.WorkspaceScreen
import com.winter.godot.tool.ui.theme.GodotToolTheme

/**
 * 手机与平板的浅色、暗色预览矩阵。
 * @author Tingdong
 * @date 2026-11-21
 */
@Preview(name = "手机·浅色", widthDp = 393, heightDp = 852, showBackground = true)
@Preview(name = "手机·暗色", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "平板·浅色", widthDp = 1280, heightDp = 800, showBackground = true)
@Preview(name = "平板·暗色", widthDp = 1280, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "平板竖屏·浅色", widthDp = 800, heightDp = 1280, showBackground = true)
@Preview(name = "平板竖屏·暗色", widthDp = 800, heightDp = 1280, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "分屏·浅色", widthDp = 600, heightDp = 800, showBackground = true)
@Preview(name = "分屏·暗色", widthDp = 600, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "大平板竖屏·浅色", widthDp = 960, heightDp = 1280, showBackground = true)
@Preview(name = "大平板竖屏·暗色", widthDp = 960, heightDp = 1280, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ScreenPreviews

// 仅预览使用的示例任务。
private val exampleTask = TaskRecord("11111111-1111-1111-1111-111111111111", "NodeMotion 1.0.2", status = TaskStatus.SUCCEEDED, stage = "done",
    summary = "{\"resources\":2893,\"scripts\":229,\"engine\":\"4.5.0\"}")

/** 对比页面的手机和平板明暗预览。 */
@ScreenPreviews
@Preview(name = "手机横屏·浅色", widthDp = 900, heightDp = 400, showBackground = true)
@Preview(name = "手机横屏·暗色", widthDp = 900, heightDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun ComparePreview() { GodotToolTheme { ToolScaffold(Screen.COMPARE) {
    CompareScreen(CompareUiState(), {}, {}, {}, {}, {}, {}, {})
} } }

/**
 * 对比历史多选与删除 FAB 的手机和平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun ComparisonSelectionPreview() { GodotToolTheme { ToolScaffold(Screen.COMPARE) {
    val header = SnapshotHeader(name = "Example", version = "1.0", catalogComplete = true, complete = true)
    val records = (1..3).map { index -> ComparisonRecord("11111111-1111-1111-1111-11111111111$index", index.toLong(),
        "Example 1.$index", "Example 1.${index + 1}", header, header.copy(version = "1.1"), listOf(1, 2, 3, 0, 0)) }
    CompareScreen(CompareUiState(histories = records, selected = setOf(records.first().id), historyLoading = false), {}, {}, {}, {}, {}, {}, {})
} } }

/**
 * 对比结果的手机和平板明暗预览。
 */
@ScreenPreviews
@Preview(name = "手机横屏·浅色", widthDp = 900, heightDp = 400, showBackground = true)
@Preview(name = "手机横屏·暗色", widthDp = 900, heightDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun ComparisonResultPreview() { ComparisonResultPreviewContent() }

/** 搜索展开状态的手机、平板与分屏预览。 */
@ScreenPreviews
@Composable
private fun ComparisonSearchPreview() { ComparisonResultPreviewContent(searchInitially = true) }

/**
 * 提供可交互的结果搜索与换行预览。
 * @param searchInitially 首次展示时是否展开搜索。
 */
@Composable
private fun ComparisonResultPreviewContent(searchInitially: Boolean = false) { GodotToolTheme {
    val header = SnapshotHeader(name = "Example", version = "1.0", engine = "4.5", catalogComplete = true, complete = true)
    val record = ComparisonRecord("11111111-1111-1111-1111-111111111111", 0, "Example 1.0", "Example 1.1", header,
        header.copy(version = "1.1"), listOf(8, 8, 8, 0, 0), "261125_1")
    var wrap by remember { mutableStateOf(true) }
    var search by remember { mutableStateOf(searchInitially) }
    var query by remember { mutableStateOf("") }
    ComparisonResultScreen(CompareUiState(record = record, viewingId = record.id, historyLoading = false, wrapLines = wrap, searchExpanded = search, query = query,
        counts = record.counts, matchedCounts = record.counts, rows = comparisonPreviewRows()),
        { query = it }, { search = it; if (!it) query = "" }, {}, {}, {}, { wrap = it }, {}, {}, { search = false; query = "" })
} }

/**
 * 生成包含长路径和长参数的三类对比示例。
 * @return 按增改删分组的示例条目。
 */
private fun comparisonPreviewRows(): List<ComparisonRow> = (0 until 24).map { index ->
    val kind = listOf(ChangeKind.ADDED, ChangeKind.MODIFIED, ChangeKind.REMOVED)[index / 8]
    ComparisonRow("addons/example_plugin/gameplay/characters/components/player_controller_$index.gd",
        MethodChange(kind, "update_player_$index",
            if (kind == ChangeKind.ADDED) "" else "func update_player_$index ( delta : float , movement_direction : Vector3 , camera_rotation : Vector3 )",
            if (kind == ChangeKind.REMOVED) "" else "func update_player_$index ( delta : float , movement_direction : Vector3 , camera_rotation : Vector3 , movement_speed : float = 10.0 , allow_air_control : bool = true )",
            kind != ChangeKind.ADDED, kind != ChangeKind.REMOVED), index % 4 == 0)
}

/**
 * 工作台的手机、平板与分屏明暗预览。
 */
@ScreenPreviews
@Preview(name = "手机横屏·浅色", widthDp = 900, heightDp = 400, showBackground = true)
@Preview(name = "手机横屏·暗色", widthDp = 900, heightDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun HomePreview() { GodotToolTheme { ToolScaffold(Screen.HOME) { HomeScreen(ToolUiState(), ToolSettings(), exampleTask, false, {}, {}, {}, {}, {}, {}, {}, {}) } } }

/**
 * 横向一级分页的手机与平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun RootTabsPreview() { RootTabsPreviewContent(false, false) }

/** 导航 600dp 与侧栏后内容 840dp 的断点预览。 */
@Preview(name = "窗口599·浅色", widthDp = 599, heightDp = 800, showBackground = true)
@Preview(name = "窗口599·暗色", widthDp = 599, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "窗口600·浅色", widthDp = 600, heightDp = 800, showBackground = true)
@Preview(name = "窗口600·暗色", widthDp = 600, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "窗口919·浅色", widthDp = 919, heightDp = 800, showBackground = true)
@Preview(name = "窗口919·暗色", widthDp = 919, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "窗口920·浅色", widthDp = 920, heightDp = 800, showBackground = true)
@Preview(name = "窗口920·暗色", widthDp = 920, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "手机横屏·浅色", widthDp = 900, heightDp = 400, showBackground = true)
@Preview(name = "手机横屏·暗色", widthDp = 900, heightDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun AdaptiveNavigationPreview() { RootTabsPreviewContent(false, false, initialScreen = Screen.COMPARE) }

/** 超宽结果页验证不受居中内容上限影响。 */
@Preview(name = "超宽结果·浅色", widthDp = 1600, heightDp = 900, showBackground = true)
@Preview(name = "超宽结果·暗色", widthDp = 1600, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun WideComparisonResultPreview() { ComparisonResultPreview() }

/** 实色悬浮底栏的手机和平板明暗预览。 */
@ScreenPreviews
@Composable
private fun FloatingTabsPreview() { RootTabsPreviewContent(true, false, processing = true) }

/** 液态玻璃底栏的手机和平板明暗预览。 */
@ScreenPreviews
@Composable
private fun GlassTabsPreview() { RootTabsPreviewContent(true, true, processing = true) }

/** 对比页的悬浮导航与方向回归预览。 */
@ScreenPreviews
@Composable
private fun FloatingCompareTabsPreview() { RootTabsPreviewContent(true, false, initialScreen = Screen.COMPARE) }

/** 设置页的悬浮导航与方向回归预览。 */
@ScreenPreviews
@Composable
private fun FloatingSettingsTabsPreview() { RootTabsPreviewContent(true, false, initialScreen = Screen.SETTINGS) }

/**
 * 为三种导航模式提供统一预览内容。
 * @param floating 是否使用悬浮底栏。
 * @param glass 是否使用液态玻璃。
 * @param processing 是否展示处理步骤与右侧日志。
 * @param initialScreen 初始导航页面。
 */
@Composable
private fun RootTabsPreviewContent(floating: Boolean, glass: Boolean, processing: Boolean = false, initialScreen: Screen = Screen.HOME) {
    var screen by remember { mutableStateOf(initialScreen) }
    val settings = ToolSettings(floatingBottomBar = floating, liquidGlass = glass)
    GodotToolTheme {
        RootTabs(screen, true, { screen = it }, floating, glass) { page ->
            if (page == Screen.HOME) HomeScreen(ToolUiState(homeStep = if (processing) 3 else 1), ToolSettings(),
                if (processing) exampleTask.copy(status = TaskStatus.RUNNING, stage = "recover", overallProgress = 0.62f) else exampleTask,
                processing, {}, {}, {}, {}, {}, {}, {}, {}, logs = if (processing) (1..30).map {
                    com.winter.godot.tool.data.TaskLogLine("recover", "正在恢复项目文件 scripts/components/player_controller_$it.gd")
                } else emptyList())
            else if (page == Screen.COMPARE) CompareScreen(CompareUiState(), {}, {}, {}, {}, {}, {}, {})
            else SettingsScreen(settings, false, {}, {})
        }
    }
}

/**
 * 任务页的手机、平板与分屏明暗预览。
 */
@ScreenPreviews
@Composable
private fun TasksPreview() { GodotToolTheme { ToolScaffold(Screen.TASKS) { TasksScreen(listOf(exampleTask.copy(id = "22222222-2222-2222-2222-222222222222", status = TaskStatus.RUNNING,
    stage = "extract", bytes = 25000000, totalBytes = 100000000, files = 620, totalFiles = 2893), exampleTask), "22222222-2222-2222-2222-222222222222", {}) } } }

/**
 * 处理记录多选与删除 FAB 的手机和平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun TasksSelectionPreview() { GodotToolTheme {
    val records = (1..3).map { exampleTask.copy(id = "11111111-1111-1111-1111-11111111111$it", name = "NodeMotion 1.0.$it") }
    var selected by remember { mutableStateOf(setOf(records.first().id)) }
    ToolScaffold(Screen.TASKS, titleOverride = if (selected.isNotEmpty()) "已选 ${selected.size} 项" else null, actions = {
        if (selected.isNotEmpty()) TasksSelectAllButton(selected.size == records.size, true) {
            selected = if (selected.size == records.size) emptySet() else records.map { it.id }.toSet()
        }
    }) {
        TasksScreen(records, null, {}, selected = selected,
            onSelect = { id -> selected = if (id in selected) selected - id else selected + id })
    }
} }

/**
 * 设置页的四种设备与主题预览。
 */
@ScreenPreviews
@Preview(name = "手机横屏·浅色", widthDp = 900, heightDp = 400, showBackground = true)
@Preview(name = "手机横屏·暗色", widthDp = 900, heightDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun SettingsPreview() { GodotToolTheme { ToolScaffold(Screen.SETTINGS) { SettingsScreen(ToolSettings(), false, {}, {}) } } }

/**
 * 过滤规则空列表的手机和平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun ComparisonRulesEmptyPreview() { GodotToolTheme { ToolScaffold(Screen.COMPARISON_RULES,
    actions = { ComparisonRulesSortButton(RuleSort.TIME, true, true) { _, _ -> } }) {
    ComparisonRulesScreen(CompareUiState(rulesLoading = false), { _, _ -> }, {}, {}, {})
} } }

// 预览规则使用稳定身份与创建时间。
private val exampleComparisonRules = listOf(
    ComparisonRule("aliyun", exclude = true, note = "忽略云服务相关结果", id = "11111111-1111-1111-1111-111111111111", createdAt = 1),
    ComparisonRule("addons/", RuleMatch.PREFIX, true, RuleTarget.PATH, id = "22222222-2222-2222-2222-222222222222", createdAt = 2),
    ComparisonRule("login", RuleMatch.EXACT, true, RuleTarget.METHOD, id = "33333333-3333-3333-3333-333333333333", createdAt = 3))

/**
 * 过滤规则列表的手机和平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun ComparisonRulesPreview() { GodotToolTheme { ToolScaffold(Screen.COMPARISON_RULES,
    actions = { ComparisonRulesSortButton(RuleSort.TIME, true, true) { _, _ -> } }) {
    ComparisonRulesScreen(CompareUiState(rulesLoading = false, rules = exampleComparisonRules), { _, _ -> }, {}, {}, {})
} } }

/**
 * 过滤规则多选与删除 FAB 的手机和平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun ComparisonRulesSelectionPreview() { GodotToolTheme { ToolScaffold(Screen.COMPARISON_RULES,
    titleOverride = stringResource(R.string.tasks_selected, 1), actions = {
        TasksSelectAllButton(false, true, {})
        ComparisonRulesSortButton(RuleSort.TIME, true, true) { _, _ -> }
    }) {
    ComparisonRulesScreen(CompareUiState(rulesLoading = false, rules = exampleComparisonRules,
        filterRuleList = ComparisonRuleListState(selectedIds = setOf(exampleComparisonRules.first().id))), { _, _ -> }, {}, {}, {})
} } }

/**
 * 过滤规则编辑表单的手机和平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun ComparisonRuleEditorPreview() { GodotToolTheme { ToolScaffold(Screen.COMPARISON_RULES) {
    ComparisonRuleEditor(exampleComparisonRules.first(), true, false, null, {}, {}, {})
} } }

/**
 * 关于页的手机与平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun AboutPreview() { GodotToolTheme {
    AboutScreen(AboutUiState(versionName = "Beta-1.0.0", versionCode = 26112500), true, {}, {}, {})
} }

/**
 * 开源许可阅读页的手机与平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun LicensesPreview() { GodotToolTheme {
    val text = "miuix 0.9.4\nhttps://github.com/compose-miuix-ui/miuix\n\nApache License\nVersion 2.0, January 2004"
    ToolScaffold(Screen.LICENSES) {
        LicensesScreen(AboutUiState(licenses = listOf(text)), {})
    }
} }

/**
 * 内置文件选择器的四种设备与主题预览。
 */
@ScreenPreviews
@Composable
private fun FilesPreview() {
    var state by remember { mutableStateOf(ToolUiState(screen = Screen.FILES, browserPath = "/storage/emulated/0/Download",
        browserFiles = listOf(BrowserFile("Projects", "/storage/emulated/0/Download/Projects", true, 0),
            BrowserFile("NodeMotion.apk", "/storage/emulated/0/Download/NodeMotion.apk", false, 100835140, 1795507200000)))) }
    GodotToolTheme {
        ToolScaffold(Screen.FILES, actions = {
            FileSortButton(state.browserSort, state.browserReversed, state.browserDirectoryOnly, { order, reversed, directoryOnly ->
                state = state.copy(browserSort = order, browserReversed = reversed, browserDirectoryOnly = directoryOnly,
                    browserFiles = sortBrowserFiles(state.browserFiles, order, reversed))
            })
        }) { FilePickerScreen(state, true, {}, { state = state.copy(chosenFiles = setOf(it)) }, {}) }
    }
}

/**
 * 工作区的四种设备与主题预览。
 */
@ScreenPreviews
@Composable
private fun WorkspacePreview() {
    val entries = listOf(WorkspaceEntry("original/scripts/Exporter.gdc", 16384), WorkspaceEntry("unpacked/scripts/Exporter.gd", 32768, "scripts/Exporter.gdc"),
        WorkspaceEntry("original/project.binary", 4096), WorkspaceEntry("unpacked/project.godot", 2048), WorkspaceEntry("report.md", 1024))
    GodotToolTheme { ToolScaffold(Screen.WORKSPACE, "NodeMotion 1.0.2") { WorkspaceScreen(ToolUiState(screen = Screen.WORKSPACE, workspaceId = exampleTask.id, entries = entries,
        previewPath = "unpacked/scripts/Exporter.gd", previewText = "extends Node\n\nfunc export_video():\n\tvar encoder = VideoEncoder.new()\n\tencoder.encode_at(frame)"), {}) } }
}

/**
 * 仅有字节码时的报告入口与四种设备预览。
 */
@ScreenPreviews
@Composable
private fun UnrecoveredWorkspacePreview() {
    val entries = listOf(WorkspaceEntry("original/scripts/Main.gdc", 16384), WorkspaceEntry("report.md", 2048))
    GodotToolTheme { ToolScaffold(Screen.WORKSPACE, "NodeMotion") {
        WorkspaceScreen(ToolUiState(screen = Screen.WORKSPACE, workspaceId = exampleTask.id, entries = entries), {})
    } }
}

/**
 * 级联解包选项的手机与平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun RecoveryOptionsPreview() { GodotToolTheme {
    ToolScaffold(Screen.SETTINGS) {
        RecoveryOptions(RecoverySelection.scriptsOnly(), {})
    }
} }

/**
 * 首页配置步骤的手机与平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun HomeConfigurationPreview() { GodotToolTheme { ToolScaffold(Screen.HOME) {
    HomeScreen(ToolUiState(inputFiles = setOf("/example/Project.apk"), homeStep = 2), ToolSettings(), null, false,
        {}, {}, {}, {}, {}, {}, {}, {})
} } }

/**
 * 首页处理状态的手机与平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun HomeProcessingPreview() { GodotToolTheme { ToolScaffold(Screen.HOME) {
    HomeScreen(ToolUiState(homeStep = 3), ToolSettings(), exampleTask.copy(status = TaskStatus.RUNNING, stage = "recover", overallProgress = 0.62f, selection = RecoverySelection.scriptsOnly()), true,
        {}, {}, {}, {}, {}, {}, {}, {}, listOf(
            com.winter.godot.tool.data.TaskLogLine("import", "正在读取文件"),
            com.winter.godot.tool.data.TaskLogLine("mode", "切换至CNM模式", com.winter.godot.tool.data.TaskLogLevel.ACCENT),
            com.winter.godot.tool.data.TaskLogLine("recover", "正在恢复项目文件")))
} } }

/**
 * 首页处理完成后的手机与平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun HomeCompletedPreview() { GodotToolTheme { ToolScaffold(Screen.HOME) {
    HomeScreen(ToolUiState(homeStep = 3), ToolSettings(), exampleTask.copy(overallProgress = 1f, selection = RecoverySelection.scriptsOnly()), false,
        {}, {}, {}, {}, {}, {}, {}, {}, listOf(com.winter.godot.tool.data.TaskLogLine("done", "处理完成，结果已保存", com.winter.godot.tool.data.TaskLogLevel.SUCCESS)))
} } }

/**
 * 首页单个文件行的手机和平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun HomeSelectedFilesPreview() { GodotToolTheme { ToolScaffold(Screen.HOME) {
    HomeScreen(ToolUiState(inputFiles = setOf("/example/Project.apk")), ToolSettings(), null, false,
        {}, {}, {}, {}, {}, {}, {}, {})
} } }

/**
 * 关心规则空列表的手机和平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun ComparisonFocusRulesEmptyPreview() { GodotToolTheme { ToolScaffold(Screen.COMPARISON_FOCUS_RULES,
    actions = { ComparisonRulesSortButton(RuleSort.TIME, true, true) { _, _ -> } }) {
    ComparisonRulesScreen(CompareUiState(rulesLoading = false), { _, _ -> }, {}, {}, {}, RuleGroup.FOCUS)
} } }

// 关心规则预览覆盖名称与路径匹配。
private val exampleFocusRules = listOf(
    exampleComparisonRules[0].copy(expression = "player", exclude = false, note = "关心玩家相关变化"),
    exampleComparisonRules[1].copy(expression = "scripts/battle/", exclude = false),
    exampleComparisonRules[2].copy(expression = "login", exclude = false))

/**
 * 关心规则列表的手机和平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun ComparisonFocusRulesPreview() { GodotToolTheme { ToolScaffold(Screen.COMPARISON_FOCUS_RULES,
    actions = { ComparisonRulesSortButton(RuleSort.TIME, true, true) { _, _ -> } }) {
    ComparisonRulesScreen(CompareUiState(rulesLoading = false, rules = exampleFocusRules), { _, _ -> }, {}, {}, {}, RuleGroup.FOCUS)
} } }

/**
 * 关心规则多选的手机和平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun ComparisonFocusRulesSelectionPreview() { GodotToolTheme { ToolScaffold(Screen.COMPARISON_FOCUS_RULES,
    titleOverride = stringResource(R.string.tasks_selected, 1), actions = {
        TasksSelectAllButton(false, true, {})
        ComparisonRulesSortButton(RuleSort.TIME, true, true) { _, _ -> }
    }) {
    ComparisonRulesScreen(CompareUiState(rulesLoading = false, rules = exampleFocusRules,
        focusRuleList = ComparisonRuleListState(selectedIds = setOf(exampleFocusRules.first().id))),
        { _, _ -> }, {}, {}, {}, RuleGroup.FOCUS)
} } }

/**
 * 关心规则编辑的手机和平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun ComparisonFocusRuleEditorPreview() { GodotToolTheme { ToolScaffold(Screen.COMPARISON_FOCUS_RULES) {
    ComparisonRuleEditor(exampleFocusRules.first(), true, false, null, {}, {}, {}, RuleGroup.FOCUS)
} } }

/**
 * 关心模式的字段标记预览。
 */
@ScreenPreviews
@Composable
private fun ComparisonFocusedResultPreview() { GodotToolTheme {
    val header = SnapshotHeader(name = "Example", version = "1.0", catalogComplete = true, complete = true, membersIndexed = true)
    val record = ComparisonRecord("11111111-1111-1111-1111-111111111111", 0, "Example 1.0", "Example 1.1", header,
        header.copy(version = "1.1"), listOf(0, 1, 0, 0, 0), "261125_1", memberCounts = listOf(0, 1, 0, 0, 0))
    ComparisonResultScreen(CompareUiState(record = record, viewingId = record.id, rulesLoading = false, focusOnly = true,
        viewMode = ComparisonMode.CONSTANTS, counts = record.memberCounts, matchedCounts = record.memberCounts,
        rows = listOf(ComparisonRow("scripts/player.gd", MethodChange(ChangeKind.MODIFIED, "health", "var health = 100", "var health = 200",
            mode = ComparisonMode.CONSTANTS), true))), {}, {}, {}, {}, {}, {}, {}, {}, {})
} }
