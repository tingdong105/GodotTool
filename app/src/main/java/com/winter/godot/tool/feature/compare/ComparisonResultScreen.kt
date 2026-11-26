package com.winter.godot.tool.feature.compare

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.ToolDevice
import com.winter.godot.tool.ui.components.EmptyContent
import com.winter.godot.tool.ui.components.ToolDialog
import com.winter.godot.tool.ui.components.ToolIcon
import com.winter.godot.tool.ui.rememberToolBarBackdrop
import com.winter.godot.tool.ui.theme.Theme
import com.winter.godot.tool.ui.toolBarBackground
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.HorizontalScrollBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBarDefaults
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.nav.gesture.PredictiveBackHandler
import top.yukonga.miuix.kmp.overlay.OverlayCascadingListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.ceil

/**
 * 按分类连续排列方法的文本阅读页。
 * @param state 当前记录与已加载结果。
 * @param onSearch 更新搜索关键词。
 * @param onSearchExpanded 展开或收起顶栏搜索。
 * @param onFilter 切换全部、增加、修改或删除。
 * @param onPlugins 切换插件范围。
 * @param onLoadMore 自动追加下一批结果。
 * @param onWrapLines 切换文本自动换行。
 * @param onViewMode 切换方法、字段或关心范围。
 * @param onPublish 重试保存公开报告。
 * @param onBack 返回或关闭搜索。
 * @param active 当前是否为可交互的导航页面。
 */
@OptIn(ExperimentalScrollBarApi::class)
@Composable
fun ComparisonResultScreen(state: CompareUiState, onSearch: (String) -> Unit, onSearchExpanded: (Boolean) -> Unit,
    onFilter: (ChangeKind?) -> Unit, onPlugins: (Boolean) -> Unit, onLoadMore: () -> Unit, onWrapLines: (Boolean) -> Unit,
    onViewMode: (ComparisonMode) -> Unit, onPublish: () -> Unit, onBack: () -> Unit, active: Boolean = true) {
    var scale by rememberSaveable(state.viewingId) { mutableFloatStateOf(1f) }
    val list = rememberLazyListState()
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val selections = remember(state.viewingId) { ResultSelections() }
    val title = state.record?.let(ComparisonText::title) ?: stringResource(R.string.compare_result)
    val tablet = ToolDevice.isPad
    val direction = LocalLayoutDirection.current
    val backdrop = rememberToolBarBackdrop()
    val contentWidth = if (tablet) Modifier.fillMaxWidth() else Modifier.widthIn(max = Theme.sizes.contentMax).fillMaxWidth()
    PredictiveBackHandler(enabled = active && state.searchExpanded, onProgress = { events -> events.collect {} },
        onCommit = { focus.clearFocus(); onSearchExpanded(false) }, onCancel = {})
    LaunchedEffect(state.viewingId, state.query, state.filter, state.includePlugins, state.viewMode, state.focusOnly, state.rulesRevision) {
        selections.clear()
        list.scrollToItem(0)
    }
    LaunchedEffect(state.wrapLines) { selections.clear() }
    LaunchedEffect(state.viewingId, state.rows.size, state.hasMore, state.loadingMore, state.filtering, state.loadMoreFailed, state.viewMode) {
        if (state.hasMore && !state.loadingMore && !state.filtering && !state.loadMoreFailed) {
            snapshotFlow {
                val info = list.layoutInfo
                info.totalItemsCount >= state.rows.size + 1 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 12
            }
                .first { it }
            onLoadMore()
        }
    }
    Scaffold(modifier = Modifier.fillMaxSize().imePadding().dismissTextSelection(selections)
        .then(if (tablet) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)) else Modifier),
        contentWindowInsets = if (tablet) WindowInsets.safeDrawing else WindowInsets.systemBars.union(WindowInsets.displayCutout), topBar = {
        Column(Modifier.fillMaxWidth().toolBarBackground(backdrop), horizontalAlignment = Alignment.CenterHorizontally) {
            SmallTopAppBar(title = title, color = Color.Transparent, modifier = contentWidth, navigationIconPadding = Theme.spacing.xs, actionIconPadding = Theme.spacing.xs,
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(Theme.sizes.largeIcon)) {
                        ToolIcon(R.drawable.ic_back, description = stringResource(R.string.back))
                    }
                }, actions = {
                    IconButton(onClick = { onSearchExpanded(!state.searchExpanded) }, modifier = Modifier.size(Theme.sizes.largeIcon)) {
                        Icon(MiuixIcons.Search, stringResource(if (state.searchExpanded) R.string.compare_close_search else R.string.compare_search), Modifier.size(Theme.sizes.icon))
                    }
                    ResultMenu(state, onPlugins, onWrapLines, onViewMode, onPublish)
                }, bottomContent = {
                    AnimatedVisibility(state.searchExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        InputField(query = state.query, onQueryChange = onSearch, onSearch = { focus.clearFocus() }, expanded = state.searchExpanded,
                            onExpandedChange = { if (it) onSearchExpanded(true) }, label = stringResource(R.string.compare_search),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.spacing.md, vertical = Theme.spacing.sm))
                    }
                })
            Box(contentWidth) { ComparisonCategories(state.filter, onFilter) }
        }
    }) { padding ->
        val outside = if (backdrop == null) padding else PaddingValues(
            start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction), bottom = padding.calculateBottomPadding())
        val readerTop = if (backdrop != null) padding.calculateTopPadding() else 0.dp
        Box(Modifier.fillMaxSize().padding(outside).consumeWindowInsets(padding)
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier), contentAlignment = Alignment.TopCenter) {
            Column(contentWidth.fillMaxSize().pointerInput(state.viewingId) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var scaling = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            scaling = true
                            val zoom = event.calculateZoom()
                            if (zoom.isFinite() && zoom > 0f) scale = (scale * zoom).coerceIn(0.75f, 1.8f)
                        }
                        if (scaling) event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val density = LocalDensity.current
                    val viewportWidth = maxWidth
                    val textViewportWidth = (viewportWidth - Theme.spacing.lg * 2).coerceAtLeast(Theme.spacing.xs)
                    val touchPadding = Theme.spacing.xs
                    val scrollBarColors = ScrollBarDefaults.scrollBarColors(
                        thumbColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    )
                    val horizontal = rememberScrollState()
                    var widestLine by remember(state.viewingId, state.query, state.filter, state.includePlugins, state.viewMode, state.focusOnly, state.rulesRevision, scale) { mutableIntStateOf(0) }
                    val readerWidth = with(density) { ComparisonReaderLayout.width(constraints.maxWidth, constraints.maxHeight, widestLine,
                        (Theme.spacing.lg * 2).roundToPx(), state.wrapLines).toDp() }
                    LaunchedEffect(state.wrapLines, state.viewingId, state.query, state.filter, state.includePlugins, state.viewMode, state.focusOnly, state.rulesRevision) { horizontal.scrollTo(0) }
                    // 整个阅读区共用一个横向滚动容器，最长已显示行决定内容宽度。
                    if (state.opening || state.filtering) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else Box(Modifier.fillMaxSize().then(if (state.wrapLines) Modifier else Modifier.horizontalScroll(horizontal))) {
                        LazyColumn(Modifier.width(readerWidth).fillMaxHeight(), state = list, contentPadding = PaddingValues(start = Theme.spacing.lg, end = Theme.spacing.lg,
                            top = Theme.spacing.md + readerTop, bottom = Theme.spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Theme.spacing.xs)) {
                            state.record?.let { record -> item(key = "summary") {
                                Column(Modifier.width(textViewportWidth), verticalArrangement = Arrangement.spacedBy(Theme.spacing.sm)) {
                                    Text(stringResource(R.string.compare_report_title), style = Theme.typography.title, color = MiuixTheme.colorScheme.primary)
                                    Text(stringResource(R.string.compare_version_change, ComparisonText.title(record)), style = Theme.typography.body)
                                    val unsupported = state.viewMode == ComparisonMode.CONSTANTS && !record.membersAvailable
                                    if (unsupported) Text(stringResource(R.string.compare_members_unsupported), color = Theme.status.warning, style = Theme.typography.detail)
                                    else {
                                        val original = if (state.viewMode == ComparisonMode.CONSTANTS) record.memberCounts else record.counts
                                        for (kind in listOf(ChangeKind.ADDED, ChangeKind.MODIFIED, ChangeKind.REMOVED)) Text(stringResource(
                                            if (state.focusOnly) R.string.compare_focus_count_detail else R.string.compare_count_detail,
                                            stringResource(changeLabel(kind, state.viewMode)), state.counts[kind.ordinal], original[kind.ordinal], original[kind.ordinal] - state.counts[kind.ordinal]),
                                            style = Theme.typography.number)
                                    }
                                    if (!record.before.complete || !record.after.complete || !record.before.catalogComplete || !record.after.catalogComplete ||
                                        record.counts[ChangeKind.UNKNOWN.ordinal] > 0)
                                        Text(stringResource(R.string.compare_partial), color = Theme.status.warning, style = Theme.typography.detail)
                                    if (record.before.engine != record.after.engine || record.before.core != record.after.core)
                                        Text(stringResource(R.string.compare_environment), color = Theme.status.warning, style = Theme.typography.detail)
                                }
                            } }
                            state.error?.let { error -> item(key = "error") { Text(stringResource(error), color = MiuixTheme.colorScheme.error) } }
                            if (state.rows.isEmpty() && !(state.viewMode == ComparisonMode.CONSTANTS && state.record?.membersAvailable == false)) item(key = "empty") {
                                EmptyContent(stringResource(if (state.query.isNotBlank()) R.string.compare_no_matches
                                    else if (state.focusOnly) {
                                        if (state.viewMode == ComparisonMode.CONSTANTS) R.string.compare_no_focused_fields else R.string.compare_no_focused
                                    } else if (state.viewMode == ComparisonMode.CONSTANTS) R.string.compare_no_member_changes else R.string.compare_no_changes), icon = R.drawable.ic_compare)
                            }
                            itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                                Column(verticalArrangement = Arrangement.spacedBy(Theme.spacing.xs)) {
                                    if (index == 0 || state.rows[index - 1].change.kind != row.change.kind) {
                                        HorizontalDivider(Modifier.padding(vertical = Theme.spacing.md))
                                        Text(stringResource(R.string.compare_section_count, stringResource(sectionLabel(row.change.kind, state.viewMode)), state.matchedCounts[row.change.kind.ordinal]),
                                            style = Theme.typography.title, color = sectionColor(row.change.kind), modifier = Modifier.padding(bottom = Theme.spacing.sm))
                                    }
                                    val line = if (row.change.kind == ChangeKind.UNKNOWN) SnapshotReasonText.line(context, row.path, row.change)
                                        else if (row.change.key.isEmpty()) stringResource(R.string.compare_note_line, row.path, stringResource(changeLabel(row.change.kind, state.viewMode)))
                                        else ComparisonText.line(row.path, row.change)
                                    SelectableResultText(ComparisonReaderLayout.preview(line), state.query, scale, state.wrapLines, selections,
                                        ComparisonText.fieldNameRange(row.path, row.change), row.focused) { width ->
                                        if (width > widestLine) widestLine = width
                                    }
                                }
                            }
                            if (state.loadingMore) item(key = "loading-more") {
                                Box(Modifier.width(textViewportWidth).padding(Theme.spacing.md), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            }
                            if (state.loadMoreFailed) item(key = "retry-more") {
                                TextButton(stringResource(R.string.compare_load_more_failed), onLoadMore, Modifier.width(textViewportWidth))
                            }
                        }
                    }
                    if (!state.opening && !state.filtering) {
                        // 扩大透明触控范围，绘制偏移保持滑块原位。
                        if (list.canScrollForward || list.canScrollBackward) VerticalScrollBar(
                            adapter = rememberScrollBarAdapter(list),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().drawWithContent {
                                translate(left = touchPadding.toPx()) { this@drawWithContent.drawContent() }
                            },
                            trackPadding = PaddingValues(top = Theme.spacing.md + readerTop, bottom = Theme.spacing.md),
                            colors = scrollBarColors,
                            endPadding = ScrollBarDefaults.EndPadding + touchPadding,
                        )
                        if (!state.wrapLines && (horizontal.canScrollForward || horizontal.canScrollBackward)) HorizontalScrollBar(
                            adapter = rememberScrollBarAdapter(horizontal),
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().drawWithContent {
                                translate(top = touchPadding.toPx()) { this@drawWithContent.drawContent() }
                            },
                            trackPadding = PaddingValues(horizontal = Theme.spacing.md),
                            colors = scrollBarColors,
                            endPadding = ScrollBarDefaults.EndPadding + touchPadding,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶栏的换行、范围与结果信息菜单。
 * @param state 当前记录。
 * @param onPlugins 切换插件范围。
 * @param onWrapLines 切换自动换行。
 * @param onViewMode 切换查看范围。
 * @param onPublish 重试保存公开报告。
 */
@Composable
private fun ResultMenu(state: CompareUiState, onPlugins: (Boolean) -> Unit, onWrapLines: (Boolean) -> Unit,
    onViewMode: (ComparisonMode) -> Unit, onPublish: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var information by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(Theme.sizes.largeIcon)) {
            Icon(MiuixIcons.More, stringResource(R.string.compare_more), Modifier.size(Theme.sizes.icon))
        }
        val modes = DropdownEntry(listOf(DropdownItem(stringResource(R.string.compare_view_mode), children = listOf(
            DropdownItem(stringResource(R.string.compare_mode_methods), selected = !state.focusOnly && state.viewMode == ComparisonMode.METHODS,
                onClick = { onViewMode(ComparisonMode.METHODS); expanded = false }),
            DropdownItem(stringResource(R.string.compare_mode_constants), selected = !state.focusOnly && state.viewMode == ComparisonMode.CONSTANTS,
                onClick = { onViewMode(ComparisonMode.CONSTANTS); expanded = false }),
            DropdownItem(stringResource(R.string.compare_mode_focus), selected = state.focusOnly,
                onClick = { onViewMode(ComparisonMode.FOCUS); expanded = false }),
        ))))
        val items = mutableListOf(
            DropdownItem(stringResource(R.string.compare_wrap_lines), selected = state.wrapLines, onClick = { onWrapLines(!state.wrapLines); expanded = false }),
            DropdownItem(stringResource(R.string.compare_plugins), selected = state.includePlugins, onClick = { onPlugins(!state.includePlugins); expanded = false }),
            DropdownItem(stringResource(R.string.compare_result_info), onClick = { information = true; expanded = false }),
        )
        if (state.record?.published == false) items += DropdownItem(stringResource(R.string.compare_retry_publish),
            onClick = { if (!state.publishing) onPublish(); expanded = false })
        OverlayCascadingListPopup(show = expanded, entries = listOf(DropdownEntry(items), modes),
            onDismissRequest = { expanded = false }, alignment = PopupPositionProvider.Align.End)
    }
    if (information) ToolDialog(stringResource(R.string.compare_result_info), { information = false }) {
        state.record?.let { record -> SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
            Text("${record.oldName} → ${record.newName}", style = Theme.typography.body)
            Text(if (record.publicName.isNotEmpty()) stringResource(R.string.compare_public_path, record.publicBase, record.publicName) else stringResource(R.string.compare_private_saved), style = Theme.typography.detail)
        } } }
        TextButton(stringResource(R.string.close), { information = false }, Modifier.fillMaxWidth())
    }
}

/**
 * 有边距的分类文字栏，以主题色和短下划线表示选中项。
 * @param selected 当前分类。
 * @param onSelected 更新分类。
 */
@Composable
private fun ComparisonCategories(selected: ChangeKind?, onSelected: (ChangeKind?) -> Unit) {
    val kinds = listOf(null, ChangeKind.ADDED, ChangeKind.MODIFIED, ChangeKind.REMOVED)
    val labels = listOf(R.string.compare_tab_all, R.string.compare_tab_added, R.string.compare_tab_modified, R.string.compare_tab_removed)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Theme.spacing.lg, vertical = Theme.spacing.xs)
        .height(Theme.sizes.largeIcon).clip(RoundedCornerShape(Theme.sizes.innerCorner))) {
        val tabWidth = maxWidth / kinds.size
        val indicatorWidth = Theme.spacing.xl
        val indicatorOffset by animateDpAsState(tabWidth * kinds.indexOf(selected).coerceAtLeast(0) + (tabWidth - indicatorWidth) / 2,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f), label = "comparisonCategoryIndicator")
        Row(Modifier.fillMaxSize().selectableGroup()) {
            kinds.forEachIndexed { index, kind ->
                val active = selected == kind
                val color by animateColorAsState(if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Box(Modifier.weight(1f).fillMaxHeight().selectable(active, interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = MiuixTheme.colorScheme.onSurface), role = Role.Tab, onClick = { onSelected(kind) }),
                    contentAlignment = Alignment.Center) {
                    Text(stringResource(labels[index]), style = Theme.typography.body, color = color,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).offset(x = indicatorOffset).width(indicatorWidth).height(Theme.spacing.xs)
            .clip(RoundedCornerShape(Theme.spacing.xs)).background(MiuixTheme.colorScheme.primary))
    }
}

/**
 * 保存文本选择及其布局，只在其它区域按下时清除已有选择。
 * @author Tingdong
 * @date 2026-11-25
 */
private class ResultSelections {
    var coordinates: LayoutCoordinates? = null
    val entries = mutableMapOf<SelectionState, LayoutCoordinates?>()

    /**
     * 切换阅读条件时关闭已有文字选择。
     */
    fun clear() { entries.keys.toList().forEach { it.clear() } }

    /**
     * 在子项处理新选择前，仅清除触点之外的旧选择。
     * @param position 页面内的按下位置。
     */
    fun dismissOutside(position: Offset) {
        val owner = coordinates?.takeIf { it.isAttached } ?: return
        for ((selection, row) in entries.toMap()) {
            if (selection.selectedTexts.isEmpty() || row?.isAttached != true) continue
            val point = row.localPositionOf(owner, position)
            if (point.x < 0f || point.y < 0f || point.x >= row.size.width || point.y >= row.size.height) selection.clear()
        }
    }
}

/**
 * 在按下阶段取消其它区域的选择，保留双击和长按新建的选择。
 * 使用示例：Modifier.dismissTextSelection(selections)。
 * @param selections 当前已组合文本的选择状态。
 * @return 不消费触摸事件的修饰符。
 */
private fun Modifier.dismissTextSelection(selections: ResultSelections): Modifier = onGloballyPositioned { selections.coordinates = it }.pointerInput(selections) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        selections.dismissOutside(down.position)
    }
}

/**
 * 单条方法的选择容器，向列表报告完整行宽。
 * @param value 方法文本。
 * @param query 搜索词。
 * @param scale 字号倍率。
 * @param wrapLines 是否允许换行。
 * @param selections 页面选择状态集合。
 * @param fieldName 字段名的加粗范围。
 * @param focused 是否命中关心规则。
 * @param onWidth 更新列表中的最长行宽。
 */
@Composable
private fun SelectableResultText(value: String, query: String, scale: Float, wrapLines: Boolean, selections: ResultSelections, fieldName: IntRange?, focused: Boolean, onWidth: (Int) -> Unit) {
    val selection = rememberSelectionState()
    DisposableEffect(selection, selections) {
        selections.entries[selection] = null
        onDispose { selections.entries.remove(selection) }
    }
    SelectionContainer(state = selection, modifier = Modifier.fillMaxWidth().onGloballyPositioned { selections.entries[selection] = it }) {
        ResultText(value, query, scale, wrapLines, fieldName, focused, onWidth)
    }
}

/**
 * 显示完整单条结果，支持选择复制和搜索高亮。
 * @param value 格式化的方法行。
 * @param query 关键词。
 * @param scale 字号缩放。
 * @param wrapLines 是否自动换行。
 * @param fieldName 字段名的加粗范围。
 * @param focused 是否命中关心规则。
 * @param onWidth 报告不换行时的完整文本宽度。
 */
@Composable
private fun ResultText(value: String, query: String, scale: Float, wrapLines: Boolean, fieldName: IntRange?, focused: Boolean, onWidth: (Int) -> Unit) {
    val highlight = MiuixTheme.colorScheme.primary.copy(alpha = 0.22f)
    val focusColor = MiuixTheme.colorScheme.primary
    val text = remember(value, query, highlight, fieldName, focused, focusColor) { buildAnnotatedString {
        append(value)
        if (focused) addStyle(SpanStyle(color = focusColor, fontWeight = FontWeight.SemiBold), 0, value.length)
        if (fieldName != null && fieldName.first < value.length) {
            addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), fieldName.first, minOf(fieldName.last + 1, value.length))
        }
        if (query.isNotBlank()) {
            var from = 0
            while (from < value.length) {
                val index = value.indexOf(query, from, ignoreCase = true)
                if (index < 0) break
                addStyle(SpanStyle(background = highlight), index, index + query.length)
                from = index + query.length
            }
        }
    } }
    val style = Theme.typography.code
    val height = if (style.lineHeight == TextUnit.Unspecified) style.fontSize * 1.5f else style.lineHeight
    Text(text, style = style.copy(fontSize = style.fontSize * scale, lineHeight = height * scale),
        softWrap = wrapLines, maxLines = if (wrapLines) Int.MAX_VALUE else 1,
        onTextLayout = { if (!wrapLines) onWidth(ceil(it.multiParagraph.intrinsics.maxIntrinsicWidth.toDouble()).toInt()) })
}

/**
 * 获取分段标题。
 * @param kind 变化类别。
 * @param mode 查看范围。
 * @return 本地化资源。
 */
private fun sectionLabel(kind: ChangeKind, mode: ComparisonMode): Int = when {
    mode == ComparisonMode.CONSTANTS && kind == ChangeKind.ADDED -> R.string.compare_member_section_added
    mode == ComparisonMode.CONSTANTS && kind == ChangeKind.MODIFIED -> R.string.compare_member_section_modified
    mode == ComparisonMode.CONSTANTS && kind == ChangeKind.REMOVED -> R.string.compare_member_section_removed
    else -> when (kind) {
        ChangeKind.ADDED -> R.string.compare_section_added
        ChangeKind.MODIFIED -> R.string.compare_section_modified
        ChangeKind.REMOVED -> R.string.compare_section_removed
        ChangeKind.OUTSIDE -> R.string.compare_outside
        ChangeKind.UNKNOWN -> R.string.compare_unknown
    }
}

/**
 * 依现有主题表达分段类别。
 * @param kind 变化类别。
 * @return 分段文字颜色。
 */
@Composable
private fun sectionColor(kind: ChangeKind): Color = when (kind) {
    ChangeKind.ADDED -> Theme.status.success
    ChangeKind.MODIFIED, ChangeKind.UNKNOWN -> Theme.status.warning
    ChangeKind.REMOVED -> MiuixTheme.colorScheme.error
    ChangeKind.OUTSIDE -> MiuixTheme.colorScheme.primary
}
