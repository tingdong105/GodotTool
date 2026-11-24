package com.winter.godot.core.model

/**
 * 可诊断的解包失败。
 * @author Tingdong
 * @date 2026-11-21
 */
class CoreFailure(val code: String, detail: String) : Exception(detail)

/**
 * 已校验的原始资源条目。
 * @author Tingdong
 * @date 2026-11-21
 */
data class ResourceEntry(val path: String, val size: Long, val md5: String, val encrypted: Boolean)

/**
 * 流式任务进度，未知总量使用零。
 * @author Tingdong
 * @date 2026-11-21
 */
data class CoreProgress(val stage: String, val path: String = "", val bytes: Long = 0, val totalBytes: Long = 0, val files: Int = 0, val totalFiles: Int = 0)
