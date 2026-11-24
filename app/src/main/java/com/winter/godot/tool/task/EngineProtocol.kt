package com.winter.godot.tool.task

/**
 * 主进程与可丢弃核心进程的消息协议。
 * @author Tingdong
 * @date 2026-11-21
 */
object EngineProtocol {
    const val START = 1
    const val SUBSCRIBE = 2
    const val EVENT = 4
    const val PAYLOAD = "payload"
    const val TASK_ID = "taskId"
}
