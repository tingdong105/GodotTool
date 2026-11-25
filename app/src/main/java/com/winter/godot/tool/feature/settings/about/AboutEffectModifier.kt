// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
@file:androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.TIRAMISU)

package com.winter.godot.tool.feature.settings.about

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 组合逐帧更新的流光绘制节点。
 * 使用示例：Modifier.aboutEffectDraw(painter, preset, device, dark, surface, true, false, playing, { stage }, { alpha })。
 * @return 根据播放状态刷新背景的修饰符。
 */
internal fun Modifier.aboutEffectDraw(
    painter: AboutEffectPainter,
    preset: AboutEffectConfig.Config,
    deviceType: AboutDeviceType,
    isDarkTheme: Boolean,
    surface: Color,
    effectBackground: Boolean,
    isFullSize: Boolean,
    playing: Boolean,
    colorStage: () -> Float,
    alpha: () -> Float,
): Modifier = this then AboutEffectElement(
    painter = painter,
    preset = preset,
    deviceType = deviceType,
    isDarkTheme = isDarkTheme,
    surface = surface,
    effectBackground = effectBackground,
    isFullSize = isFullSize,
    playing = playing,
    colorStage = colorStage,
    alpha = alpha,
)

/**
 * 流光节点的参数快照。
 * @author compose-miuix-ui contributors
 * @date 2026-11-25
 */
private data class AboutEffectElement(
    val painter: AboutEffectPainter,
    val preset: AboutEffectConfig.Config,
    val deviceType: AboutDeviceType,
    val isDarkTheme: Boolean,
    val surface: Color,
    val effectBackground: Boolean,
    val isFullSize: Boolean,
    val playing: Boolean,
    val colorStage: () -> Float,
    val alpha: () -> Float,
) : ModifierNodeElement<AboutEffectNode>() {

    /**
     * 创建独立的流光绘制节点。
     */
    override fun create(): AboutEffectNode = AboutEffectNode(
        painter = painter,
        preset = preset,
        deviceType = deviceType,
        isDarkTheme = isDarkTheme,
        surface = surface,
        effectBackground = effectBackground,
        isFullSize = isFullSize,
        playing = playing,
        colorStage = colorStage,
        alpha = alpha,
    )

    /**
     * 同步视口、主题与播放参数。
     */
    override fun update(node: AboutEffectNode) {
        node.update(
            painter = painter,
            preset = preset,
            deviceType = deviceType,
            isDarkTheme = isDarkTheme,
            surface = surface,
            effectBackground = effectBackground,
            isFullSize = isFullSize,
            playing = playing,
            colorStage = colorStage,
            alpha = alpha,
        )
    }
}

/**
 * 管理着色器逐帧绘制与取消。
 * @author compose-miuix-ui contributors
 * @date 2026-11-25
 */
private class AboutEffectNode(
    private var painter: AboutEffectPainter,
    private var preset: AboutEffectConfig.Config,
    private var deviceType: AboutDeviceType,
    private var isDarkTheme: Boolean,
    private var surface: Color,
    private var effectBackground: Boolean,
    private var isFullSize: Boolean,
    private var playing: Boolean,
    private var colorStage: () -> Float,
    private var alpha: () -> Float,
) : Modifier.Node(),
    DrawModifierNode {

    private var animationJob: Job? = null
    private var animTime: Float = 0f
    private var startOffset: Float = 0f

    /**
     * 挂载时按播放状态启动绘制。
     */
    override fun onAttach() {
        if (playing) startAnimation()
    }

    /**
     * 卸载时取消帧回调。
     */
    override fun onDetach() {
        animationJob?.cancel()
        animationJob = null
    }

    /**
     * 同步视口、主题与播放参数。
     */
    fun update(
        painter: AboutEffectPainter,
        preset: AboutEffectConfig.Config,
        deviceType: AboutDeviceType,
        isDarkTheme: Boolean,
        surface: Color,
        effectBackground: Boolean,
        isFullSize: Boolean,
        playing: Boolean,
        colorStage: () -> Float,
        alpha: () -> Float,
    ) {
        this.painter = painter
        this.preset = preset
        this.deviceType = deviceType
        this.isDarkTheme = isDarkTheme
        this.surface = surface
        this.effectBackground = effectBackground
        this.isFullSize = isFullSize
        this.colorStage = colorStage
        this.alpha = alpha

        if (this.playing != playing) {
            this.playing = playing
            if (playing && isAttached) {
                startAnimation()
            } else {
                animationJob?.cancel()
                animationJob = null
            }
        }
        invalidateDraw()
    }

    /**
     * 按累计时间以目标每秒六十帧推进着色器。
     */
    private fun startAnimation() {
        animationJob?.cancel()
        startOffset = animTime
        animationJob = coroutineScope.launch {
            val origin = withFrameNanos { it }
            val schedule = AboutFrameSchedule(origin)
            while (isActive) {
                val now = withFrameNanos { it }
                if (!schedule.shouldDraw(now)) continue
                animTime = startOffset + (now - origin) / 1_000_000_000f
                invalidateDraw()
            }
        }
    }

    /**
     * 先绘制流光再绘制页面内容。
     */
    override fun ContentDrawScope.draw() {
        drawRect(surface)
        if (effectBackground && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            val alphaValue = alpha()
            if (alphaValue > 0f) {
                val drawHeight = if (isFullSize) size.height * 0.8f else size.height * 0.5f

                painter.updateResolution(size.width, size.height)
                painter.updateBoundIfNeeded(drawHeight, size.height, size.width)
                painter.updatePresetIfNeeded(deviceType, isDarkTheme)
                painter.updateColors(preset, colorStage())
                painter.updateAnimTime(animTime)
                painter.updatePointsAnim(animTime, preset)

                drawRect(painter.brush, alpha = alphaValue)
            }
        }
        drawContent()
    }
}

/**
 * 保留帧时间余量，跳过已错过的绘制时点。
 * @author Tingdong
 * @date 2026-11-25
 */
internal class AboutFrameSchedule(startNanos: Long) {
    private val intervalNanos = 1_000_000_000L / 60L
    private var nextFrameNanos = startNanos + intervalNanos

    /**
     * 判断当前显示帧是否需要更新流光。
     * @param nowNanos 当前单调递增的显示帧时间。
     * @return 到达目标时点时返回 true，每次仅绘制一帧。
     */
    fun shouldDraw(nowNanos: Long): Boolean {
        if (nowNanos < nextFrameNanos) return false
        nextFrameNanos += ((nowNanos - nextFrameNanos) / intervalNanos + 1) * intervalNanos
        return true
    }
}

