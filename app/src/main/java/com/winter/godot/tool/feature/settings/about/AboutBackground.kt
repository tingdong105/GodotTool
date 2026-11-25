// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.winter.godot.tool.feature.settings.about

import android.os.Build
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import com.winter.godot.tool.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.floor

/**
 * 为关于页提供可暂停的 OS3 流光和独立模糊采样层。
 * @param dark 使用当前应用的暗色主题。
 * @param playing 页面可见且前台时播放。
 * @param alpha 滚动过程中背景的透明度。
 * @param modifier 页面边界与顶栏采样层。
 * @param content 传入流光采样层，旧系统为空。
 */
@Composable
internal fun AboutBackground(dark: Boolean, playing: Boolean, alpha: () -> Float,
    modifier: Modifier = Modifier, content: @Composable (LayerBackdrop?) -> Unit) {
    val inspection = LocalInspectionMode.current
    val supported = isRuntimeShaderSupported() && !inspection
    val painter = remember(supported) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && supported) runCatching { AboutEffectPainter().also { it.runtimeShader } }
            .onFailure { Log.w("AboutBackground", "OS3 shader unavailable", it) }.getOrNull() else null
    }
    val surface = MiuixTheme.colorScheme.surface
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || painter == null) {
        Box(modifier.background(surface)) { content(null) }
        return
    }
    BoxWithConstraints(modifier.background(surface)) {
        val device = if (maxWidth >= Theme.sizes.tabletBreakpoint) AboutDeviceType.PAD else AboutDeviceType.PHONE
        val preset = remember(device, dark) { AboutEffectConfig.get(device, dark) }
        val backdrop = rememberLayerBackdrop()
        val colorStage = remember(preset) { Animatable(0f) }
        LaunchedEffect(playing, preset) {
            if (!playing) return@LaunchedEffect
            var target = floor(colorStage.value) + 1f
            while (isActive) {
                delay((preset.colorInterpPeriod * 500).toLong())
                colorStage.animateTo(target, spring(dampingRatio = 0.9f, stiffness = 35f))
                target += 1f
            }
        }
        Spacer(Modifier.fillMaxSize().layerBackdrop(backdrop).aboutEffectDraw(
            painter = painter, preset = preset, deviceType = device, isDarkTheme = dark, surface = surface,
            effectBackground = true, isFullSize = false, playing = playing,
            colorStage = { colorStage.value }, alpha = alpha))
        content(backdrop)
    }
}
