package com.winter.godot.core.io

import com.winter.godot.core.model.CoreFailure
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 约束外部资源的路径与流式读写。
 * @author Tingdong
 * @date 2026-11-21
 */
object SafeFiles {
    /**
     * 规范化资源路径并拒绝目录逃逸。
     * 使用示例：SafeFiles.relative("res://scripts/Main.gd")。
     * @param path 外部资源路径。
     * @return 工作区相对路径。
     * @throws CoreFailure 路径不合法。
     */
    fun relative(path: String): String {
        val value = path.removePrefix("res://").replace('\\', '/')
        if (value.isEmpty() || value.length > 16384 || value.startsWith('/') || ':' in value || '\u0000' in value ||
            value.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw CoreFailure("UNSAFE_PATH", "Unsafe resource path: ${path.take(160)}")
        }
        return value
    }

    /**
     * 将资源路径限定在给定根目录内。
     * 使用示例：SafeFiles.resolve(workspace, entry.path)。
     * @param root 工作区根目录。
     * @param path 资源路径。
     * @return 根目录内的规范文件。
     * @throws CoreFailure 检测到目录逃逸。
     */
    fun resolve(root: File, path: String): File {
        val base = root.canonicalFile
        val target = File(base, relative(path)).canonicalFile
        if (!target.toPath().startsWith(base.toPath()) || target == base) {
            throw CoreFailure("UNSAFE_PATH", "Resource path escapes workspace")
        }
        return target
    }

    /**
     * 限定大小地复制数据并响应取消。
     * 使用示例：SafeFiles.copy(input, output, limit, check, progress)。
     * @param input 输入流。
     * @param output 输出流。
     * @param limit 最大字节数。
     * @param check 取消检查。
     * @param progress 累计字节回调。
     * @return 已复制字节数。
     * @throws CoreFailure 数据超出限制。
     */
    fun copy(input: InputStream, output: OutputStream, limit: Long, check: () -> Unit = {}, progress: (Long) -> Unit = {}): Long {
        val buffer = ByteArray(128 * 1024)
        var count = 0L
        while (true) {
            check()
            val size = input.read(buffer)
            if (size < 0) break
            if (size == 0) continue
            if (count > limit - size) throw CoreFailure("SIZE_LIMIT", "Input exceeds byte limit")
            output.write(buffer, 0, size)
            count += size
            progress(count)
        }
        return count
    }

    /**
     * 复制写入中日志的固定长度快照。
     * 使用示例：SafeFiles.snapshot(input, output, file.length(), check)。
     * @param input 日志流。
     * @param output 快照输出流。
     * @param size 快照长度。
     * @param check 取消检查。
     * @param progress 已复制字节。
     * @return 快照字节数。
     * @throws CoreFailure 日志被截断。
     */
    fun snapshot(input: InputStream, output: OutputStream, size: Long, check: () -> Unit = {}, progress: (Long) -> Unit = {}): Long {
        if (size < 0) throw CoreFailure("SIZE_CHANGED", "Invalid snapshot length")
        val buffer = ByteArray(128 * 1024)
        var done = 0L
        while (done < size) {
            check()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - done).toInt())
            if (count < 0) throw CoreFailure("SIZE_CHANGED", "Log truncated while saving snapshot")
            if (count == 0) continue
            output.write(buffer, 0, count)
            done += count
            progress(done)
        }
        return done
    }

    /**
     * 计算流的 SHA-256。
     * 使用示例：SafeFiles.sha256(file.inputStream())。
     * @param input 调用方负责关闭的输入流。
     * @param check 取消检查。
     * @return 小写十六进制摘要。
     */
    fun sha256(input: InputStream, check: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return hex(digest.digest())
    }

    /**
     * 将字节编码为小写十六进制。
     * 使用示例：SafeFiles.hex(md5.digest())。
     * @param bytes 原始字节。
     * @return 十六进制字符串。
     */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
