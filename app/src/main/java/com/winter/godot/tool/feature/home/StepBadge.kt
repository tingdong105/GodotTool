package com.winter.godot.tool.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.winter.godot.tool.ui.theme.Theme
import top.yukonga.miuix.kmp.basic.Text

/**
 * 标准数字轮廓快速变形成勾，回退编辑时平滑反向播放。
 * @param number 步骤编号。
 * @param completed 当前步骤是否完成。
 * @param color 随主题变化的笔画颜色。
 * @param modifier 图标布局修饰。
 */
@Composable
internal fun StepBadge(number: Int, completed: Boolean, color: Color, modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset("lottie/step_${number}_complete.json"))
    val animation = rememberLottieAnimatable()
    var previous by rememberSaveable(number) { mutableStateOf(completed) }
    val properties = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(LottieProperty.COLOR, color.toArgb(), "**"))
    LaunchedEffect(composition, completed) {
        val scene = composition ?: return@LaunchedEffect
        val initial = if (animation.composition == null) { if (previous) 1f else 0f } else animation.progress
        if (previous == completed) animation.snapTo(scene, progress = if (completed) 1f else 0f)
        else {
            previous = completed
            animation.animate(scene, initialProgress = initial, speed = if (completed) 1f else -1f, iterations = 1)
        }
    }
    if (composition != null) LottieAnimation(composition, progress = { if (animation.composition == null) { if (previous) 1f else 0f } else animation.progress }, dynamicProperties = properties, modifier = modifier)
    else Text(if (completed) "✓" else number.toString(), color = color, style = Theme.typography.number)
}
