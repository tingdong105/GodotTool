package com.winter.godot.tool.feature.files

import com.winter.godot.core.model.CoreFailure
import java.io.File

/**
 * 文件选择器中的只读条目。
 * @author Tingdong
 * @date 2026-11-21
 */
data class BrowserFile(val name: String, val path: String, val directory: Boolean, val size: Long)

/**
 * 约束共享存储范围的文件浏览器。
 * @author Tingdong
 * @date 2026-11-21
 */
class FileBrowser(private val root: File) {
    /**
     * 枚举目录，文件仅保留支持的资源包。
     * 使用示例：browser.list(root.absolutePath)。
     * @param path 目录绝对路径。
     * @return 文件夹优先的稳定条目列表。
     */
    fun list(path: String): List<BrowserFile> {
        val folder = checked(path)
        val children = folder.listFiles() ?: throw CoreFailure("READ_PERMISSION", folder.absolutePath)
        return children.asSequence().filter { !it.name.startsWith('.') && (it.isDirectory || it.extension.lowercase() in setOf("apk", "pck", "zip")) }
            .filter { it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) }
            .map { BrowserFile(it.name, it.absolutePath, it.isDirectory, if (it.isFile) it.length() else 0) }
            .sortedWith(compareBy<BrowserFile> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }).toList()
    }

    /**
     * 校验浏览目录边界。
     * @param path 外部目录路径。
     * @return 根目录以内的规范目录。
     */
    fun checked(path: String): File = File(path).canonicalFile.also {
        if (!it.toPath().startsWith(root.canonicalFile.toPath()) || !it.isDirectory) throw CoreFailure("UNSAFE_PATH", "Directory outside shared storage")
    }
}
