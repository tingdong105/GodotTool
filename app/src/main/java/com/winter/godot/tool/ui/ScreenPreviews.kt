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
import com.winter.godot.tool.feature.files.BrowserFile
import com.winter.godot.tool.feature.files.FilePickerScreen
import com.winter.godot.tool.feature.home.HomeScreen
import com.winter.godot.tool.feature.recovery.RecoveryOptions
import com.winter.godot.tool.feature.settings.SettingsScreen
import com.winter.godot.tool.feature.tasks.TasksScreen
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

/**
 * 工作台的四种设备与主题预览。
 */
@ScreenPreviews
@Composable
private fun HomePreview() { GodotToolTheme { ToolScaffold(Screen.HOME) { HomeScreen(exampleTask, false, {}, {}, {}, {}) } } }

/**
 * 横向一级分页的手机与平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun RootTabsPreview() {
    var screen by remember { mutableStateOf(Screen.HOME) }
    GodotToolTheme {
        RootTabs(screen, true, { screen = it }) { page ->
            if (page == Screen.HOME) HomeScreen(exampleTask, false, {}, {}, {}, {})
            else SettingsScreen(ToolSettings(), false, {}, {}, {})
        }
    }
}

/**
 * 任务页的四种设备与主题预览。
 */
@ScreenPreviews
@Composable
private fun TasksPreview() { GodotToolTheme { ToolScaffold(Screen.TASKS) { TasksScreen(listOf(exampleTask.copy(id = "22222222-2222-2222-2222-222222222222", status = TaskStatus.RUNNING,
    stage = "extract", bytes = 25000000, totalBytes = 100000000, files = 620, totalFiles = 2893), exampleTask), "22222222-2222-2222-2222-222222222222", {}, {}, {}, {}) } } }

/**
 * 设置页的四种设备与主题预览。
 */
@ScreenPreviews
@Composable
private fun SettingsPreview() { GodotToolTheme { ToolScaffold(Screen.SETTINGS) { SettingsScreen(ToolSettings(), false, {}, {}, {}) } } }

/**
 * 内置文件选择器的四种设备与主题预览。
 */
@ScreenPreviews
@Composable
private fun FilesPreview() { GodotToolTheme { ToolScaffold(Screen.FILES) {
    FilePickerScreen(ToolUiState(screen = Screen.FILES, browserPath = "/storage/emulated/0/Download",
        browserFiles = listOf(BrowserFile("Projects", "/storage/emulated/0/Download/Projects", true, 0),
            BrowserFile("NodeMotion.apk", "/storage/emulated/0/Download/NodeMotion.apk", false, 100835140))), true, {}, {}, {})
} } }

/**
 * 工作区的四种设备与主题预览。
 */
@ScreenPreviews
@Composable
private fun WorkspacePreview() {
    val entries = listOf(WorkspaceEntry("raw/scripts/Exporter.gdc", 16384), WorkspaceEntry("project/scripts/Exporter.gd", 32768, "scripts/Exporter.gdc"),
        WorkspaceEntry("raw/project.binary", 4096), WorkspaceEntry("project/project.godot", 2048), WorkspaceEntry("recovery-report.txt", 1024))
    GodotToolTheme { ToolScaffold(Screen.WORKSPACE, "NodeMotion 1.0.2") { WorkspaceScreen(ToolUiState(screen = Screen.WORKSPACE, workspaceId = exampleTask.id, entries = entries,
        previewPath = "project/scripts/Exporter.gd", previewText = "extends Node\n\nfunc export_video():\n\tvar encoder = VideoEncoder.new()\n\tencoder.encode_at(frame)"), {}) } }
}

/**
 * 仅有字节码时的报告入口与四种设备预览。
 */
@ScreenPreviews
@Composable
private fun UnrecoveredWorkspacePreview() {
    val entries = listOf(WorkspaceEntry("raw/scripts/Main.gdc", 16384), WorkspaceEntry("recovery-report.txt", 2048))
    GodotToolTheme { ToolScaffold(Screen.WORKSPACE, "NodeMotion") {
        WorkspaceScreen(ToolUiState(screen = Screen.WORKSPACE, workspaceId = exampleTask.id, entries = entries), {})
    } }
}

/**
 * 恢复内容选择的手机与平板明暗预览。
 */
@ScreenPreviews
@Composable
private fun RecoveryOptionsPreview() { GodotToolTheme {
    ToolScaffold(Screen.RECOVERY) {
        RecoveryOptions(RecoverySelection.scriptsOnly(), setOf("/storage/emulated/0/Download/Example.pck"), true, {}, {})
    }
} }

/**
 * 默认恢复范围的四种设备与主题预览。
 */
@ScreenPreviews
@Composable
private fun DefaultsPreview() { GodotToolTheme { ToolScaffold(Screen.DEFAULTS) {
    RecoveryOptions(RecoverySelection(), emptySet(), true, {}, {})
} } }
