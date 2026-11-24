package com.winter.godot.tool.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.nav.runtime.NavProgrammaticEasing
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

/**
 * 首页与设置共用的横向分页，底部导航保持固定。
 * @param screen 业务导航选中的一级页面。
 * @param active 当前是否处于一级页面。
 * @param onNavigate 同步标签或打开子页面。
 * @param content 各标签的业务内容。
 */
@Composable
fun RootTabs(screen: Screen, active: Boolean, onNavigate: (Screen) -> Unit, content: @Composable (Screen) -> Unit) {
    val tabs = remember { listOf(Screen.HOME, Screen.COMPARE, Screen.SETTINGS) }
    val selected = tabs.indexOf(screen).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = selected, pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val navigate by rememberUpdatedState(onNavigate)
    var initialized by remember { mutableStateOf(false) }
    var aligning by remember { mutableStateOf(true) }
    var requested by remember { mutableIntStateOf(selected) }
    var generation by remember { mutableIntStateOf(0) }
    var animation by remember { mutableStateOf<Job?>(null) }

    // 外部跳转和状态恢复直接对齐，标签点击的动画由点击事件驱动。
    LaunchedEffect(selected) {
        if (!initialized || selected != requested) {
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
    LaunchedEffect(pager, active) {
        snapshotFlow { Triple(pager.settledPage, pager.isScrollInProgress, aligning) }.collect { (page, moving, syncing) ->
            if (active && initialized && !moving && !syncing && page != requested) {
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
                    pager.animateScrollToPage(index, animationSpec = tween(260, easing = NavProgrammaticEasing))
                } finally {
                    if (command == generation) aligning = false
                }
            }
        }
    }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
        ToolBottomNavigation(tabs[requested], select)
    }) { padding ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .pagerGestureOverride(pager, enabled = active), userScrollEnabled = false,
            beyondViewportPageCount = 1, pageNestedScrollConnection = PagerGestureNestedScrollConnection,
            key = { tabs[it].name }) { page ->
            val tab = tabs[page]
            Box(Modifier.fillMaxSize().then(if (active && page == requested) Modifier else Modifier.clearAndSetSemantics {})) {
                ToolScaffold(tab, onNavigate = { target ->
                    navigate(tab)
                    navigate(target)
                }, showNavigation = false) { content(tab) }
            }
        }
    }
}
