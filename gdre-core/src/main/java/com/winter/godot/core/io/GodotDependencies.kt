package com.winter.godot.core.io

/**
 * 从 Godot 导入元数据中读取资源路径，不执行脚本。
 * @author Tingdong
 * @date 2026-11-21
 */
object GodotDependencies {
    /**
     * 解析双引号中的 res 路径，支持空格、反斜杠和 Unicode 转义。
     * 使用示例：GodotDependencies.paths(metadataText)。
     * @param text import 或 remap 文本。
     * @return 已校验的资源相对路径。
     */
    fun paths(text: String): Set<String> {
        val paths = linkedSetOf<String>()
        var position = 0
        while (position < text.length) {
            if (text[position++] != '"') continue
            val value = StringBuilder()
            var closed = false
            while (position < text.length) {
                val character = text[position++]
                if (character == '"') { closed = true; break }
                if (character != '\\' || position >= text.length) { value.append(character); continue }
                when (val escape = text[position++]) {
                    'n' -> value.append('\n')
                    'r' -> value.append('\r')
                    't' -> value.append('\t')
                    'u' -> {
                        if (position + 4 > text.length) break
                        val code = text.substring(position, position + 4).toIntOrNull(16) ?: break
                        value.append(code.toChar()); position += 4
                    }
                    else -> value.append(escape)
                }
                if (value.length > 16384) break
            }
            if (closed && value.startsWith("res://")) paths += SafeFiles.relative(value.toString())
        }
        return paths
    }
}
