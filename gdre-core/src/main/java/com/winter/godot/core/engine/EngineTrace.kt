package com.winter.godot.core.engine

import java.io.File
import java.io.RandomAccessFile

/**
 * 在进入原生调用前保存启动阶段，进程崩溃后仍可追踪。
 * @author Tingdong
 * @date 2026-11-21
 */
class EngineTrace(private val directory: File) {
    /**
     * 同步追加阶段记录，不写入请求或密钥。
     * 使用示例：trace.record("native_setup")。
     * @param stage 阶段名称。
     */
    @Synchronized
    fun record(stage: String) {
        runCatching {
            directory.mkdirs()
            File(directory, "engine-startup.log").appendText("${System.currentTimeMillis()} $stage\n")
        }
    }

    companion object {
        /**
         * 有界读取启动日志和核心日志末尾。
         * 使用示例：EngineTrace.diagnostics(output)。
         * @param directory 结果目录。
         * @return 可直接展示的诊断文本。
         */
        fun diagnostics(directory: File): String = listOf("engine-startup.log", "bootstrap.log", "engine.log", "engine-logcat.log").mapNotNull { name ->
            runCatching {
                val file = File(directory, "reports/$name").takeIf { it.isFile } ?: File(directory, name)
                if (!file.isFile) return@runCatching null
                val tail = RandomAccessFile(file, "r").use { input ->
                    val size = minOf(input.length(), 8192).toInt()
                    input.seek(input.length() - size)
                    ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
                }
                "$name:\n$tail"
            }.getOrNull()
        }.joinToString("\n\n")
    }
}
