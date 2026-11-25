package com.winter.godot.tool.task

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.winter.godot.core.engine.EngineTrace
import com.winter.godot.tool.data.ProjectAssembler
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 在主进程读取已退出核心的系统原因和原生日志。
 * @author Tingdong
 * @date 2026-11-21
 */
object EngineExitDiagnostics {
    /**
     * 查询当前任务对应核心的退出时间，旧系统或记录缺失时返回空。
     * 使用示例：EngineExitDiagnostics.exitedAt(context, task.createdAt, enginePid)。
     * @param context 主应用环境。
     * @param since 当前任务开始时间。
     * @param pid 已知核心进程标识，零表示按当前串行任务匹配。
     * @return 系统退出时间，单位为墙上时钟毫秒。
     */
    fun exitedAt(context: Context, since: Long, pid: Int): Long? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val now = System.currentTimeMillis()
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, pid.coerceAtLeast(0), 16).firstOrNull {
                    it.processName == "${context.packageName}:engine" && it.timestamp in since..now && (pid <= 0 || it.pid == pid)
                }?.timestamp
        }.getOrNull()
    }

    /**
     * 查询本次引擎退出，不混入其他任务或进程的记录。
     * @param context 主应用环境。
     * @param directory 公共结果目录。
     * @param since 任务恢复阶段开始时间。
     * @param pid 已知的核心进程标识，旧记录使用零。
     * @return 系统原因和有界日志。
     */
    fun read(context: Context, directory: File, since: Long, pid: Int): String {
        var processId = pid
        val reason = if (pid <= 0) "Engine PID was not recorded by the previous build; retry to capture startup diagnostics"
        else if (Build.VERSION.SDK_INT >= 30) runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
            var exit: ApplicationExitInfo? = null
            repeat(4) { attempt ->
                if (exit == null) {
                    exit = manager.getHistoricalProcessExitReasons(context.packageName, pid, 16).firstOrNull {
                        it.processName == "${context.packageName}:engine" && it.timestamp >= since && (pid == 0 || it.pid == pid)
                    }
                    if (exit == null && attempt < 3) Thread.sleep(250)
                }
            }
            val record = exit
            if (record == null) "System exit information unavailable"
            else {
                processId = record.pid
                "System exit: reason=${record.reason}, status=${record.status}, description=${record.description.orEmpty().take(2000)}"
            }
        }.getOrElse { "System exit information: ${it.javaClass.simpleName}" }
        else "Android ${Build.VERSION.SDK_INT}: system exit history unavailable"
        if (processId > 0) captureLogcat(directory, processId)
        return "$reason\n\n${EngineTrace.diagnostics(directory)}".take(20000)
    }

    /**
     * 截取当前应用指定进程的日志，进程读取结束后回收 logcat。
     * @param directory 结果目录。
     * @param pid 已退出核心的标识。
     */
    private fun captureLogcat(directory: File, pid: Int) {
        runCatching {
            val log = ProjectAssembler.reportFile(directory, "engine-logcat.log")
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "100", "--pid=$pid")
                .redirectErrorStream(true).redirectOutput(log).start()
            try { process.waitFor(2, TimeUnit.SECONDS) } finally { process.destroy(); if (process.isAlive) process.destroyForcibly() }
        }
    }
}
