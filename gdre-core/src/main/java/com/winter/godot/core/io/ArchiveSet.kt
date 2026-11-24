package com.winter.godot.core.io

import com.winter.godot.core.model.CoreFailure
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 汇总基础 APK 与分包的 ZIP 条目。
 * @author Tingdong
 * @date 2026-11-21
 */
class ArchiveSet(files: List<File>) : Closeable {
    private val archives = mutableListOf<ZipFile>()
    private val entries = linkedMapOf<String, Pair<ZipFile, ZipEntry>>()

    init {
        try {
            for (file in files) {
                val zip = ZipFile(file)
                archives += zip
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    if (entries.size >= 200000) throw CoreFailure("ENTRY_LIMIT", "ZIP entry count exceeds 200000")
                    SafeFiles.relative(entry.name)
                    val previous = entries[entry.name]
                    if (previous?.first === zip) throw CoreFailure("DUPLICATE_PATH", entry.name)
                    if (previous != null && (previous.second.crc != entry.crc || previous.second.size != entry.size)) {
                        // APK 的签名和 Manifest 各自独立，资源冲突才会影响解包。
                        if (entry.name.startsWith("assets/")) throw CoreFailure("SPLIT_CONFLICT", entry.name)
                    }
                    if (previous == null) entries[entry.name] = zip to entry
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /**
     * 判断集合中是否存在指定条目。
     * @param path ZIP 内部路径。
     * @return 条目是否存在。
     */
    fun contains(path: String): Boolean = entries.containsKey(path)

    /**
     * 打开集合中的资源流。
     * @param path ZIP 内部路径。
     * @return 调用方负责关闭的输入流。
     * @throws CoreFailure 缺少分包资源。
     */
    fun open(path: String): InputStream {
        val (zip, entry) = entries[path] ?: throw CoreFailure("MISSING_ENTRY", path)
        return zip.getInputStream(entry)
    }

    /**
     * 获取资源的解压后大小。
     * @param path ZIP 内部路径。
     * @return 解压大小。
     */
    fun size(path: String): Long = entries[path]?.second?.size ?: throw CoreFailure("MISSING_ENTRY", path)

    /**
     * 关闭全部归档句柄。
     */
    override fun close() {
        archives.forEach { runCatching { it.close() } }
        archives.clear()
    }
}
