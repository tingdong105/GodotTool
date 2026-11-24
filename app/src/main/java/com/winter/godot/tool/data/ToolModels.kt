package com.winter.godot.tool.data

import com.winter.godot.core.model.RecoverySelection
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用主题模式。
 * @author Tingdong
 * @date 2026-11-21
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 可持久化的恢复偏好。
 * @author Tingdong
 * @date 2026-11-21
 */
data class ToolSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val scripts: Boolean = true, val scenes: Boolean = false,
    val assets: Boolean = false, val plugins: Boolean = false, val config: Boolean = false, val keepRaw: Boolean = false) {
    val selection: RecoverySelection get() = RecoverySelection(scripts, scenes, assets, plugins, config, keepRaw)

    /**
     * 应用恢复内容预设并保留外观偏好。
     * @param value 新的恢复内容。
     * @return 更新后的设置。
     */
    fun withSelection(value: RecoverySelection): ToolSettings = copy(scripts = value.scripts, scenes = value.scenes,
        assets = value.assets, plugins = value.plugins, config = value.config, keepRaw = value.keepRaw)
}

/**
 * 任务终态与运行状态。
 * @author Tingdong
 * @date 2026-11-21
 */
enum class TaskStatus { RUNNING, SUCCEEDED, PARTIAL, FAILED, CANCELED, INTERRUPTED }

/**
 * 内存中有界保留的任务日志行。
 * @author Tingdong
 * @date 2026-11-24
 */
data class TaskLogLine(val stage: String, val text: String, val level: TaskLogLevel = TaskLogLevel.INFO)

/**
 * 面向用户的日志语义。
 * @author Tingdong
 * @date 2026-11-24
 */
enum class TaskLogLevel { INFO, ACCENT, SUCCESS, WARNING, ERROR }

/**
 * MMKV 中的轻量任务摘要。
 * @author Tingdong
 * @date 2026-11-21
 */
data class TaskRecord(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val operation: String = "recover",
    val status: TaskStatus = TaskStatus.RUNNING,
    val stage: String = "import",
    val path: String = "",
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val files: Int = 0,
    val totalFiles: Int = 0,
    val errorCode: String = "",
    val errorDetail: String = "",
    val summary: String = "{}",
    val overallProgress: Float = 0f,
    val selection: RecoverySelection? = null,
    val acknowledged: Boolean = false,
) {
    /**
     * 将摘要序列化为可迁移的 JSON。
     */
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("createdAt", createdAt).put("operation", operation)
        .put("status", status.name).put("stage", stage).put("path", path).put("bytes", bytes).put("totalBytes", totalBytes)
        .put("files", files).put("totalFiles", totalFiles).put("errorCode", errorCode).put("errorDetail", errorDetail).put("summary", summary)
        .put("overallProgress", overallProgress).put("selection", selection?.toJson()).put("acknowledged", acknowledged)

    companion object {
        /**
         * 校验并读取历史任务。
         * @param json 存储中的摘要。
         * @return 有效任务。
         */
        fun fromJson(json: JSONObject): TaskRecord {
            val id = json.getString("id")
            require(id.matches(Regex("[0-9a-f-]{36}")))
            val status = TaskStatus.valueOf(json.getString("status"))
            val summary = json.optString("summary", "{}")
            val selection = json.optJSONObject("selection")
                ?: runCatching { JSONObject(summary).optJSONObject("selection") }.getOrNull()
            return TaskRecord(id, json.optString("name").take(300), json.getLong("createdAt"), json.optString("operation", "recover"),
                status, json.optString("stage", "import"), json.optString("path"),
                json.optLong("bytes").coerceAtLeast(0), json.optLong("totalBytes").coerceAtLeast(0), json.optInt("files").coerceAtLeast(0),
                json.optInt("totalFiles").coerceAtLeast(0), json.optString("errorCode"), json.optString("errorDetail"), summary,
                json.optDouble("overallProgress", if (json.optString("stage") == "done") 1.0 else 0.0).toFloat().coerceIn(0f, 1f),
                selection?.let(::recoverySelectionFromJson), json.optBoolean("acknowledged", status != TaskStatus.RUNNING))
        }
    }
}

/**
 * 可浏览的工作区文件。
 * @author Tingdong
 * @date 2026-11-21
 */
data class WorkspaceEntry(val path: String, val size: Long, val source: String = "") {
    val extension: String get() = path.substringAfterLast('.', "").lowercase()
    val category: String get() = when (extension) {
        "gd", "gdc", "gde" -> "scripts"
        "tscn", "scn", "tres", "res", "godot", "binary" -> "scenes"
        "png", "jpg", "jpeg", "webp", "bmp" -> "images"
        else -> "other"
    }
}

/**
 * 恢复输入与选项，仅在请求期间携带密钥。
 * @author Tingdong
 * @date 2026-11-21
 */
data class ImportDraft(val name: String, val sources: List<JSONObject>, val selection: RecoverySelection? = null) {
    /**
     * 生成发送给后台服务的任务。
     * @param id 任务标识。
     * @param settings 恢复选项。
     * @param key 用户临时提供的密钥。
     * @return 内存请求。
     * @throws IllegalArgumentException 输入不是单个文件。
     */
    fun request(id: String, settings: ToolSettings, key: String): JSONObject {
        require(sources.size == 1) { "Exactly one input file is required" }
        val selected = selection ?: settings.selection
        return JSONObject().put("id", id).put("name", name)
            .put("operation", "recover").put("sources", JSONArray(sources)).put("scripts", selected.scripts || selected.plugins).put("scenes", selected.scenes).put("key", key)
            .put("selection", selected.toJson())
    }
}

/**
 * 保存完整的六项任务配置。
 * 使用示例：selection.toJson()。
 * @return 不随应用设置变化的配置 JSON。
 */
internal fun RecoverySelection.toJson(): JSONObject = JSONObject().put("scripts", scripts).put("scenes", scenes)
    .put("assets", assets).put("plugins", plugins).put("config", config).put("keepRaw", keepRaw)

/**
 * 读取请求或历史任务中的实际配置。
 * 使用示例：recoverySelectionFromJson(request.getJSONObject("selection"))。
 * @param json 六项配置，缺省值兼容旧请求。
 * @return 任务配置快照。
 */
internal fun recoverySelectionFromJson(json: JSONObject): RecoverySelection = RecoverySelection(
    json.optBoolean("scripts", true), json.optBoolean("scenes", true), json.optBoolean("assets", true),
    json.optBoolean("plugins", true), json.optBoolean("config", true), json.optBoolean("keepRaw", false))
