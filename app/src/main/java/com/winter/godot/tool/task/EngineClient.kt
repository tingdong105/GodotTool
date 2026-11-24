package com.winter.godot.tool.task

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * 维护应用级后台连接与任务重连。
 * @author Tingdong
 * @date 2026-11-21
 */
class EngineClient(private val context: Context, private val event: (JSONObject) -> Unit, private val disconnected: () -> Unit) {
    private var remote: Messenger? = null
    private var bound = false
    private var pending: String? = null
    private var cancelPending = false
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == EngineProtocol.EVENT) {
            runCatching { JSONObject(message.data.getString(EngineProtocol.PAYLOAD).orEmpty()) }.onSuccess(event)
        }
        true
    })
    private val connection = object : ServiceConnection {
        /**
         * 订阅快照并提交等待中的请求。
         */
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = Messenger(service)
            send(EngineProtocol.SUBSCRIBE)
            pending?.let { send(EngineProtocol.START, it); pending = null }
            if (cancelPending) { send(EngineProtocol.CANCEL); cancelPending = false }
        }
        /**
         * 核心进程退出后清理连接。
         */
        override fun onServiceDisconnected(name: ComponentName?) { release(); disconnected() }
        /**
         * 绑定失效时让仓库记录任务中断。
         */
        override fun onBindingDied(name: ComponentName?) { release(); disconnected() }
        /**
         * 空端点按照连接失败处理。
         */
        override fun onNullBinding(name: ComponentName?) { release(); disconnected() }
    }

    /**
     * 由用户操作启动前台任务。
     * @param request 内存任务参数。
     */
    fun start(request: JSONObject) {
        check(!bound && pending == null)
        pending = request.toString()
        try {
            val intent = Intent(context, EngineService::class.java)
            ContextCompat.startForegroundService(context, intent)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Engine service binding failed" }
        } catch (error: Throwable) { pending = null; release(); throw error }
    }

    /**
     * 尝试重连仍在运行的核心进程。
     * @return 是否存在可绑定的后台服务。
     */
    fun reconnect(): Boolean {
        if (bound) return true
        bound = context.bindService(Intent(context, EngineService::class.java), connection, 0)
        return bound
    }

    /**
     * 请求取消当前任务。
     */
    fun cancel() { if (remote == null) cancelPending = true else send(EngineProtocol.CANCEL) }

    /**
     * 向核心服务发送小型控制消息。
     * @param command 协议命令。
     * @param payload 可选 JSON 请求。
     */
    private fun send(command: Int, payload: String? = null) {
        try {
            remote?.send(Message.obtain(null, command).apply {
                replyTo = endpoint
                data = Bundle().apply { if (payload != null) putString(EngineProtocol.PAYLOAD, payload) }
            })
        } catch (error: android.os.RemoteException) { release(); disconnected() }
    }

    /**
     * 释放已经退出的服务连接。
     */
    private fun release() {
        remote = null
        pending = null
        cancelPending = false
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
    }
}
