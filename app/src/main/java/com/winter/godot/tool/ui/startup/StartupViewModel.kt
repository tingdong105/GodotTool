package com.winter.godot.tool.ui.startup

import android.animation.ValueAnimator
import android.app.Application
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.AndroidViewModel
import com.winter.godot.tool.App
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal const val STARTUP_MOTION_MILLIS = 1_050f
internal const val STARTUP_DURATION_MILLIS = 1_200f

/**
 * 保存一次启动的帧进度与系统启动屏交接状态。
 * @author Tingdong
 * @date 2026-11-26
 */
class StartupViewModel(application: Application) : AndroidViewModel(application) {
    private var windowGeneration = 0
    var finished by mutableStateOf(!(application as App).claimStartup() || !ValueAnimator.areAnimatorsEnabled())
        private set
    var elapsedMillis by mutableFloatStateOf(0f)
        private set
    var assetReady by mutableStateOf(false)
        private set
    var contentDrawn by mutableStateOf(false)
        private set
    var systemReleased by mutableStateOf(Build.VERSION.SDK_INT < 31)
        private set
    var handoffPrepared by mutableStateOf(false)
        private set
    var handoffDrawn by mutableStateOf(false)
        private set
    var iconBounds by mutableStateOf<IntRect?>(null)
        private set
    val canDraw: Boolean get() = finished || assetReady

    /** 标记本地动画已完成解析。 */
    fun assetLoaded() { assetReady = true }

    /** 标记底层应用已经完成首帧绘制。 */
    fun contentReady() { if (!contentDrawn) contentDrawn = true }

    /**
     * 在系统画面仍可见时同步完整矢量画布。
     * @param bounds 完整矢量画布相对于应用窗口的像素边界。
     * @param generation 发起交接的窗口代次。
     * @return 是否接收当前窗口的交接。
     */
    fun prepareHandoff(bounds: IntRect?, generation: Int): Boolean {
        if (generation != windowGeneration || finished) return false
        if (bounds != null && bounds.width > 0 && bounds.height > 0) iconBounds = bounds
        systemReleased = false
        handoffDrawn = false
        handoffPrepared = true
        return true
    }

    /**
     * 确认新边界对应的动画首帧已绘制。
     * @param bounds 本次实际绘制使用的画布边界。
     */
    fun artworkDrawn(bounds: IntRect?) {
        if (handoffPrepared && bounds == iconBounds && !handoffDrawn) handoffDrawn = true
    }

    /**
     * 接收当前窗口的系统画面移除信号。
     * @param generation 已移除启动屏的窗口代次。
     */
    fun releaseSystem(generation: Int) {
        if (generation == windowGeneration) systemReleased = true
    }

    /**
     * 清理旧窗口的首帧状态，保留重建前的播放进度。
     * @return 当前窗口代次，用于隔离旧窗口回调。
     */
    fun resetWindow(): Int {
        systemReleased = Build.VERSION.SDK_INT < 31 || windowGeneration > 0
        windowGeneration++
        iconBounds = null
        contentDrawn = false
        assetReady = false
        handoffPrepared = false
        handoffDrawn = false
        return windowGeneration
    }

    /**
     * 等待真实移除信号，无系统回调时跳过动画。
     */
    suspend fun awaitSystemRelease() {
        val signalled = withTimeoutOrNull(1_000L) {
            snapshotFlow { systemReleased || handoffPrepared || finished }.first { it }
        }
        if (signalled == null) {
            Log.w("Startup", "System splash handoff unavailable")
            finish()
            return
        }
        snapshotFlow { systemReleased || finished }.first { it }
    }

    /**
     * 按实际显示帧推进动画，配置变化后从原位置继续。
     * @param millis 本次显示帧经过的毫秒数。
     */
    fun advance(millis: Float) {
        if (finished || !systemReleased) return
        elapsedMillis = (elapsedMillis + millis.coerceAtLeast(0f)).coerceAtMost(STARTUP_DURATION_MILLIS)
        if (elapsedMillis >= STARTUP_DURATION_MILLIS) finish()
    }

    /** 结束动画并释放启动遮罩。 */
    fun finish() { finished = true }
}
