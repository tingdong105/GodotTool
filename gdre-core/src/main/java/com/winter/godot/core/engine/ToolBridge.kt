package com.winter.godot.core.engine

import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.UsedByGodot
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 为工具自身的恢复脚本提供任务与事件桥接。
 * @author Tingdong
 * @date 2026-11-21
 */
class ToolBridge(
    godot: Godot,
    private val request: String,
    private val canceled: AtomicBoolean,
    private val event: (String) -> Unit,
) : GodotPlugin(godot) {
    val completed = AtomicBoolean(false)

    /**
     * 返回固定插件名称。
     */
    override fun getPluginName(): String = "GodotToolBridge"

    /**
     * 返回内存中的任务参数。
     */
    @UsedByGodot
    fun getJobJson(): String = request

    /**
     * 返回当前任务的取消状态。
     */
    @UsedByGodot
    fun isCanceled(): Boolean = canceled.get()

    /**
     * 将核心事件交回宿主。
     * @param json 结构化事件，不包含密钥。
     */
    @UsedByGodot
    fun emitEvent(json: String) { event(json) }

    /**
     * 标记恢复脚本已经结束。
     */
    @UsedByGodot
    fun finish() { completed.set(true) }
}
