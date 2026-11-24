package com.winter.godot.tool.feature.files

import com.winter.godot.core.model.CoreFailure
import java.io.File

/**
 * 文件选择器中的只读条目。
 * @author Tingdong
 * @date 2026-11-21
 */
data class BrowserFile(val name: String, val path: String, val directory: Boolean, val size: Long, val modifiedAt: Long = 0)

/**
 * 文件浏览器的排序依据。
 * @author Tingdong
 * @date 2026-11-24
 */
enum class BrowserSort { NAME, MODIFIED, SIZE }

/**
 * 在文件夹优先的前提下排序，修改时间默认从新到旧。
 * 使用示例：sortBrowserFiles(files, BrowserSort.NAME, false)。
 * @param files 当前目录条目。
 * @param order 排序依据。
 * @param reversed 是否反转各组内部顺序。
 * @return 排序后的列表。
 */
fun sortBrowserFiles(files: List<BrowserFile>, order: BrowserSort, reversed: Boolean): List<BrowserFile> {
    val names = compareBy<BrowserFile, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.name }.thenBy { it.path }
    val entries = when (order) {
        BrowserSort.NAME -> names
        BrowserSort.MODIFIED -> compareByDescending<BrowserFile> { it.modifiedAt }.then(names)
        BrowserSort.SIZE -> compareByDescending<BrowserFile> { it.size }.then(names)
    }
    return files.sortedWith(compareBy<BrowserFile> { !it.directory }.then(if (reversed) entries.reversed() else entries))
}

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
     * @param extensions 当前用途允许选择的扩展名。
     * @return 待按界面选项排序的目录条目。
     */
    fun list(path: String, extensions: Set<String> = setOf("apk", "pck", "zip")): List<BrowserFile> {
        val folder = checked(path)
        val children = folder.listFiles() ?: throw CoreFailure("READ_PERMISSION", folder.absolutePath)
        return children.asSequence().filter { !it.name.startsWith('.') && (it.isDirectory || it.extension.lowercase() in extensions) }
            .filter { it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) }
            .map { BrowserFile(it.name, it.absolutePath, it.isDirectory, if (it.isFile) it.length() else 0, it.lastModified()) }
            .toList()
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
