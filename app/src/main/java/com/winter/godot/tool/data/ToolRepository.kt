package com.winter.godot.tool.data

import android.content.Context
import com.tencent.mmkv.MMKV
import com.winter.godot.core.model.RecoverySelection
import com.winter.godot.tool.task.EngineClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 主进程唯一的设置与任务状态持有者。
 * @author Tingdong
 * @date 2026-11-21
 */
class ToolRepository(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val kv = MMKV.mmkvWithID("godottool-settings-v1", MMKV.SINGLE_PROCESS_MODE)
    val workspaces = WorkspaceStore(context)
    private val mutableSettings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ToolSettings> = mutableSettings.asStateFlow()
    private val mutableTasks = MutableStateFlow(loadTasks())
    val tasks: StateFlow<List<TaskRecord>> = mutableTasks.asStateFlow()
    private val mutableActive = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = mutableActive.asStateFlow()
    private val mutableLogs = MutableStateFlow<List<TaskLogLine>>(emptyList())
    val logs: StateFlow<List<TaskLogLine>> = mutableLogs.asStateFlow()
    private var persistedAt = 0L
    private val client = EngineClient(context, ::onEvent, ::onDisconnected)

    init {
        val running = mutableTasks.value.firstOrNull { it.status == TaskStatus.RUNNING }
        if (running != null) {
            mutableActive.value = running.id
            if (!client.reconnect()) onDisconnected()
        }
    }

    /**
     * 保存新的恢复偏好。
     * @param value 完整偏好。
     */
    fun updateSettings(value: ToolSettings) {
        kv.encode("theme", value.theme.name)
        kv.encode("scripts", value.scripts)
        kv.encode("scenes", value.scenes)
        kv.encode("assets", value.assets)
        kv.encode("plugins", value.plugins)
        kv.encode("project-config", value.config)
        kv.encode("keep-raw", value.keepRaw)
        mutableSettings.value = value
    }

    /**
     * 创建一个恢复任务。
     * @param draft 导入来源。
     * @param key 临时密钥。
     * @return 新任务标识。
     */
    fun start(draft: ImportDraft, key: String): String {
        val id = UUID.randomUUID().toString()
        launchTask(TaskRecord(id, draft.name), draft.request(id, settings.value, key))
        return id
    }

    /**
     * 请求终止当前后台任务。
     */
    fun cancel() { client.cancel() }

    /**
     * 清理明确指定且未运行的工作区。
     * @param id 任务标识。
     */
    suspend fun delete(id: String) {
        check(id != active.value)
        withContext(Dispatchers.IO) { workspaces.delete(id) }
        mutableTasks.update { records -> records.filterNot { it.id == id } }
        persistTasks()
    }

    /**
     * 清理全部已结束任务和缓存。
     * @return 清理的工作区数量。
     */
    suspend fun clearFinished(): Int {
        check(active.value == null)
        val finished = tasks.value.filter { it.status != TaskStatus.RUNNING }
        finished.forEach { delete(it.id) }
        return finished.size
    }

    /**
     * 从本地输入记录生成重试来源。
     * @param id 原任务标识。
     * @return 可重试的导入草稿。
     */
    suspend fun retryDraft(id: String): ImportDraft = withContext(Dispatchers.IO) {
        val file = workspaces.file(id, "inputs.json")
        require(file.length() < 1024 * 1024)
        val array = JSONArray(file.readText())
        val sources = List(array.length()) { i ->
            val source = array.getJSONObject(i)
            val input = workspaces.file(id, source.getString("local"))
            JSONObject().put("file", input.absolutePath).put("name", source.getString("name"))
        }
        val options = java.io.File(workspaces.directory(id), "request-options.json")
        val selection = if (options.isFile && options.length() in 1..8192) JSONObject(options.readText()).let {
            RecoverySelection(it.optBoolean("scripts", true), it.optBoolean("scenes", true), it.optBoolean("assets", true),
                it.optBoolean("plugins", true), it.optBoolean("config", true), it.optBoolean("keepRaw", false))
        } else null
        ImportDraft(tasks.value.first { it.id == id }.name, sources, selection)
    }

    /**
     * 注册摘要后发送任务，重复触发由活动标识互斥。
     * @param record 新任务摘要。
     * @param request 仅内存使用的请求。
     */
    private fun launchTask(record: TaskRecord, request: JSONObject) {
        check(active.value == null) { "Task already running" }
        mutableActive.value = record.id
        mutableLogs.value = emptyList()
        mutableTasks.update { listOf(record) + it }
        persistTasks()
        try { client.start(request) }
        catch (error: Throwable) {
            mutableActive.value = null
            replace(record.copy(status = TaskStatus.FAILED, errorCode = "SERVICE_START", errorDetail = error.message.orEmpty()))
            persistTasks()
        }
    }

    /**
     * 合并核心事件并在完成后建立文件索引。
     * @param event 后台事件。
     */
    private fun onEvent(event: JSONObject) {
        val id = event.optString("id")
        val current = tasks.value.firstOrNull { it.id == id } ?: return
        if (current.status != TaskStatus.RUNNING) return
        when (event.optString("type")) {
            "progress" -> {
                val line = TaskLogLine(event.optString("stage", current.stage), event.optString("path"))
                if (mutableLogs.value.lastOrNull() != line) mutableLogs.update { (it + line).takeLast(300) }
                replace(current.copy(stage = event.optString("stage", current.stage), path = event.optString("path"), bytes = event.optLong("bytes"),
                    totalBytes = event.optLong("totalBytes"), files = event.optInt("files"), totalFiles = event.optInt("totalFiles")))
                if (System.currentTimeMillis() - persistedAt > 1500) persistTasks()
            }
            "log" -> {
                val lines = event.optJSONArray("lines") ?: return
                val next = List(minOf(lines.length(), 20)) { TaskLogLine(current.stage, lines.optString(it).take(1000)) }
                mutableLogs.update { (it + next).takeLast(300) }
            }
            "result" -> {
                val summary = event.optJSONObject("summary") ?: JSONObject()
                val partial = summary.has("warning") || summary.optInt("failedScripts") > 0 || summary.optInt("failedConversions") > 0
                // 先保存完成事件，索引可在下次打开工作区时重建。
                replace(current.copy(status = if (partial) TaskStatus.PARTIAL else TaskStatus.SUCCEEDED, stage = "done", summary = summary.toString(), path = ""))
                persistTasks()
                if (current.operation == "recover") scope.launch {
                    runCatching { withContext(Dispatchers.IO) { workspaces.index(id) } }.onFailure { error ->
                        val completed = tasks.value.firstOrNull { it.id == id } ?: return@onFailure
                        replace(completed.copy(status = TaskStatus.PARTIAL, errorCode = "INDEX_FAILED", errorDetail = error.message.orEmpty()))
                        persistTasks()
                    }
                }
            }
            "error" -> {
                val code = event.optString("code", "IO_FAILED")
                val summary = event.optJSONObject("summary")
                replace(current.copy(status = when {
                    code == "CANCELED" -> TaskStatus.CANCELED
                    summary != null && summary.optInt("resources") > 0 -> TaskStatus.PARTIAL
                    else -> TaskStatus.FAILED
                }, summary = summary?.toString() ?: current.summary, errorCode = code, errorDetail = event.optString("detail").take(3000)))
                persistTasks()
            }
        }
    }

    /**
     * 核心进程退出后读取已落盘结果或记录中断。
     */
    private fun onDisconnected() {
        val id = active.value ?: return
        scope.launch {
            val since = tasks.value.firstOrNull { it.id == id }?.createdAt ?: System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { runCatching { workspaces.reconcileInterrupted(id, since) }.getOrElse { workspaces.result(id) } }
            if (result != null) onEvent(result)
            val current = tasks.value.firstOrNull { it.id == id }
            if (current?.status == TaskStatus.RUNNING) {
                replace(current.copy(status = TaskStatus.INTERRUPTED, errorCode = "ENGINE_EXIT"))
                persistTasks()
            }
            mutableActive.value = null
        }
    }

    /**
     * 替换列表中的单个任务摘要。
     * @param value 更新后的任务。
     */
    private fun replace(value: TaskRecord) { mutableTasks.update { records -> records.map { if (it.id == value.id) value else it } } }

    /**
     * 保存带版本号的轻量任务索引。
     */
    private fun persistTasks() {
        val records = JSONArray()
        tasks.value.forEach { records.put(it.toJson()) }
        kv.encode("tasks-v1", records.toString())
        persistedAt = System.currentTimeMillis()
    }

    /**
     * 从 MMKV 读取并校验主题与恢复开关。
     */
    private fun loadSettings(): ToolSettings {
        val defaults = ToolSettings()
        return ToolSettings(runCatching { ThemeMode.valueOf(kv.decodeString("theme", defaults.theme.name)!!) }.getOrDefault(defaults.theme),
            kv.decodeBool("scripts", defaults.scripts), kv.decodeBool("scenes", defaults.scenes), kv.decodeBool("assets", defaults.assets),
            kv.decodeBool("plugins", defaults.plugins), kv.decodeBool("project-config", defaults.config), kv.decodeBool("keep-raw", defaults.keepRaw))
    }

    /**
     * 从 MMKV 读取有效的历史任务。
     */
    private fun loadTasks(): List<TaskRecord> = runCatching {
        val array = JSONArray(kv.decodeString("tasks-v1", "[]"))
        (0 until array.length()).mapNotNull { index -> runCatching { TaskRecord.fromJson(array.getJSONObject(index)) }.getOrNull() }
    }.getOrDefault(emptyList())
}
