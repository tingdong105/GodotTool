package com.winter.godot.tool.feature.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.winter.godot.tool.R
import com.winter.godot.tool.data.WorkspaceEntry
import com.winter.godot.tool.ui.ToolDevice
import com.winter.godot.tool.ui.ToolUiState
import com.winter.godot.tool.ui.components.EmptyContent
import com.winter.godot.tool.ui.components.FileSearch
import com.winter.godot.tool.ui.components.LoadingContent
import com.winter.godot.tool.ui.components.PrimaryButton
import com.winter.godot.tool.ui.components.SectionTitle
import com.winter.godot.tool.ui.components.ToolDialog
import com.winter.godot.tool.ui.components.ToolIcon
import com.winter.godot.tool.ui.components.fileSize
import com.winter.godot.tool.ui.theme.Theme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 自适应资源目录与预览双栏。
 * @param state 工作区状态。
 * @param onPreview 文件打开事件。
 * @param active 仅当前页面接收目录返回事件。
 */
@Composable
fun WorkspaceScreen(state: ToolUiState, onPreview: (WorkspaceEntry) -> Unit, active: Boolean = true) {
    var search by rememberSaveable(state.workspaceId) { mutableStateOf("") }
    var category by rememberSaveable(state.workspaceId) { mutableStateOf("all") }
    var prefix by rememberSaveable(state.workspaceId) { mutableStateOf("") }
    var sourceType by rememberSaveable(state.workspaceId) { mutableStateOf("auto") }
    val projectRoot = remember(state.entries) { when {
        state.entries.any { it.path.startsWith("unpacked/") } -> "unpacked/"
        state.entries.any { it.path.startsWith("project/") } -> "project/"
        state.entries.any { it.path.startsWith("recovered/") } -> "recovered/"
        else -> ""
    } }
    val originalRoot = remember(state.entries) { when {
        state.entries.any { it.path.startsWith("original/") } -> "original/"
        state.entries.any { it.path.startsWith("raw/") } -> "raw/"
        else -> ""
    } }
    val sources = listOfNotNull("project".takeIf { projectRoot.isNotEmpty() }, "raw".takeIf { originalRoot.isNotEmpty() }, "all")
    val source = sourceType.takeIf { it in sources } ?: sources.first()
    val base = when (source) { "project" -> projectRoot; "raw" -> originalRoot; else -> "" }
    val report = remember(state.entries) { state.entries.firstOrNull { it.path == "report.md" }
        ?: state.entries.firstOrNull { it.path == "report.txt" }
        ?: state.entries.firstOrNull { it.path in setOf("reports/recovery-report.txt", "recovery-report.txt") }
        ?: state.entries.firstOrNull { it.path in setOf("reports/result.json", "result.json") } }
    val compiledCount = remember(state.entries) { state.entries.count { it.extension in setOf("gdc", "gde") } }
    val sourceCount = remember(state.entries) { state.entries.count { it.extension == "gd" } }
    val filtered = remember(state.entries, search, category, base) { state.entries.filter { it.path.startsWith(base) && (category == "all" || it.category == category) && (search.isEmpty() || it.path.contains(search, true)) } }
    val rows = remember(filtered, prefix, search, base) { browserRows(filtered, base + prefix, search.isNotEmpty()) }
    val tablet = ToolDevice.isPad
    val browserList = rememberSaveable(state.workspaceId, base, prefix, search, category, saver = LazyListState.Saver) { LazyListState() }
    val previewList = rememberSaveable(state.workspaceId, state.previewPath, saver = LazyListState.Saver) { LazyListState() }
    if (state.loading) { LoadingContent(); return }
    var filtering by rememberSaveable { mutableStateOf(false) }
    val categories = listOf("all" to R.string.all_files, "scripts" to R.string.scripts, "scenes" to R.string.scenes, "images" to R.string.images)
    val tabletLandscape = tablet && ToolDevice.isLandscape
    BoxWithConstraints(Modifier.fillMaxSize().then(if (tablet) Modifier else Modifier.padding(horizontal = Theme.spacing.md))) {
        val wide = (!tablet || tabletLandscape) && maxWidth >= Theme.sizes.tabletBreakpoint
        BackHandler(enabled = active && prefix.isNotEmpty() && state.previewPath == null && !filtering) {
            prefix = prefix.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        }
        Row(Modifier.fillMaxSize().then(if (tablet) Modifier.padding(horizontal = Theme.spacing.md) else Modifier), horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
            if (wide || state.previewPath == null) Column((if (wide) Modifier.width(Theme.sizes.browserWidth) else Modifier.weight(1f)).then(if (tablet) Modifier.fillMaxHeight() else Modifier)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FileSearch(search, stringResource(R.string.search_files), { search = it.take(512) }, Modifier.weight(1f))
                    IconButton(onClick = { filtering = true }) {
                        ToolIcon(R.drawable.ic_settings, description = stringResource(R.string.filter_files))
                    }
                }
                val sourceName = stringResource(when (source) { "project" -> R.string.project_files; "raw" -> R.string.raw_files; else -> R.string.all_files })
                val categoryLabel = stringResource(categories.first { it.first == category }.second)
                SectionTitle(listOf(sourceName, prefix.trimEnd('/'), categoryLabel.takeIf { category != "all" }.orEmpty())
                    .filter { it.isNotEmpty() }.joinToString(" / "))
                if (compiledCount > 0 && sourceCount == 0)
                    BasicComponent(title = stringResource(R.string.script_recovery_incomplete), summary = stringResource(R.string.bytecode_only, compiledCount))
                if (rows.isEmpty()) EmptyContent(stringResource(R.string.no_results))
                else Card(Modifier.weight(1f, fill = false)) {
                    key(base, prefix, search, category) {
                        LazyColumn(state = browserList) {
                            items(rows, key = { it.path }) { row ->
                                val entry = row.entry
                                if (entry == null) ArrowPreference(title = row.name, endActions = { Text(row.count.toString(), style = Theme.typography.number) },
                                    startAction = { ToolIcon(R.drawable.ic_folder, Modifier.padding(end = Theme.spacing.md)) },
                                    onClick = { prefix = row.path.removePrefix(base); search = "" })
                                else BasicComponent(title = row.name, summary = fileSize(entry.size),
                                    startAction = { ToolIcon(when (entry.category) { "scripts" -> R.drawable.ic_code; "images" -> R.drawable.ic_image; else -> R.drawable.ic_file },
                                        Modifier.padding(end = Theme.spacing.md)) }, onClick = { onPreview(entry) })
                            }
                        }
                    }
                }
                if (report != null) ArrowPreference(title = stringResource(R.string.view_report), onClick = { onPreview(report) })
            }
            if (wide || state.previewPath != null) Card(Modifier.weight(1f).fillMaxHeight()) { FilePreview(state, previewList) }
        }
    }
    if (filtering) ToolDialog(stringResource(R.string.filter_files), { filtering = false }) {
        Text(stringResource(R.string.output_contents), style = Theme.typography.detail)
        TabRow(tabs = sources.map { stringResource(when (it) { "project" -> R.string.project_files; "raw" -> R.string.raw_files; else -> R.string.all_files }) },
            selectedTabIndex = sources.indexOf(source), minWidth = Theme.spacing.xl,
            onTabSelected = { sourceType = sources[it]; prefix = ""; search = "" })
        Text(stringResource(R.string.file_category), style = Theme.typography.detail)
        TabRow(tabs = categories.map { stringResource(it.second) }, selectedTabIndex = categories.indexOfFirst { it.first == category },
            minWidth = Theme.spacing.xl, onTabSelected = { category = categories[it].first; prefix = "" })
        PrimaryButton(stringResource(R.string.confirm), { filtering = false }, Modifier.fillMaxWidth())
    }
}

/**
 * 展示受限大小的图片、文本或二进制预览。
 * @param state 已加载的预览状态。
 * @param list 跨窗口布局保留的文本阅读位置。
 */
@Composable
private fun FilePreview(state: ToolUiState, list: LazyListState) {
    if (state.previewPath == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyContent(stringResource(R.string.preview_empty), icon = R.drawable.ic_file) }; return }
    if (state.previewLoading) { LoadingContent(); return }
    val entry = state.entries.firstOrNull { it.path == state.previewPath }
    Column(Modifier.fillMaxSize().padding(Theme.spacing.lg), verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
        Text(state.previewPath, style = Theme.typography.detail, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (!entry?.source.isNullOrEmpty()) Text(stringResource(R.string.original_source, entry!!.source), style = Theme.typography.detail)
        if (state.binary) Text(stringResource(if (entry?.extension in setOf("gdc", "gde")) R.string.preview_bytecode else R.string.preview_binary), style = Theme.typography.detail)
        if (state.truncated) Text(stringResource(R.string.preview_truncated), style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        val image = state.previewImage
        if (image != null) Image(image.asImageBitmap(), state.previewPath, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else {
            val lines = remember(state.previewText) { state.previewText.lines() }
            SelectionContainer(Modifier.fillMaxWidth().weight(1f)) { LazyColumn(Modifier.fillMaxSize(), state = list) { items(lines.size) { index -> Text(lines[index].ifEmpty { " " }, style = Theme.typography.code) } } }
        }
    }
}

/**
 * 文件浏览器中的目录或文件行。
 * @author Tingdong
 * @date 2026-11-21
 */
private data class BrowserRow(val name: String, val path: String, val count: Int = 1, val entry: WorkspaceEntry? = null)

/**
 * 按目录聚合索引，搜索时展开完整路径。
 * @param entries 筛选后的文件。
 * @param prefix 当前目录。
 * @param searching 是否跨目录搜索。
 * @return 目录优先的浏览行。
 */
private fun browserRows(entries: List<WorkspaceEntry>, prefix: String, searching: Boolean): List<BrowserRow> {
    if (searching) return entries.map { BrowserRow(it.path, it.path, entry = it) }
    val directories = linkedMapOf<String, Int>()
    val files = mutableListOf<BrowserRow>()
    for (entry in entries) {
        if (!entry.path.startsWith(prefix)) continue
        val remainder = entry.path.removePrefix(prefix)
        if ('/' in remainder) {
            val name = remainder.substringBefore('/')
            directories[name] = (directories[name] ?: 0) + 1
        } else files += BrowserRow(remainder, entry.path, entry = entry)
    }
    return directories.map { (name, count) -> BrowserRow(name, "$prefix$name/", count) }.sortedBy { it.name } + files.sortedBy { it.name }
}
