package com.winter.godot.tool.ui

import android.os.Build
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 当前窗口可用的应用栏模糊状态。 */
internal val LocalToolBlurEnabled = staticCompositionLocalOf { false }

/** 当前窗口是否支持真实背景模糊。 */
internal val LocalToolBlurSupported = staticCompositionLocalOf { false }

/** 由滚动内容内部消费的应用栏留白。 */
internal val LocalToolContentPadding = compositionLocalOf<PaddingValues> { PaddingValues(0.dp) }

/**
 * 检查版本、预览和窗口硬件加速，挂载后刷新能力。
 * 使用示例：val supported = toolBlurSupported()。
 * @return 当前窗口能否创建模糊采样层。
 */
@Composable
internal fun toolBlurSupported(): Boolean {
    if (Build.VERSION.SDK_INT < 33 || LocalInspectionMode.current) return false
    val view = LocalView.current
    var accelerated by remember(view) { mutableStateOf(view.isHardwareAccelerated) }
    DisposableEffect(view) {
        val listener = object : View.OnAttachStateChangeListener {
            /** 挂载窗口后刷新硬件加速状态。 */
            override fun onViewAttachedToWindow(v: View) { accelerated = v.isHardwareAccelerated }
            /** 脱离窗口后暂停模糊。 */
            override fun onViewDetachedFromWindow(v: View) { accelerated = false }
        }
        view.addOnAttachStateChangeListener(listener)
        accelerated = view.isHardwareAccelerated
        onDispose { view.removeOnAttachStateChangeListener(listener) }
    }
    return accelerated && isRuntimeShaderSupported()
}

/**
 * 创建带主题底色的页面采样层，关闭时释放图层。
 * 使用示例：val backdrop = rememberToolBarBackdrop()。
 * @param enabled 已检查设备能力的启用状态。
 * @return 开启时的内容采样层，关闭时为空。
 */
@Composable
internal fun rememberToolBarBackdrop(enabled: Boolean = LocalToolBlurEnabled.current): LayerBackdrop? {
    if (!enabled) return null
    val surface = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
}

/**
 * 绘制应用栏毛玻璃或实色背景。
 * 使用示例：Modifier.toolBarBackground(backdrop)。
 * @param backdrop 仅包含栏位背后页面内容的采样层。
 * @return 带主题背景的修饰符。
 */
@Composable
internal fun Modifier.toolBarBackground(backdrop: LayerBackdrop?): Modifier {
    val surface = MiuixTheme.colorScheme.surface
    return if (backdrop != null) textureBlur(backdrop, RectangleShape, blurRadius = 25f,
        colors = BlurColors(blendColors = listOf(BlendColorEntry(surface.copy(alpha = 0.87f)))))
    else background(surface)
}

/**
 * 合并栏位留白与页面间距，保持滚动区域延伸到栏位下方。
 * 使用示例：LazyColumn(contentPadding = toolContentPadding(PaddingValues(16.dp)))。
 * @param padding 页面原有留白。
 * @return 按当前布局方向合并的内部留白。
 */
@Composable
internal fun toolContentPadding(padding: PaddingValues): PaddingValues {
    val bars = LocalToolContentPadding.current
    val direction = LocalLayoutDirection.current
    return PaddingValues(start = padding.calculateStartPadding(direction) + bars.calculateStartPadding(direction),
        top = padding.calculateTopPadding() + bars.calculateTopPadding(),
        end = padding.calculateEndPadding(direction) + bars.calculateEndPadding(direction),
        bottom = padding.calculateBottomPadding() + bars.calculateBottomPadding())
}
