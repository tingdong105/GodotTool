package com.winter.godot.core.model

import com.winter.godot.core.io.SafeFiles

/**
 * 按 Godot 资源路径选择恢复内容，不改变原项目目录。
 * @author Tingdong
 * @date 2026-11-21
 */
data class RecoverySelection(
    val scripts: Boolean = true,
    val scenes: Boolean = true,
    val assets: Boolean = true,
    val plugins: Boolean = true,
    val config: Boolean = true,
    val keepRaw: Boolean = false,
) {
    val hasContent: Boolean get() = scripts || scenes || assets || plugins || config

    /**
     * 判断文件是否属于用户选择的原项目内容。
     * 使用示例：selection.includes("res://scripts/Main.gdc")。
     * @param path Godot 资源路径。
     * @return 是否输出到源码工程。
     */
    fun includes(path: String): Boolean {
        val name = SafeFiles.relative(path)
        if (name.startsWith(".godot/") || name.startsWith(".import/")) return false
        if (name.startsWith("addons/")) return plugins
        if (name.split('/').dropLast(1).any { it.substringAfterLast('.', "").lowercase() in modelPackageExtensions }) return assets
        if (name in setOf("project.godot", "project.binary", "engine.cfg", "export_presets.cfg")) return config
        if (name.endsWith(".remap") || name.endsWith(".import") || name.endsWith(".uid")) return includes(name.substringBeforeLast('.'))
        return when (name.substringAfterLast('.', "").lowercase()) {
            "gd", "gdc", "gde", "cs", "gdshader", "gdshaderinc", "shader", "shaderinc" -> scripts
            "tscn", "scn", "tres", "res" -> scenes
            in assetExtensions -> assets
            else -> config
        }
    }

    /**
     * 为解析器保留版本配置和对应的导入依赖。
     * 使用示例：selection.needsForRecovery("project.binary")。
     * @param path 原始资源路径。
     * @return 是否需要解密到临时工作区。
     */
    fun needsForRecovery(path: String): Boolean {
        val name = SafeFiles.relative(path)
        if (includes(name)) return true
        if (name in setOf("project.godot", "project.binary", "engine.cfg", ".godot/global_script_class_cache.cfg", ".godot/uid_cache.bin")) return true
        // 精简恢复通过已选 import/remap 追踪编译缓存，完整素材恢复保留全部缓存候选。
        return assets && (name.startsWith(".godot/exported/") || name.startsWith(".godot/imported/") || name.startsWith(".import/"))
    }

    companion object {
        private val modelPackageExtensions = setOf("mlpackage", "mlmodelc")

        private val assetExtensions = setOf(
            "png", "jpg", "jpeg", "webp", "svg", "svgz", "bmp", "gif", "ico", "tga", "exr", "hdr", "dds", "ktx", "ktx2", "basis",
            "wav", "ogg", "mp3", "flac", "aac", "m4a", "opus", "wma", "aiff", "aif",
            "mp4", "webm", "ogv", "mov", "avi", "mkv", "mpeg", "mpg", "m4v",
            "ttf", "otf", "woff", "woff2", "fnt", "font", "fontdata",
            "glb", "gltf", "obj", "fbx", "dae", "blend", "3ds", "stl", "ply",
            "onnx", "tflite", "mlmodel", "mlpackage", "ncnnpack",
            "ctex", "stex", "tex", "atlastex", "sample", "oggstr", "mp3str",
        )

        /**
         * 仅恢复项目脚本，适合静态分析业务逻辑。
         * 使用示例：RecoverySelection.scriptsOnly()。
         * @return 项目脚本预设。
         */
        fun scriptsOnly(): RecoverySelection = RecoverySelection(scenes = false, assets = false, plugins = false, config = false)
    }
}
