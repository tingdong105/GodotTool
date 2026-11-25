// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.winter.godot.tool.feature.settings.about

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Brush
import top.yukonga.miuix.kmp.shader.RuntimeShader
import top.yukonga.miuix.kmp.shader.asBrush
import kotlin.math.cos
import kotlin.math.sin

/**
 * 缓存 OS3 着色器与绘制参数。
 * @author compose-miuix-ui contributors, Tingdong
 * @date 2026-11-25
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class AboutEffectPainter {

    val runtimeShader by lazy {
        val shaderCode = OS3_BG_FRAG
        RuntimeShader(shaderCode).also {
            initStaticUniforms(it)
        }
    }

    val brush: Brush get() = runtimeShader.asBrush()

    private val resolution = FloatArray(2)
    private val bound = FloatArray(4)
    private val colorsBuffer = FloatArray(16)
    private val pointsAnimBuffer = FloatArray(8)

    private var animTime = Float.NaN
    private var isDarkCached: Boolean? = null
    private var deviceTypeCached: AboutDeviceType? = null

    private var presetApplied = false

    private var cachedLogoHeight = Float.NaN
    private var cachedTotalHeight = Float.NaN
    private var cachedTotalWidth = Float.NaN

    private var cachedColorStage = Float.NaN
    private var cachedColorsPreset: AboutEffectConfig.Config? = null

    private var cachedPointsAnimTime = Float.NaN
    private var cachedPointsAnimPreset: AboutEffectConfig.Config? = null

    companion object {

        private const val U_TRANSLATE_Y = 0f
        private const val U_ALPHA_MULTI = 1f
        private const val U_NOISE_SCALE = 1.5f
        private const val U_POINT_RADIUS_MULTI = 1f
    }

    /**
     * 设置不随帧变化的着色器参数。
     */
    private fun initStaticUniforms(shader: RuntimeShader) {
        shader.setFloatUniform("uTranslateY", U_TRANSLATE_Y)
        shader.setFloatUniform("uNoiseScale", U_NOISE_SCALE)
        shader.setFloatUniform("uPointRadiusMulti", U_POINT_RADIUS_MULTI)
        shader.setFloatUniform("uAlphaMulti", U_ALPHA_MULTI)
    }

    /**
     * 在视口尺寸变化时更新分辨率。
     */
    fun updateResolution(width: Float, height: Float) {
        if (resolution[0] == width && resolution[1] == height) return
        resolution[0] = width
        resolution[1] = height
        runtimeShader.setFloatUniform("uResolution", resolution)
    }

    /**
     * 更新着色器动画时间。
     */
    fun updateAnimTime(time: Float) {
        if (animTime == time) return
        animTime = time
        runtimeShader.setFloatUniform("uAnimTime", animTime)
    }

    /**
     * 按时间推进四个流光中心。
     */
    fun updatePointsAnim(time: Float, preset: AboutEffectConfig.Config) {
        if (cachedPointsAnimTime == time && cachedPointsAnimPreset === preset) return

        val offset = preset.pointOffset
        var i = 0
        while (i < 4) {
            val srcX = preset.points[i * 3]
            val srcY = preset.points[i * 3 + 1]
            val animX = srcX + sin(time + srcY) * offset
            val animY = srcY + cos(time + animX) * offset
            pointsAnimBuffer[i * 2] = animX
            pointsAnimBuffer[i * 2 + 1] = animY
            i++
        }
        runtimeShader.setFloatUniform("uPointsAnim", pointsAnimBuffer)

        cachedPointsAnimTime = time
        cachedPointsAnimPreset = preset
    }

    /**
     * 在循环配色之间进行插值。
     */
    fun updateColors(preset: AboutEffectConfig.Config, stage: Float) {
        if (cachedColorsPreset === preset && cachedColorStage == stage) return

        val base = stage.toInt()
        val fraction = stage - base
        val start = colorsForCycleIndex(preset, base)
        val end = colorsForCycleIndex(preset, base + 1)
        for (i in 0 until 16) {
            colorsBuffer[i] = start[i] + (end[i] - start[i]) * fraction
        }
        runtimeShader.setFloatUniform("uColors", colorsBuffer)

        cachedColorsPreset = preset
        cachedColorStage = stage
    }

    /**
     * 返回当前颜色循环阶段的预设。
     */
    private fun colorsForCycleIndex(preset: AboutEffectConfig.Config, index: Int): FloatArray = when (index.mod(4)) {
        1 -> preset.colors1
        3 -> preset.colors3
        else -> preset.colors2
    }

    /**
     * 在背景边界变化时更新绘制区域。
     */
    fun updateBoundIfNeeded(logoHeight: Float, totalHeight: Float, totalWidth: Float) {
        if (cachedLogoHeight == logoHeight &&
            cachedTotalHeight == totalHeight &&
            cachedTotalWidth == totalWidth
        ) {
            return
        }

        updateBound(logoHeight, totalHeight, totalWidth)
        runtimeShader.setFloatUniform("uBound", bound)

        cachedLogoHeight = logoHeight
        cachedTotalHeight = totalHeight
        cachedTotalWidth = totalWidth
    }

    /**
     * 在设备或主题变化时切换预设。
     */
    fun updatePresetIfNeeded(deviceType: AboutDeviceType, isDark: Boolean) {
        if (presetApplied && isDarkCached == isDark && deviceTypeCached == deviceType) return

        applyPreset(deviceType, isDark)

        isDarkCached = isDark
        deviceTypeCached = deviceType
        presetApplied = true
    }

    /**
     * 提交四点半径和亮度参数。
     */
    private fun applyPreset(deviceType: AboutDeviceType, isDark: Boolean) {
        val preset = AboutEffectConfig.get(deviceType, isDark)

        runtimeShader.setFloatUniform("uPoints", preset.points)
        runtimeShader.setFloatUniform("uLightOffset", preset.lightOffset)
        runtimeShader.setFloatUniform("uSaturateOffset", preset.saturateOffset)
    }

    /**
     * 按横竖屏计算流光覆盖区域。
     */
    private fun updateBound(logoHeight: Float, totalHeight: Float, totalWidth: Float) {
        val heightRatio = logoHeight / totalHeight
        if (totalWidth <= totalHeight) {
            bound[0] = 0f
            bound[1] = 1f - heightRatio
            bound[2] = 1f
            bound[3] = heightRatio
        } else {
            val aspectRatio = totalWidth / totalHeight
            val contentCenterY = 1f - heightRatio / 2f
            bound[0] = 0f
            bound[1] = contentCenterY - aspectRatio / 2f
            bound[2] = 1f
            bound[3] = aspectRatio
        }
    }
}

