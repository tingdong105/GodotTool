package com.winter.godot.core.engine

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotHost
import org.godotengine.godot.GodotLib
import org.godotengine.godot.plugin.GodotPluginRegistry
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 在隔离进程的离屏线程驱动恢复核心并持久化启动阶段。
 * @author Tingdong
 * @date 2026-11-21
 */
class GdreRunner(private val context: Context) {
    /**
     * 启动一次恢复任务，每个进程仅调用一次。
     * @param request 内存任务参数。
     * @param canceled 共享取消标志。
     * @param event 结构化结果回调。
     * @param finished 线程清理回调。
     */
    fun start(request: String, canceled: AtomicBoolean, event: (String) -> Unit, finished: () -> Unit) {
        val output = File(JSONObject(request).getString("output"))
        val trace = EngineTrace(output)
        val enteredScript = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
        val lastStage = AtomicReference("")
        val thread = HandlerThread("GdreOffscreen").apply { start() }
        val handler = Handler(thread.looper)
        val main = Handler(Looper.getMainLooper())
        val timer = Executors.newSingleThreadScheduledExecutor { action -> Thread(action, "GdreStartupWatchdog").apply { isDaemon = true } }
        val dispatch: (String) -> Unit = dispatch@{ payload ->
            val value = JSONObject(payload)
            val type = value.optString("type")
            if (type == "result" || type == "error") {
                if (!terminal.compareAndSet(false, true)) return@dispatch
            } else if (terminal.get()) return@dispatch
            if (enteredScript.compareAndSet(false, true)) trace.record("first_event:$type")
            if (type == "progress") {
                val stage = value.optString("stage").take(64)
                if (lastStage.getAndSet(stage) != stage) trace.record("script_stage:$stage")
            }
            event(payload)
        }
        trace.record("runner_created sdk=${android.os.Build.VERSION.SDK_INT}")
        timer.schedule({
            if (!enteredScript.get() && !terminal.get() && !canceled.get()) {
                trace.record("startup_timeout")
                dispatch(JSONObject().put("type", "error").put("code", "ENGINE_START_TIMEOUT")
                    .put("detail", EngineTrace.diagnostics(output)).toString())
                finished()
            }
        }, 60, TimeUnit.SECONDS)
        handler.post {
            val graphics = OffscreenGlContext()
            try {
                trace.record("check_assets")
                context.assets.open("project.godot").use { check(it.read() >= 0) }
                trace.record("create_engine")
                val engine = Godot.getInstance(context)
                val terminated = AtomicBoolean(false)
                val host = object : GodotHost {
                    /**
                     * 服务不持有页面。
                     */
                    override fun getActivity(): Activity? = null
                    /**
                     * 返回当前引擎。
                     */
                    override fun getGodot(): Godot = engine
                    /**
                     * 将 Android 界面回调调度到主线程。
                     */
                    override fun runOnHostThread(action: Runnable) {
                        if (Looper.myLooper() == main.looper) action.run() else main.post(action)
                    }
                    /**
                     * 记录原生退出，由服务回收进程。
                     */
                    override fun onGodotForceQuit(instance: Godot) { terminated.set(true) }
                }
                trace.record("create_render_view")
                val createView = FutureTask { OffscreenRenderView(context, engine.godotInputHandler, handler) }
                main.post(createView)
                engine.renderView = createView.get(10, TimeUnit.SECONDS)
                val bridge = ToolBridge(engine, request, canceled, dispatch)
                trace.record("create_egl")
                graphics.open()
                val args = listOf("--script", "res://gdre/driver.gdc", "--rendering-driver", "opengl3",
                    "--rendering-method", "gl_compatibility", "--audio-driver", "Dummy", "--disable-render-loop",
                    "--disable_godot_splash", "--max-fps", "30", "--log-file", File(output, "engine.log").absolutePath)
                trace.record("native_setup")
                check(engine.initEngine(host, args, setOf(bridge))) { "Godot initialization failed" }
                trace.record("native_surface")
                GodotLib.newcontext(null)
                GodotLib.resize(null, 1, 1)
                trace.record("register_plugins")
                for (plugin in GodotPluginRegistry.getPluginRegistry().allPlugins) plugin.onRegisterPluginWithGodotNative()
                var steps = 0
                while (!bridge.completed.get() && !terminal.get() && !canceled.get() && !terminated.get()) {
                    // 前三帧分别初始化后端、启动标志和主循环。
                    if (steps < 3) trace.record("native_step_$steps:enter")
                    GodotLib.step()
                    if (steps < 3) trace.record("native_step_$steps:exit")
                    steps++
                    Thread.sleep(8)
                }
                if (!terminal.get() && !bridge.completed.get() && !canceled.get()) {
                    dispatch(JSONObject().put("type", "error").put("code", "ENGINE_STOPPED")
                        .put("detail", EngineTrace.diagnostics(output)).toString())
                }
            } catch (error: Throwable) {
                val detail = error.javaClass.simpleName + ": " + error.message.orEmpty()
                runCatching { trace.record("java_failure:$detail"); File(output, "bootstrap.log").writeText(detail) }
                dispatch(JSONObject().put("type", "error").put("code", "ENGINE_INIT")
                    .put("detail", "$detail\n${EngineTrace.diagnostics(output)}").toString())
            } finally {
                timer.shutdownNow()
                // 原生全局状态由整个工作进程退出统一释放。
                runCatching { graphics.close() }
                thread.quitSafely()
                finished()
            }
        }
    }
}
