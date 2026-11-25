package com.winter.godot.tool.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tencent.mmkv.MMKV
import com.winter.godot.core.model.RecoverySelection
import com.winter.godot.tool.R
import com.winter.godot.tool.feature.compare.SnapshotOptions
import com.winter.godot.tool.task.EngineClient
import com.winter.godot.tool.task.EngineExitDiagnostics
import com.winter.godot.tool.ui.components.errorResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 主进程唯一的设置与任务状态持有者。
 * @author Tingdong
 * @date 2026-11-21
 */
class ToolRepository(private val context: Context) {
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
    private val deletionMutex = Mutex()
    private val deletionKey = "pending-task-delete-v1"
    private val client = EngineClient(context, ::onEvent, ::onDisconnected)

    init {
        scope.launch {
            deletionMutex.withLock {
                val id = kv.decodeString(deletionKey) ?: return@withLock
                try { deleteRecord(id) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { Log.w("ToolRepository", "Pending workspace cleanup failed", error) }
            }
        }
        val running = mutableTasks.value.firstOrNull { it.status == TaskStatus.RUNNING }
        if (running != null) {
            mutableActive.value = running.id
            if (running.selection == null) scope.launch {
                val selection = withContext(Dispatchers.IO) { readSelection(running.id) }
                if (selection != null) {
                    mutableTasks.update { records -> records.map { if (it.id == running.id && it.selection == null) it.copy(selection = selection) else it } }
                    persistTasks()
                }
            }
            if (!client.reconnect()) onDisconnected()
        }
    }

    /**
     * 保存新的恢复偏好。
     * @param value 完整偏好。
     */
    fun updateSettings(value: ToolSettings) {
        kv.encode("theme", value.theme.name)
        kv.encode("floating-bottom-bar", value.floatingBottomBar)
        kv.encode("liquid-glass", value.liquidGlass)
        kv.encode("scripts", value.scripts)
        kv.encode("scenes", value.scenes)
        kv.encode("assets", value.assets)
        kv.encode("plugins", value.plugins)
        kv.encode("project-config", value.config)
        kv.encode("keep-raw", value.keepRaw)
        kv.encode("snapshot", value.snapshot.generate)
        mutableSettings.value = value
    }

    /**
     * 读取上次确认文件所在的目录，由浏览器校验可访问性。
     * @param comparison 是否读取独立的对比目录。
     * @return 保存的目录，尚未确认时为空。
     */
    fun confirmedBrowserPath(comparison: Boolean = false): String = kv.decodeString(if (comparison) "comparison-browser-path" else "confirmed-browser-path").orEmpty()

    /**
     * 确认文件后持久保存已校验的父目录。
     * @param path 共享存储中的规范目录。
     * @param comparison 是否写入独立的对比目录。
     */
    fun rememberBrowserPath(path: String, comparison: Boolean = false) { kv.encode(if (comparison) "comparison-browser-path" else "confirmed-browser-path", path) }

    /**
     * 创建一个恢复任务。
     * @param draft 导入来源。
     * @param key 临时密钥。
     * @return 新任务标识。
     */
    fun start(draft: ImportDraft, key: String): String {
        val id = UUID.randomUUID().toString()
        val request = draft.request(id, settings.value, key)
        launchTask(TaskRecord(id, draft.name, selection = recoverySelectionFromJson(request.getJSONObject("selection")), snapshot = SnapshotOptions(request.optBoolean("snapshot"))), request)
        return id
    }

    /**
     * 确认已结束的任务结果。
     * @param id 用户已查看或离开的任务。
     */
    fun acknowledge(id: String) {
        if (active.value == id || tasks.value.none { it.id == id && !it.acknowledged && it.status != TaskStatus.RUNNING }) return
        mutableTasks.update { records -> records.map { if (it.id == id) it.copy(acknowledged = true) else it } }
        persistTasks()
    }

    /**
     * 清理明确指定且未运行的工作区。
     * @param id 任务标识。
     */
    suspend fun delete(id: String) { delete(setOf(id)) }

    /**
     * 串行清理工作区，逐条保存记录与恢复标记。
     * @param ids 用户确认的任务标识集合。
     */
    suspend fun delete(ids: Set<String>) = deletionMutex.withLock {
        kv.decodeString(deletionKey)?.let { deleteRecord(it) }
        val records = tasks.value.filter { it.id in ids }
        check(records.none { it.id == active.value || it.status == TaskStatus.RUNNING })
        for (record in records) {
            currentCoroutineContext().ensureActive()
            deleteRecord(record.id)
        }
    }

    /**
     * 先保存删除意图，提交索引后清除标记，中断时可重放。
     * @param id 已确认清理的任务标识。
     */
    private suspend fun deleteRecord(id: String) {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        check(id != active.value && tasks.value.none { it.id == id && it.status == TaskStatus.RUNNING })
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                check(kv.encode(deletionKey, id)) { "DELETE_JOURNAL_WRITE_FAILED" }
                kv.sync()
            }
            var removed = false
            var committed = false
            try {
                withContext(Dispatchers.IO) { workspaces.delete(id) }
                removed = true
                mutableTasks.update { records -> records.filterNot { it.id == id } }
                check(persistTasks()) { "TASK_INDEX_WRITE_FAILED" }
                withContext(Dispatchers.IO) { kv.sync() }
                committed = true
            } finally {
                if (!removed || committed) withContext(Dispatchers.IO) {
                    kv.removeValueForKey(deletionKey)
                    kv.sync()
                }
            }
        }
    }

    /**
     * 清理全部已结束任务和缓存。
     * @return 清理的工作区数量。
     */
    suspend fun clearFinished(): Int {
        check(active.value == null)
        val finished = tasks.value.filter { it.status != TaskStatus.RUNNING }
        delete(finished.map { it.id }.toSet())
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
        val task = tasks.value.first { it.id == id }
        ImportDraft(task.name, sources, task.selection ?: readSelection(id), task.snapshot)
    }

    /**
     * 从私有请求记录补齐旧任务的配置。
     * @param id 工作区任务标识。
     * @return 可用的任务配置，缺失或损坏时为空。
     */
    private fun readSelection(id: String): RecoverySelection? = runCatching {
        val options = java.io.File(workspaces.directory(id), "request-options.json")
        if (options.isFile && options.length() in 1..8192) recoverySelectionFromJson(JSONObject(options.readText())) else null
    }.getOrNull()

    /**
     * 注册摘要后发送任务，重复触发由活动标识互斥。
     * @param record 新任务摘要。
     * @param request 仅内存使用的请求。
     */
    private fun launchTask(record: TaskRecord, request: JSONObject) {
        check(active.value == null) { "Task already running" }
        val running = record.copy(startedAtElapsedRealtime = SystemClock.elapsedRealtime())
        mutableActive.value = record.id
        mutableLogs.value = listOf(TaskLogLine("import", context.getString(R.string.log_import)))
        mutableTasks.update { records -> listOf(running) + records.map { if (!it.acknowledged) it.copy(acknowledged = true) else it } }
        persistTasks()
        try { client.start(request.put("startedAtElapsedRealtime", running.startedAtElapsedRealtime)) }
        catch (error: Throwable) {
            mutableActive.value = null
            replace(running.copy(status = TaskStatus.FAILED, errorCode = "SERVICE_START", errorDetail = error.message.orEmpty(),
                elapsedMillis = running.processingMillis(SystemClock.elapsedRealtime())))
            appendLog(TaskLogLine("error", context.getString(R.string.error_service), TaskLogLevel.ERROR))
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
                val stage = event.optString("stage", current.stage)
                val now = SystemClock.elapsedRealtime()
                val elapsed = event.optLong("elapsedMillis", current.processingMillis(now)).coerceAtLeast(current.elapsedMillis)
                val message = when (stage) {
                    "import" -> R.string.log_import
                    "probe", "decrypt" -> R.string.log_prepare
                    "extract" -> R.string.log_extract
                    "checkpoint" -> R.string.log_checkpoint
                    "recover" -> R.string.log_recover
                    "snapshot" -> R.string.stage_snapshot
                    "assemble" -> R.string.log_assemble
                    "publish", "export" -> R.string.log_publish
                    else -> null
                }
                if (message != null) {
                    val line = TaskLogLine(stage, context.getString(message))
                    if (mutableLogs.value.none { it.text == line.text }) appendLog(line)
                }
                replace(current.copy(stage = stage, path = event.optString("path"), bytes = event.optLong("bytes"),
                    totalBytes = event.optLong("totalBytes"), files = event.optInt("files"), totalFiles = event.optInt("totalFiles"),
                    elapsedMillis = elapsed, startedAtElapsedRealtime = (now - elapsed).takeIf { it > 0L } ?: 0L,
                    overallProgress = maxOf(current.overallProgress, event.optDouble("overallProgress", current.overallProgress.toDouble()).toFloat()).coerceIn(0f, 0.99f)))
                if (System.currentTimeMillis() - persistedAt > 1500) persistTasks()
            }
            "mode" -> if (event.optString("mode") == "CNM") {
                val line = TaskLogLine("mode", context.getString(R.string.cnm_detected), TaskLogLevel.ACCENT)
                if (line !in mutableLogs.value) appendLog(line)
            }
            "result" -> {
                val summary = event.optJSONObject("summary") ?: JSONObject()
                val partial = summary.has("warning") || summary.optInt("failedScripts") > 0 || summary.optInt("failedConversions") > 0
                // 先保存完成事件，索引可在下次打开工作区时重建。
                replace(current.copy(status = if (partial) TaskStatus.PARTIAL else TaskStatus.SUCCEEDED, stage = "done", summary = summary.toString(), path = "", overallProgress = 1f,
                    elapsedMillis = completedDuration(current, event),
                    selection = current.selection ?: summary.optJSONObject("selection")?.let(::recoverySelectionFromJson)))
                appendLog(TaskLogLine("done", context.getString(if (partial) R.string.log_partial else R.string.log_complete),
                    if (partial) TaskLogLevel.WARNING else TaskLogLevel.SUCCESS))
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
                appendLog(TaskLogLine("error", context.getString(errorResource(code)), TaskLogLevel.ERROR))
                val summary = event.optJSONObject("summary")
                replace(current.copy(status = when {
                    code == "CANCELED" -> TaskStatus.CANCELED
                    summary != null && summary.optInt("resources") > 0 -> TaskStatus.PARTIAL
                    else -> TaskStatus.FAILED
                }, summary = summary?.toString() ?: current.summary, errorCode = code, errorDetail = event.optString("detail").take(3000),
                    elapsedMillis = completedDuration(current, event)))
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
            val task = tasks.value.firstOrNull { it.id == id } ?: return@launch
            val since = task.createdAt
            val (result, elapsed) = withContext(Dispatchers.IO) {
                val event = runCatching { workspaces.reconcileInterrupted(id, since) }.getOrElse { workspaces.result(id) }
                val saved = event?.let { savedDuration(it) }
                val duration = saved ?: task.interruptedMillis(EngineExitDiagnostics.exitedAt(context, since,
                    event?.optJSONObject("summary")?.optInt("enginePid") ?: 0))
                if (event != null && saved == null) {
                    event.put("elapsedMillis", duration)
                    runCatching { workspaces.writeJson(id, "engine-result.json", event.toString()) }
                }
                event to duration
            }
            if (result != null) onEvent(result)
            val current = tasks.value.firstOrNull { it.id == id }
            if (current?.status == TaskStatus.RUNNING) {
                replace(current.copy(status = TaskStatus.INTERRUPTED, errorCode = "ENGINE_EXIT",
                    elapsedMillis = elapsed))
                appendLog(TaskLogLine("error", context.getString(R.string.error_engine), TaskLogLevel.ERROR))
                persistTasks()
            }
            mutableActive.value = null
        }
    }

    /**
     * 冻结服务记录的全程耗时，缺失记录时保留最后确认值。
     * @param task 当前任务。
     * @param event 终态事件。
     * @return 可持久保存的处理毫秒数。
     */
    private fun completedDuration(task: TaskRecord, event: JSONObject): Long {
        return savedDuration(event) ?: task.elapsedMillis
    }

    /**
     * 读取服务确认的耗时，兼容旧版本终态格式。
     * @param event 进度或终态事件。
     * @return 非负处理毫秒数，记录缺失时为空。
     */
    private fun savedDuration(event: JSONObject): Long? = event.optLong("elapsedMillis",
        event.optJSONObject("timings")?.optLong("totalMs", -1L) ?: -1L).takeIf { it >= 0L }

    /**
     * 保留有界且去重的用户日志。
     * @param line 已本地化的关键状态。
     */
    private fun appendLog(line: TaskLogLine) {
        mutableLogs.update { if (it.lastOrNull() == line) it else (it + line).takeLast(100) }
    }

    /**
     * 替换列表中的单个任务摘要。
     * @param value 更新后的任务。
     */
    private fun replace(value: TaskRecord) { mutableTasks.update { records -> records.map { if (it.id == value.id) value else it } } }

    /**
     * 保存带版本号的轻量任务索引。
     * @return 是否成功写入索引。
     */
    private fun persistTasks(): Boolean {
        val records = JSONArray()
        tasks.value.forEach { records.put(it.toJson()) }
        val persisted = kv.encode("tasks-v1", records.toString())
        persistedAt = System.currentTimeMillis()
        return persisted
    }

    /**
     * 从 MMKV 读取并校验主题与恢复开关。
     */
    private fun loadSettings(): ToolSettings {
        val defaults = ToolSettings()
        return ToolSettings(runCatching { ThemeMode.valueOf(kv.decodeString("theme", defaults.theme.name)!!) }.getOrDefault(defaults.theme),
            kv.decodeBool("scripts", defaults.scripts), kv.decodeBool("scenes", defaults.scenes), kv.decodeBool("assets", defaults.assets),
            kv.decodeBool("plugins", defaults.plugins), kv.decodeBool("project-config", defaults.config), kv.decodeBool("keep-raw", defaults.keepRaw), SnapshotOptions(kv.decodeBool("snapshot", false)),
            floatingBottomBar = kv.decodeBool("floating-bottom-bar", defaults.floatingBottomBar),
            liquidGlass = kv.decodeBool("liquid-glass", defaults.liquidGlass))
    }

    /**
     * 从 MMKV 读取有效的历史任务。
     */
    private fun loadTasks(): List<TaskRecord> = runCatching {
        val array = JSONArray(kv.decodeString("tasks-v1", "[]"))
        (0 until array.length()).mapNotNull { index -> runCatching { TaskRecord.fromJson(array.getJSONObject(index)) }.getOrNull() }
    }.getOrDefault(emptyList())
}
