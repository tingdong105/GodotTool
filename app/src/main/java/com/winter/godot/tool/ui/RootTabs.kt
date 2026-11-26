package com.winter.godot.tool.ui

import android.os.Build
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.components.glass.FloatingBottomBar
import com.winter.godot.tool.ui.components.glass.FloatingBottomBarMode
import com.winter.godot.tool.ui.components.toolIconVector
import com.winter.godot.tool.ui.theme.Theme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.nav.runtime.NavProgrammaticEasing
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

/** 悬浮底栏在一级页面中的底部避让高度。 */
internal val LocalRootBottomInset = staticCompositionLocalOf { 0.dp }

/**
 * 一级页面共用的横向分页与可选悬浮导航。
 * @param floatingBottomBar 是否使用悬浮底栏。
 * @param liquidGlass 是否启用液态玻璃。
 * @param screen 业务导航选中的一级页面。
 * @param active 当前是否处于一级页面。
 * @param onNavigate 同步标签或打开子页面。
 * @param content 各标签的业务内容。
 */
@Composable
fun RootTabs(screen: Screen, active: Boolean, onNavigate: (Screen) -> Unit,
    floatingBottomBar: Boolean = false, liquidGlass: Boolean = true, content: @Composable (Screen) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layoutDirection = LocalLayoutDirection.current
        val safeHorizontal = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
        val safeWidth = maxWidth - safeHorizontal.calculateStartPadding(layoutDirection) - safeHorizontal.calculateEndPadding(layoutDirection)
        val tablet = ToolDevice.isPad
        val floating = floatingBottomBar
        val rail = !floating && tablet && safeWidth >= Theme.sizes.navigationBreakpoint
        val tabs = remember { listOf(Screen.HOME, Screen.COMPARE, Screen.SETTINGS) }
        val selected = tabs.indexOf(screen).coerceAtLeast(0)
        val glassEnabled = floating && liquidGlass && Build.VERSION.SDK_INT >= 33 && isRuntimeShaderSupported()
        val backdrop = if (glassEnabled) rememberLayerBackdrop() else null
        val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomInset = if (floating) 76.dp + navigationBottom else 0.dp
        val navigationItems = listOf(
            NavigationItem(stringResource(R.string.home), toolIconVector(R.drawable.ic_workbench)),
            NavigationItem(stringResource(R.string.compare), toolIconVector(R.drawable.ic_compare)),
            NavigationItem(stringResource(R.string.settings), toolIconVector(R.drawable.ic_settings)),
        )
        val pager = rememberPagerState(initialPage = selected, pageCount = { tabs.size })
        val scope = rememberCoroutineScope()
        val navigate by rememberUpdatedState(onNavigate)
        var previousRail by remember { mutableStateOf(rail) }
        var initialized by remember { mutableStateOf(false) }
        var aligning by remember { mutableStateOf(true) }
        var requested by remember { mutableIntStateOf(selected) }
        var generation by remember { mutableIntStateOf(0) }
        var animation by remember { mutableStateOf<Job?>(null) }

        // 外部跳转和状态恢复直接对齐，标签点击的动画由点击事件驱动。
        LaunchedEffect(selected, rail) {
            if (!initialized || selected != requested || previousRail != rail) {
                previousRail = rail
                val command = ++generation
                animation?.cancel()
                aligning = true
                requested = selected
                try {
                    pager.scrollToPage(selected)
                } finally {
                    if (command == generation) { initialized = true; aligning = false }
                }
            }
        }
        LaunchedEffect(pager, active, floating) {
            snapshotFlow { Triple(if (floating) pager.currentPage else pager.settledPage, pager.isScrollInProgress, aligning) }.collect { (page, moving, syncing) ->
                if (active && initialized && (floating || !moving) && !syncing && page != requested) {
                    requested = page
                    navigate(tabs[page])
                }
            }
        }
        val select: (Screen) -> Unit = { target ->
            val index = tabs.indexOf(target)
            if (active && index >= 0 && (requested != index || pager.currentPage != index && animation?.isActive != true)) {
                val command = ++generation
                animation?.cancel()
                requested = index
                aligning = true
                navigate(target)
                animation = scope.launch {
                    try {
                        if (rail) pager.scrollToPage(index)
                        else pager.animateScrollToPage(index, animationSpec = if (floating)
                            tween(100 * kotlin.math.abs(index - pager.currentPage).coerceAtLeast(2) + 100, easing = EaseInOut)
                            else tween(260, easing = NavProgrammaticEasing))
                    } finally {
                        if (command == generation) aligning = false
                    }
                }
            }
        }
        val navigation: @Composable (Modifier) -> Unit = { modifier ->
            Scaffold(modifier = modifier, contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
                if (floating) {
                    Box(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                        .then(if (tablet) Modifier else Modifier.padding(safeHorizontal)).padding(bottom = Theme.spacing.md), contentAlignment = Alignment.BottomCenter) {
                        FloatingBottomBar(items = navigationItems, selectedIndex = { requested },
                            onSelected = { select(tabs[it]) }, backdrop = backdrop, modifier = Modifier.width(300.dp),
                            mode = if (glassEnabled) FloatingBottomBarMode.LiquidGlass else FloatingBottomBarMode.None,
                            iconContent = { item, _ -> Icon(item.icon, contentDescription = null) },
                            labelContent = { item, _ -> Text(item.label, fontSize = 11.sp, lineHeight = 14.sp,
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) })
                    }
                } else if (!rail) ToolBottomNavigation(tabs[requested], select)
            }) { padding ->
                val pagePadding = if (floating) PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection), end = padding.calculateEndPadding(layoutDirection),
                    top = padding.calculateTopPadding()) else padding
                CompositionLocalProvider(LocalRootBottomInset provides bottomInset) {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(pagePadding).consumeWindowInsets(pagePadding)
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                        .pagerGestureOverride(pager, enabled = active && !rail), userScrollEnabled = false,
                        beyondViewportPageCount = 1, pageNestedScrollConnection = PagerGestureNestedScrollConnection,
                        key = { tabs[it].name }) { page ->
                        val tab = tabs[page]
                        Box(Modifier.fillMaxSize().then(if (active && page == requested) Modifier else Modifier.clearAndSetSemantics {})) {
                            ToolScaffold(tab, onNavigate = { target ->
                                navigate(tab)
                                navigate(target)
                            }, showNavigation = false, floatingNavigation = floating) { content(tab) }
                        }
                    }
                }
            }
        }
        // 固定分页的组合位置。
        Row(Modifier.fillMaxSize().then(if (tablet) Modifier.padding(safeHorizontal).consumeWindowInsets(safeHorizontal) else Modifier)) {
            if (rail) NavigationRail(modifier = Modifier.fillMaxHeight()) {
                navigationItems.forEachIndexed { index, item ->
                    ToolNavigationRailItem(item, requested == index, active) { select(tabs[index]) }
                }
            }
            navigation(Modifier.weight(1f))
        }
    }
}

/**
 * 整项可点击，按压反馈仅显示在图标椭圆内。
 * @param item 导航图标与名称。
 * @param selected 是否为当前标签。
 * @param enabled 是否允许切换。
 * @param onClick 切换标签。
 */
@Composable
private fun ToolNavigationRailItem(item: NavigationItem, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val tint = if (enabled) MiuixTheme.colorScheme.onSurfaceContainer else MiuixTheme.colorScheme.disabledOnSurface
    Column(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.Tab,
        onClick = onClick, interactionSource = interaction, indication = null).padding(vertical = NavigationRailDefaults.ItemVerticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NavigationRailDefaults.IconTextSpacing)) {
        Box(Modifier.clip(CircleShape).background(if (selected) MiuixTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .indication(interaction, LocalIndication.current)
            .padding(horizontal = NavigationRailDefaults.ExpandedItemContentHorizontalPadding,
                vertical = NavigationRailDefaults.CollapsedIndicatorVerticalPadding)) {
            Icon(item.icon, contentDescription = null, modifier = Modifier.size(NavigationRailDefaults.IconSize), tint = tint)
        }
        Text(item.label, color = tint, fontSize = NavigationRailDefaults.LabelFontSize, fontWeight = FontWeight.Medium)
    }
}
