package com.winter.godot.tool.ui.startup

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.ScreenPreviews
import com.winter.godot.tool.ui.theme.GodotToolTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 在真实页面上播放一次启动动画，期间屏蔽页面输入。
 * @param state 当前进程的启动状态，预览及非启动入口可为空。
 * @param content 保持正常组合和加载的应用页面。
 */
@Composable
fun StartupHost(state: StartupViewModel?, content: @Composable () -> Unit) {
    val covering = state != null && !state.finished
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { covering }) {
        Box(Modifier.fillMaxSize().drawWithContent {
            drawContent()
            state?.contentReady()
        }.then(if (covering) Modifier.clearAndSetSemantics {} else Modifier)) { content() }
        if (covering) StartupPlayback(requireNotNull(state))
        if (covering) Box(Modifier.matchParentSize().clearAndSetSemantics {}.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        })
    }
}

/**
 * 管理本地解析、前台帧时钟和启动屏移交。
 * @param state 跨配置变化保留的动画状态。
 */
@Composable
private fun StartupPlayback(state: StartupViewModel) {
    val result = rememberLottieComposition(LottieCompositionSpec.Asset("lottie/startup.json"))
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    val initialBackground = colorResource(R.color.startup_background)
    val background = MiuixTheme.colorScheme.background
    LaunchedEffect(state, result) {
        try {
            if (withTimeoutOrNull(500) { result.await() } == null) {
                Log.w("Startup", "Animation loading timed out")
                state.finish()
            } else state.assetLoaded()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("Startup", "Animation loading failed", error)
            state.finish()
        }
    }
    DisposableEffect(state, lifecycle, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) state.finish()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { state.assetReady && state.contentDrawn }.first { it }
            state.awaitSystemRelease()
            if (state.finished) return@repeatOnLifecycle
            var previous = withFrameNanos { it }
            while (!state.finished) {
                val now = withFrameNanos { it }
                if (!ValueAnimator.areAnimatorsEnabled()) state.finish()
                else state.advance((now - previous) / 1_000_000f)
                previous = now
            }
        }
    }
    LaunchedEffect(state, activity, initialBackground, background) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        try {
            snapshotFlow { startupBackground(initialBackground, background, state.elapsedMillis).luminance() >= 0.5f }
                .distinctUntilChanged().collect { light ->
                    controller.isAppearanceLightStatusBars = light
                    controller.isAppearanceLightNavigationBars = light
                }
        } finally {
            val light = background.luminance() >= 0.5f
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
    }
    val bounds = state.iconBounds
    StartupArtwork(result.value, { state.elapsedMillis }, initialBackground, background, bounds) { state.artworkDrawn(bounds) }
    BackHandler { activity?.finish() }
}

/**
 * 将系统底色在蓄力阶段过渡到应用主题。
 * 使用示例：startupBackground(systemColor, appColor, 90f)。
 * @param initial 系统启动屏颜色。
 * @param target 当前应用背景色。
 * @param millis 动画经过的毫秒数。
 * @return 当前帧的背景色。
 */
private fun startupBackground(initial: Color, target: Color, millis: Float): Color =
    lerp(initial, target, (millis / 180f).coerceIn(0f, 1f))

/**
 * 绘制透明矢量动画及整体淡出，所有逐帧读取只发生在绘制阶段。
 * @param composition 本地 Lottie 素材。
 * @param elapsedMillis 当前播放时间。
 * @param initialBackground 系统启动屏底色。
 * @param background 应用主题底色。
 * @param bounds 系统前景画布的像素边界，为空时在窗口居中。
 * @param onFrameDrawn 当前画布和首帧绘制完成的回调。
 */
@Composable
private fun StartupArtwork(composition: LottieComposition?, elapsedMillis: () -> Float,
    initialBackground: Color, background: Color, bounds: IntRect? = null, onFrameDrawn: () -> Unit = {}) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer {
        alpha = (1f - (elapsedMillis() - STARTUP_MOTION_MILLIS) /
            (STARTUP_DURATION_MILLIS - STARTUP_MOTION_MILLIS)).coerceIn(0f, 1f)
    }.drawBehind { drawRect(startupBackground(initialBackground, background, elapsedMillis())) }
        .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        val size = minOf(288.dp, maxWidth, maxHeight)
        val iconModifier = if (bounds == null) Modifier.size(size) else {
            Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { IntOffset(bounds.left, bounds.top) }
                .wrapContentSize(AbsoluteAlignment.TopLeft, unbounded = true)
                .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
        }
        LottieAnimation(composition, progress = { (elapsedMillis() / STARTUP_MOTION_MILLIS).coerceIn(0f, 1f) },
            modifier = iconModifier.drawWithContent { drawContent(); onFrameDrawn() })
    }
}

/** 手机、平板、分屏明暗主题的钻出动作预览。 */
@ScreenPreviews
@androidx.compose.ui.tooling.preview.Preview(name = "手机横屏·启动", widthDp = 900, heightDp = 400)
@androidx.compose.ui.tooling.preview.Preview(name = "手机横屏·暗色启动", widthDp = 900, heightDp = 400,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StartupPreview() {
    val composition = rememberLottieComposition(LottieCompositionSpec.Asset("lottie/startup.json"))
    GodotToolTheme {
        StartupArtwork(composition.value, { 550f }, MiuixTheme.colorScheme.background, MiuixTheme.colorScheme.background)
    }
}
