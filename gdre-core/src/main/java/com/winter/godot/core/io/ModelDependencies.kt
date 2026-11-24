package com.winter.godot.core.io

import com.winter.godot.core.model.CoreFailure
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/**
 * 追踪模型文件引用的本地素材。
 * @author Tingdong
 * @date 2026-11-24
 */
object ModelDependencies {
    const val MAX_METADATA_SIZE = 8L * 1024 * 1024

    /**
     * 判断是否需要解析模型依赖。
     * 使用示例：ModelDependencies.supports("models/robot.gltf")。
     * @param path 项目资源路径。
     * @return 是否为 glTF 元数据。
     */
    fun supports(path: String): Boolean = path.substringAfterLast('.', "").equals("gltf", true)

    /**
     * 读取模型引用的缓冲区和图片路径。
     * 使用示例：ModelDependencies.paths("models/robot.gltf", file)。
     * @param path 模型资源路径。
     * @param file 已提取的模型文件。
     * @return 规范化的本地依赖路径。
     * @throws CoreFailure 元数据超限或依赖路径越界。
     */
    fun paths(path: String, file: File): Set<String> {
        if (!supports(path)) return emptySet()
        if (file.length() > MAX_METADATA_SIZE) throw CoreFailure("SIZE_LIMIT", "Model metadata exceeds limit: $path")
        val model = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptySet()
        val result = linkedSetOf<String>()
        for (key in listOf("buffers", "images")) {
            val entries = model.optJSONArray(key) ?: continue
            for (i in 0 until entries.length()) {
                val uri = entries.optJSONObject(i)?.optString("uri").orEmpty()
                resolve(path, uri)?.let(result::add)
            }
        }
        return result
    }

    /**
     * 为选中的模型补齐已有依赖。
     * 使用示例：ModelDependencies.collect(files, selection::includes, check)。
     * @param files 原始与恢复产物集合。
     * @param selected 判断所属模型是否被选中。
     * @param check 取消检查。
     * @return 集合内需要随模型输出的路径。
     */
    fun collect(files: Map<String, File>, selected: (String) -> Boolean, check: () -> Unit): Set<String> {
        val result = linkedSetOf<String>()
        for ((path, file) in files) {
            check()
            if (supports(path) && selected(path)) result += paths(path, file).filter { it in files }
        }
        return result
    }

    /**
     * 将相对 URI 限定在项目资源根目录内。
     * @param owner 模型路径。
     * @param uri 模型引用的 URI。
     * @return 本地依赖路径，内嵌数据及远程地址为空。
     * @throws CoreFailure 依赖指向项目外部。
     */
    private fun resolve(owner: String, uri: String): String? {
        if (uri.isEmpty() || ':' in uri || uri.startsWith("//")) return null
        val decoded = URLDecoder.decode(uri.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8").replace('\\', '/')
        if (decoded.isEmpty()) return null
        if (decoded.startsWith('/')) throw CoreFailure("UNSAFE_PATH", "Absolute model dependency: $uri")
        val parts = SafeFiles.relative(owner).substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (part in decoded.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) throw CoreFailure("UNSAFE_PATH", "Model dependency escaped project: $uri") else parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
        return SafeFiles.relative(parts.joinToString("/"))
    }
}
