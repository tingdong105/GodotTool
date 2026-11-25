package com.winter.godot.tool.ui.theme

import android.animation.ValueAnimator
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.winter.godot.tool.data.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.hypot

// 与 Telegram 相同的二次缓入缓出。
private val themeRevealEasing = Easing { fraction ->
    if (fraction < 0.5f) 2f * fraction * fraction else 1f - 2f * (1f - fraction) * (1f - fraction)
}

/**
 * 一次切换的旧主题截图和圆形遮罩几何。
 * @param image 仅保存在内存中的旧画面。
 * @param center 点击位置在根绘制区域内的坐标。
 * @param fromDark 原主题是否为暗色。
 * @author Tingdong
 * @date 2026-11-25
 */
private class ThemeSnapshot(val image: ImageBitmap, val center: Offset, val fromDark: Boolean) {
    val maxRadius = hypot(maxOf(abs(center.x), abs(image.width - center.x)),
        maxOf(abs(center.y), abs(image.height - center.y))) + 1f
}

/**
 * 打开颜色菜单前保留的旧主题画面。
 * @param image 不包含菜单的内存截图。
 * @param dark 截图对应的主题明暗。
 * @param windowOffset 截图根区域在窗口内的位置。
 * @author Tingdong
 * @date 2026-11-25
 */
private class ThemeFrame(val image: ImageBitmap, val dark: Boolean, val windowOffset: Offset)

/**
 * 管理截图捕获、首帧等待与互斥的主题过渡。
 * @author Tingdong
 * @date 2026-11-25
 */
private class ThemeTransitionState {
    var snapshot by mutableStateOf<ThemeSnapshot?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var size by mutableStateOf(IntSize.Zero)
        private set
    val progress = Animatable(0f)
    private var windowOffset = Offset.Zero
    private var drawnDark: Boolean? = null
    private var frameWaiter: Pair<Boolean, CompletableDeferred<Unit>>? = null
    private var job: Job? = null
    private var generation = 0
    private var preparedFrame: ThemeFrame? = null

    /**
     * 更新窗口几何，窗口变化时结束旧尺寸的过渡。
     * @param newSize 当前根区域尺寸。
     * @param offset 根区域在窗口内的位置。
     */
    fun placed(newSize: IntSize, offset: Offset) {
        if (size != newSize || windowOffset != offset) finish()
        size = newSize
        windowOffset = offset
    }

    /**
     * 通知真实界面已完成当前主题绘制。
     * @param dark 本帧的主题明暗。
     */
    fun drawn(dark: Boolean) {
        drawnDark = dark
        frameWaiter?.takeIf { it.first == dark }?.second?.complete(Unit)
    }

    /**
     * 打开菜单前准备截图，选中主题时无需再等待捕获。
     * @param layer 不包含过渡遮罩的应用绘制层。
     * @param dark 当前主题明暗。
     * @return 是否可以打开颜色菜单。
     */
    suspend fun prepare(layer: GraphicsLayer, dark: Boolean): Boolean {
        if (busy) return false
        val request = ++generation
        busy = true
        job = currentCoroutineContext().job
        try {
            progress.snapTo(0f)
            val captured = if (ValueAnimator.areAnimatorsEnabled()) {
                withTimeoutOrNull(150L) { capture(layer, dark) }
            } else null
            currentCoroutineContext().ensureActive()
            preparedFrame = captured
            return true
        } finally {
            if (generation == request) {
                busy = false
                job = null
            }
        }
    }

    /**
     * 取消选择时释放预备截图。
     */
    fun discard() {
        preparedFrame = null
    }

    /**
     * 立即提交主题，使用预备截图播放遮罩。
     * @param scope 界面协程作用域。
     * @param fromDark 当前主题明暗。
     * @param targetDark 目标主题明暗。
     * @param origin 点击位置的窗口坐标。
     * @param commit 保存用户选择。
     */
    fun select(scope: CoroutineScope, fromDark: Boolean, targetDark: Boolean,
        origin: Offset, commit: () -> Unit) {
        if (busy) return
        val frame = preparedFrame
        preparedFrame = null
        val center = origin - windowOffset
        if (fromDark == targetDark || !ValueAnimator.areAnimatorsEnabled() || frame == null ||
            frame.dark != fromDark || frame.windowOffset != windowOffset ||
            frame.image.width != size.width || frame.image.height != size.height ||
            !center.x.isFinite() || !center.y.isFinite() ||
            center.x !in 0f..size.width.toFloat() || center.y !in 0f..size.height.toFloat()) {
            commit()
            return
        }
        val request = ++generation
        busy = true
        snapshot = ThemeSnapshot(frame.image, center, fromDark)
        // 保存发生在点击回调内，动画只等待新主题的真实绘制。
        commit()
        job = scope.launch {
            try {
                if (awaitThemeFrame(targetDark)) {
                    progress.animateTo(1f, tween(450, easing = themeRevealEasing))
                }
            } finally {
                if (generation == request) {
                    snapshot = null
                    frameWaiter = null
                    busy = false
                    job = null
                }
            }
        }
    }

    /**
     * 窗口变化或离开前台时释放截图并取消动画。
     */
    fun finish() {
        generation++
        job?.cancel()
        job = null
        frameWaiter = null
        snapshot = null
        preparedFrame = null
        busy = false
    }

    /**
     * 读取旧主题绘制层，捕获失败时降级为即时切换。
     * @param layer 应用绘制层。
     * @param dark 原主题明暗。
     * @return 内存截图，窗口无效或捕获失败时返回 null。
     */
    private suspend fun capture(layer: GraphicsLayer, dark: Boolean): ThemeFrame? {
        val capturedSize = size
        val capturedOffset = windowOffset
        if (drawnDark != dark || capturedSize.width <= 0 || capturedSize.height <= 0) return null
        return try {
            val image = layer.toImageBitmap()
            if (size != capturedSize || windowOffset != capturedOffset || drawnDark != dark ||
                image.width != capturedSize.width || image.height != capturedSize.height) null
            else ThemeFrame(image, dark, capturedOffset)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("ThemeTransition", "Could not capture theme transition", error)
            null
        } catch (error: OutOfMemoryError) {
            Log.w("ThemeTransition", "Not enough memory for theme transition", error)
            null
        }
    }

    /**
     * 等待目标主题的首帧，防止遮罩提前露出旧画面。
     * @param dark 目标主题明暗。
     * @return 是否在限定时间内完成绘制。
     */
    private suspend fun awaitThemeFrame(dark: Boolean): Boolean {
        if (drawnDark == dark) return true
        val signal = CompletableDeferred<Unit>()
        frameWaiter = dark to signal
        return try {
            withTimeoutOrNull(500L) { signal.await(); true } ?: false
        } finally {
            if (frameWaiter?.second === signal) frameWaiter = null
        }
    }
}

/**
 * 提供从点击处展开或收回的截图主题过渡。
 * @param mode 已保存的主题偏好。
 * @param onModeChange 保存新的主题偏好。
 * @param content 接收准备截图、主题切换与取消选择的回调。
 */
@Composable
fun ThemeTransitionHost(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit,
    content: @Composable (suspend () -> Boolean, (ThemeMode, Offset) -> Unit, () -> Unit) -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) { ThemeMode.SYSTEM -> systemDark; ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val state = remember { ThemeTransitionState() }
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val saveMode by rememberUpdatedState(onModeChange)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) state.finish()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); state.finish() }
    }
    ThemeTransitionSystemBars(state, dark)
    GodotToolTheme(mode, updateSystemBars = false) {
        val background = MiuixTheme.colorScheme.surface
        Box(Modifier.fillMaxSize().background(background)
            .onGloballyPositioned { state.placed(it.size, it.positionInWindow()) }
            .onPreviewKeyEvent { state.busy }
            .pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (state.busy) event.changes.forEach { it.consume() }
                    }
                }
            }) {
            Box(Modifier.matchParentSize().drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
                state.drawn(dark)
            }.then(if (state.busy) Modifier.clearAndSetSemantics {} else Modifier)) {
                content({
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && state.prepare(layer, dark)
                }, { selected, origin ->
                    if (!state.busy) {
                        val targetDark = when (selected) { ThemeMode.SYSTEM -> systemDark; ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            state.select(scope, dark, targetDark, origin) { saveMode(selected) }
                        } else saveMode(selected)
                    }
                }, state::discard)
            }
            ThemeTransitionOverlay(state, Modifier.matchParentSize())
        }
        BackHandler(enabled = state.busy) { }
    }
}

/**
 * 使用离屏清除遮罩，暗色展开、浅色收回。
 * @param state 截图与动画进度。
 * @param modifier 覆盖整个窗口的布局修饰。
 */
@Composable
private fun ThemeTransitionOverlay(state: ThemeTransitionState, modifier: Modifier) {
    val snapshot = state.snapshot ?: return
    val clearPath = remember(snapshot) { Path().apply { fillType = PathFillType.EvenOdd } }
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        drawImage(snapshot.image)
        val radius = snapshot.maxRadius * if (snapshot.fromDark) 1f - state.progress.value else state.progress.value
        if (snapshot.fromDark) {
            clearPath.reset()
            clearPath.fillType = PathFillType.EvenOdd
            clearPath.addRect(Rect(Offset.Zero, size))
            clearPath.addOval(Rect(snapshot.center.x - radius, snapshot.center.y - radius,
                snapshot.center.x + radius, snapshot.center.y + radius))
            drawPath(clearPath, Color.Transparent, blendMode = BlendMode.Clear)
        } else drawCircle(Color.Transparent, radius, snapshot.center, blendMode = BlendMode.Clear)
    }
}

/**
 * 系统栏图标在圆形边缘经过时同步切换明暗。
 * @param state 截图与动画进度。
 * @param dark 当前有效主题。
 */
@Composable
private fun ThemeTransitionSystemBars(state: ThemeTransitionState, dark: Boolean) {
    val activity = LocalActivity.current ?: return
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navigation = WindowInsets.navigationBars
    val bottom = navigation.getBottom(density)
    val left = navigation.getLeft(density, direction)
    val right = navigation.getRight(density, direction)
    LaunchedEffect(activity, state, dark, statusTop, bottom, left, right) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        snapshotFlow {
            val snapshot = state.snapshot
            if (snapshot == null) dark to dark else {
                val size = state.size
                val statusPoint = Offset(size.width / 2f, statusTop / 2f)
                val navigationPoint = when {
                    bottom > 0 -> Offset(size.width / 2f, size.height - bottom / 2f)
                    right > 0 -> Offset(size.width - right / 2f, size.height / 2f)
                    left > 0 -> Offset(left / 2f, size.height / 2f)
                    else -> Offset(size.width / 2f, size.height.toFloat())
                }
                val progress = state.progress.value
                val radius = snapshot.maxRadius * if (snapshot.fromDark) 1f - progress else progress
                val statusDark = if (progress <= 0f) snapshot.fromDark else (statusPoint - snapshot.center).getDistance() <= radius
                val navigationDark = if (progress <= 0f) snapshot.fromDark else (navigationPoint - snapshot.center).getDistance() <= radius
                statusDark to navigationDark
            }
        }.distinctUntilChanged().collect { (statusDark, navigationDark) ->
            controller.isAppearanceLightStatusBars = !statusDark
            controller.isAppearanceLightNavigationBars = !navigationDark
        }
    }
}
