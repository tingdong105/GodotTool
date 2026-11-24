package com.winter.godot.core.engine

import android.content.Context
import android.os.Handler
import android.view.SurfaceView
import org.godotengine.godot.GodotRenderView
import org.godotengine.godot.input.GodotInputHandler

/**
 * 为离屏引擎提供原生后端要求的视图接口。
 * @author Tingdong
 * @date 2026-11-21
 */
internal class OffscreenRenderView(context: Context, private val input: GodotInputHandler, private val engineThread: Handler) : SurfaceView(context), GodotRenderView {
    /**
     * 返回不挂载窗口的表面视图。
     */
    override fun getView(): SurfaceView = this

    /**
     * 循环由恢复驱动统一启动。
     */
    override fun startRenderer() = Unit

    /**
     * 将原生回调调度到引擎线程。
     * @param event 待执行操作。
     */
    override fun queueOnRenderThread(event: Runnable) { engineThread.post(event) }

    /**
     * 离屏任务的暂停由服务控制。
     */
    override fun onActivityPaused() = Unit

    /**
     * 离屏任务的停止由服务控制。
     */
    override fun onActivityStopped() = Unit

    /**
     * 离屏任务持续运行。
     */
    override fun onActivityResumed() = Unit

    /**
     * 离屏任务的启动由驱动控制。
     */
    override fun onActivityStarted() = Unit

    /**
     * 隔离进程退出时统一回收原生状态。
     * @param blockingTimeInMs 上游等待时间。
     */
    override fun blockingExitRenderer(blockingTimeInMs: Long): Boolean = true

    /**
     * 返回当前引擎的输入管理器。
     */
    override fun getInputHandler(): GodotInputHandler = input

    /**
     * 离屏表面无需显示光标。
     * @param pointerType 光标类型。
     * @param imagePath 光标资源。
     * @param hotSpotX 水平热点。
     * @param hotSpotY 垂直热点。
     */
    override fun configurePointerIcon(pointerType: Int, imagePath: String?, hotSpotX: Float, hotSpotY: Float) = Unit

    /**
     * 忽略离屏光标变更。
     * @param pointerType 光标类型。
     */
    override fun setPointerIcon(pointerType: Int) = Unit

    /**
     * 离屏模式关闭指针捕获。
     */
    override fun canCapturePointer(): Boolean = false
}
