package com.winter.godot.tool.ui

import android.content.res.Configuration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import com.winter.godot.tool.ui.theme.Theme

/**
 * 基于 Android 窗口配置统一判断设备布局与方向。
 * 使用示例：ToolDevice.isPad && ToolDevice.isLandscape。
 * @author Tingdong
 * @date 2026-11-26
 */
internal object ToolDevice {
    private const val PAD_SMALLEST_WIDTH_DP = 600

    /** 当前窗口是否使用平板布局，旋转不会把普通手机判成平板。 */
    val isPad: Boolean
        @Composable get() = LocalConfiguration.current.smallestScreenWidthDp >= PAD_SMALLEST_WIDTH_DP

    /** 当前窗口是否使用手机布局。 */
    val isPhone: Boolean
        @Composable get() = !isPad

    /** 使用系统方向，避免侧栏、底栏和键盘改变布局判断。 */
    val isLandscape: Boolean
        @Composable get() = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
}

/** 扣除导航和安全区域后，页面是否具备双栏空间。 */
internal val LocalWideContent = staticCompositionLocalOf { false }

/**
 * 记住可跨容器移动的内容，并始终读取最新参数。
 * 使用示例：val card = rememberMovableToolContent { SettingsCard(settings) }。
 * @param content 最新的卡片内容。
 * @return 搬移时保留局部状态的内容。
 */
@Composable
internal fun rememberMovableToolContent(content: @Composable () -> Unit): @Composable () -> Unit {
    val current by rememberUpdatedState(content)
    return remember { movableContentOf { current() } }
}

/**
 * 设置分组随窗口切换单双列，共用父级保存的滚动状态。
 * 使用示例：AdaptiveColumns(primary = { ThemeGroup() }, secondary = { StorageGroup() })。
 * @param modifier 容器布局。
 * @param scrollState 跨设备布局保留的纵向滚动状态。
 * @param spacing 分组间距。
 * @param contentPadding 内容边距。
 * @param primary 窄屏先显示、宽屏位于起始侧的内容。
 * @param secondary 窄屏接续、宽屏位于末端侧的内容。
 */
@Composable
internal fun AdaptiveColumns(modifier: Modifier = Modifier, scrollState: ScrollState = rememberScrollState(),
    spacing: Dp = Theme.spacing.md, contentPadding: PaddingValues = PaddingValues(Theme.spacing.md),
    primary: @Composable () -> Unit, secondary: @Composable () -> Unit) {
    val currentSpacing by rememberUpdatedState(spacing)
    val first = rememberMovableToolContent {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(currentSpacing)) { primary() }
    }
    val second = rememberMovableToolContent {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(currentSpacing)) { secondary() }
    }
    if (LocalWideContent.current) {
        Row(modifier.fillMaxSize().verticalScroll(scrollState).padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
            Column(Modifier.weight(1f)) { first() }
            Column(Modifier.weight(1f)) { second() }
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(scrollState).padding(contentPadding), verticalArrangement = Arrangement.spacedBy(spacing)) {
            first()
            second()
        }
    }
}
