package com.winter.godot.tool.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.winter.godot.core.model.RecoverySelection
import com.winter.godot.tool.data.TaskRecord
import com.winter.godot.tool.data.TaskStatus
import com.winter.godot.tool.data.ToolSettings
import com.winter.godot.tool.data.WorkspaceEntry
import com.winter.godot.tool.feature.compare.ChangeKind
import com.winter.godot.tool.feature.compare.CompareScreen
import com.winter.godot.tool.feature.compare.CompareUiState
import com.winter.godot.tool.feature.compare.ComparisonRecord
import com.winter.godot.tool.feature.compare.ComparisonResultScreen
import com.winter.godot.tool.feature.compare.ComparisonRow
import com.winter.godot.tool.feature.compare.MethodChange
import com.winter.godot.tool.feature.compare.SnapshotHeader
import com.winter.godot.tool.feature.files.BrowserFile
import com.winter.godot.tool.feature.files.FilePickerScreen
import com.winter.godot.tool.feature.files.FileSortButton
import com.winter.godot.tool.feature.files.sortBrowserFiles
import com.winter.godot.tool.feature.home.HomeScreen
import com.winter.godot.tool.feature.recovery.RecoveryOptions
import com.winter.godot.tool.feature.settings.SettingsScreen
import com.winter.godot.tool.feature.settings.about.AboutScreen
import com.winter.godot.tool.feature.settings.about.AboutUiState
import com.winter.godot.tool.feature.settings.about.LicensesCopyButton
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
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ScreenPreviews

// 仅预览使用的示例任务。
private val exampleTask = TaskRecord("11111111-1111-1111-1111-111111111111", "NodeMotion 1.0.2", status = TaskStatus.SUCCEEDED, stage = "done",
    summary = "{\"resources\":2893,\"scripts\":229,\"engine\":\"4.5.0\"}")

/** 对比页面的手机和平板明暗预览。 */
@ScreenPreviews
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
@Composable
private fun ComparisonResultPreview() { GodotToolTheme {
    val header = SnapshotHeader(name = "Example", version = "1.0", engine = "4.5", catalogComplete = true, complete = true)
    val record = ComparisonRecord("11111111-1111-1111-1111-111111111111", 0, "Example 1.0", "Example 1.1", header,
        header.copy(version = "1.1"), listOf(1, 1, 0, 0, 0), "261125_1")
    ComparisonResultScreen(CompareUiState(record = record, viewingId = record.id, historyLoading = false,
        counts = record.counts, matchedCounts = record.counts, rows = listOf(ComparisonRow("scripts/player.gd", MethodChange(ChangeKind.MODIFIED, "take_damage",
            "func take_damage ( amount : int )", "func take_damage ( amount : int , source = null )", true, true), false))),
        {}, {}, {}, {}, {}, {}, {}, {}, {})
} }

/**
 * 工作台的四种设备与主题预览。
 */
@ScreenPreviews
@Composable
private fun HomePreview() { GodotToolTheme { ToolScaffold(Screen.HOME) { HomeScreen(ToolUiState(), ToolSettings(), exampleTask, false, {}, {}, {}, {}, {}, {}, {}, {}) } } }

/**
 * 横向一级分页的手机与平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun RootTabsPreview() { RootTabsPreviewContent(false, false) }

/** 实色悬浮底栏的手机和平板明暗预览。 */
@ScreenPreviews
@Composable
private fun FloatingTabsPreview() { RootTabsPreviewContent(true, false) }

/** 液态玻璃底栏的手机和平板明暗预览。 */
@ScreenPreviews
@Composable
private fun GlassTabsPreview() { RootTabsPreviewContent(true, true) }

/**
 * 为三种导航模式提供统一预览内容。
 * @param floating 是否使用悬浮底栏。
 * @param glass 是否使用液态玻璃。
 */
@Composable
private fun RootTabsPreviewContent(floating: Boolean, glass: Boolean) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    val settings = ToolSettings(floatingBottomBar = floating, liquidGlass = glass)
    GodotToolTheme {
        RootTabs(screen, true, { screen = it }, floating, glass) { page ->
            if (page == Screen.HOME) HomeScreen(ToolUiState(), ToolSettings(), exampleTask, false, {}, {}, {}, {}, {}, {}, {}, {})
            else if (page == Screen.COMPARE) CompareScreen(CompareUiState(), {}, {}, {}, {}, {}, {}, {})
            else SettingsScreen(settings, false, {}, {})
        }
    }
}

/**
 * 任务页的四种设备与主题预览。
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
@Composable
private fun SettingsPreview() { GodotToolTheme { ToolScaffold(Screen.SETTINGS) { SettingsScreen(ToolSettings(), false, {}, {}) } } }

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
    ToolScaffold(Screen.LICENSES, actions = { LicensesCopyButton(text) }) {
        LicensesScreen(AboutUiState(licenses = listOf(text), licenseText = text), {})
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
