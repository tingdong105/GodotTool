package com.winter.godot.tool.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.winter.godot.core.engine.GdreRunner
import com.winter.godot.core.io.ArchiveSet
import com.winter.godot.core.io.SafeFiles
import com.winter.godot.core.model.CoreFailure
import com.winter.godot.core.model.CoreProgress
import com.winter.godot.core.model.RecoverySelection
import com.winter.godot.tool.MainActivity
import com.winter.godot.tool.R
import com.winter.godot.tool.data.ProjectAssembler
import com.winter.godot.tool.data.PublicOutputs
import com.winter.godot.tool.data.WorkspaceStore
import com.winter.godot.tool.feature.CNM.NodeMotionAdapter
import com.winter.godot.tool.feature.files.StorageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 承载导入、解密、恢复与公共保存的隔离前台服务。
 * @author Tingdong
 * @date 2026-11-21
 */
class EngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val canceled = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { WorkspaceStore(this) }
    @Volatile private var listener: Messenger? = null
    @Volatile private var taskId = ""
    @Volatile private var lastEvent: String = ""
    private var lastProgress = 0L
    private var lastNotification = 0L
    private var started = false
    private var selection = RecoverySelection()
    private var selectionJson = JSONObject()
    @Volatile private var outputArchive: File? = null
    @Volatile private var currentStage = "import"
    @Volatile private var currentPath = ""
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            EngineProtocol.SUBSCRIBE -> {
                listener = message.replyTo
                if (lastEvent.isNotEmpty()) send(lastEvent)
            }
            EngineProtocol.START -> {
                listener = message.replyTo
                if (!started) {
                    started = true
                    val payload = message.data.getString(EngineProtocol.PAYLOAD).orEmpty()
                    try {
                        val request = JSONObject(payload)
                        taskId = request.getString("id")
                        scope.launch {
                            try { runRequest(request) }
                            catch (error: Throwable) { fail(error) }
                        }
                    } catch (error: Throwable) { fail(error) }
                }
            }
            EngineProtocol.CANCEL -> cancelTask("CANCELED")
        }
        true
    })

    /**
     * 创建进度通知渠道。
     */
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("extraction", getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * 启动前台通知并处理通知栏取消。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { cancelTask("CANCELED"); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, 10, notification(getString(R.string.stage_prepare)), type)
        main.postDelayed({ if (!started) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }, 15000)
        return START_NOT_STICKY
    }

    /**
     * 返回仅供应用内部使用的消息端点。
     */
    override fun onBind(intent: Intent?): IBinder = endpoint.binder

    /**
     * 响应系统前台任务超时。
     */
    override fun onTimeout(startId: Int, fgsType: Int) { cancelTask("TIMEOUT") }

    /**
     * 释放协程作用域。
     */
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /**
     * 处理文件恢复请求。
     * @param request 内存任务参数。
     */
    private fun runRequest(request: JSONObject) {
        taskId = request.getString("id")
        checkCanceled()
        val directory = store.directory(taskId).apply { mkdirs() }
        if (!StorageAccess.granted(this)) throw CoreFailure("STORAGE_PERMISSION", "Shared storage access is required")
        selectionJson = request.optJSONObject("selection") ?: JSONObject()
        selection = RecoverySelection(selectionJson.optBoolean("scripts", true), selectionJson.optBoolean("scenes", true),
            selectionJson.optBoolean("assets", true), selectionJson.optBoolean("plugins", true), selectionJson.optBoolean("config", true), selectionJson.optBoolean("keepRaw", false))
        if (!selection.hasContent) throw CoreFailure("EMPTY_SELECTION", "No recovery content selected")
        store.writeJson(taskId, "request-options.json", selectionJson.toString())
        val workDirectory = directory
        val inputs = stageInputs(request, directory)
        checkCanceled()
        var nm = false
        var nmResources = 0
        if (inputs.all { it.extension.lowercase() in setOf("apk", "zip") }) {
            progress(CoreProgress("probe"))
            ArchiveSet(inputs).use { archives ->
                val packageName = inputs.asSequence().filter { it.extension == "apk" }
                    .mapNotNull { packageManager.getPackageArchiveInfo(it.absolutePath, 0)?.packageName }
                    .firstOrNull { it == NodeMotionAdapter.PACKAGE_NAME }
                if (packageName != null) progress(CoreProgress("probe", getString(R.string.cnm_detected)))
                nm = NodeMotionAdapter.probe(archives, ::checkCanceled, packageName)
                if (nm) {
                    val entries = NodeMotionAdapter.extract(archives, File(directory, "raw"), ::checkCanceled, ::progress, selection::needsForRecovery, ensureVersionProbe = true)
                    nmResources = entries.size
                    val index = JSONArray()
                    entries.forEach { index.put(JSONObject().put("path", it.path).put("size", it.size).put("md5", it.md5).put("encrypted", it.encrypted)) }
                    store.writeJson(taskId, "raw-index.json", index.toString())
                    // 解密与引擎恢复分阶段落盘，原生崩溃后仍能读取完整原始资源。
                    val outputs = PublicOutputs(StorageAccess.outputRoot())
                    outputArchive = outputs.allocate(System.currentTimeMillis())
                    val pending = JSONObject().put("type", "result").put("id", taskId).put("summary", JSONObject()
                        .put("resources", nmResources).put("scripts", 0).put("adapter", "CNM")
                        .put("warning", "RECOVERY_INTERRUPTED").put("diagnostic", "Native recovery interrupted; decrypted resources are saved")
                        .put("enginePid", Process.myPid()).put("recoveryStartedAt", System.currentTimeMillis())
                        .put("selection", selectionJson).put("layoutVersion", 3)
                        .put("outputPath", outputArchive!!.absolutePath))
                    synchronized(this) {
                        checkCanceled()
                        if (terminal.get()) throw CancellationException("Task ended")
                        store.writeJson(taskId, "engine-result.json", pending.toString())
                        File(workDirectory, "result.json").writeText(pending.getJSONObject("summary").toString(2))
                        File(workDirectory, "recovery-report.txt").writeText("CNM: resources decrypted and MD5 verified. Script recovery is pending.\n")
                    }
                    outputs.publish(directory, System.currentTimeMillis(), ::checkCanceled, ::progress, outputArchive)
                }
            }
        }
        val paths = if (nm) listOf(File(workDirectory, "raw").absolutePath) else inputs.map { it.absolutePath }
        val job = JSONObject().put("paths", JSONArray(paths)).put("output", workDirectory.absolutePath).put("rawReady", nm)
            .put("scripts", request.optBoolean("scripts", true)).put("scenes", request.optBoolean("scenes", true)).put("key", request.optString("key"))
            .put("availableBytes", workDirectory.usableSpace)
            .put("selection", selectionJson)
        checkCanceled()
        streamEngineLog(workDirectory)
        GdreRunner(this).start(job.toString(), canceled, { payload ->
            try {
                val event = JSONObject(payload)
                if (event.optString("type") in setOf("result", "error")) {
                    if (event.optString("type") == "result") {
                        if (nm) event.getJSONObject("summary").put("adapter", "CNM")
                        saveRecovery(event, workDirectory)
                    } else if (nmResources > 0 && !canceled.get()) {
                        val summary = JSONObject().put("resources", nmResources).put("scripts", 0).put("adapter", "CNM")
                            .put("warning", "SCRIPT_RECOVERY_FAILED").put("errorCode", event.optString("code")).put("diagnostic", event.optString("detail").take(20000))
                        File(workDirectory, "recovery-report.txt").writeText(event.optString("code") + "\n" + event.optString("detail"))
                        saveRecovery(JSONObject().put("type", "result").put("summary", summary), workDirectory)
                    } else finish(event)
                } else emit(event)
            } catch (error: Throwable) { fail(error) }
        }, ::shutdown)
    }

    /**
     * 自动发布恢复结果后再通知主界面完成。
     * @param event 恢复结果事件。
     * @param directory 私有工作区。
     */
    private fun saveRecovery(event: JSONObject, directory: File) {
        checkCanceled()
        if (!StorageAccess.granted(this)) throw CoreFailure("STORAGE_PERMISSION", "Shared storage access is required")
        val summary = event.getJSONObject("summary")
        val checkpoint = JSONObject(summary.toString()).put("warning", "RECOVERY_INTERRUPTED")
        outputArchive?.let { checkpoint.put("outputPath", it.absolutePath) }
        store.writeJson(taskId, "engine-result.json", JSONObject().put("type", "result").put("id", taskId).put("summary", checkpoint).toString())
        val dependencyFile = File(directory, "resource-dependencies.json")
        val dependencies = if (dependencyFile.isFile && dependencyFile.length() in 1..(128L * 1024 * 1024)) {
            val array = JSONArray(dependencyFile.readText())
            if (array.length() > 200000) throw CoreFailure("ENTRY_LIMIT", "Too many fallback resources")
            List(array.length()) { array.getString(it) }
        } else emptyList()
        val assembler = ProjectAssembler()
        val project = assembler.assemble(directory, selection, ::checkCanceled, ::progress, dependencies)
        summary.put("rawResources", summary.optInt("resources")).put("resources", project.files).put("scripts", project.scripts)
            .put("projectFiles", project.files).put("layoutVersion", 3).put("selection", selectionJson)
        val report = ProjectAssembler.reportFile(directory, "recovery-report.txt")
        report.appendText("\nGodotTool project: project/\nFiles: ${project.files}, readable scripts: ${project.scripts}\nSelection: $selectionJson\n")
        assembler.finish(directory, selection)
        assembler.organizeReports(directory)
        val outputs = PublicOutputs(StorageAccess.outputRoot())
        val output = outputArchive ?: outputs.allocate(System.currentTimeMillis()).also { outputArchive = it }
        summary.put("outputPath", output.absolutePath).put("projectPath", "project/")
        ProjectAssembler.reportFile(directory, "result.json").writeText(summary.toString(2))
        outputs.publish(directory, System.currentTimeMillis(), ::checkCanceled, ::progress, output)
        finish(event)
    }

    /**
     * 将外部输入复制到可随机读取的私有工作区。
     * @param request 导入请求。
     * @param directory 任务目录。
     * @return 本地输入文件集合。
     */
    private fun stageInputs(request: JSONObject, directory: File): List<File> {
        val sources = request.getJSONArray("sources")
        if (sources.length() !in 1..128) throw CoreFailure("BAD_REQUEST", "Input count must be 1..128")
        val inputDirectory = File(directory, "input").apply { mkdirs() }
        val manifest = JSONArray()
        val inputs = List(sources.length()) { i ->
            checkCanceled()
            val source = sources.getJSONObject(i)
            val name = source.optString("name", "input.apk")
            val extension = name.substringAfterLast('.', "apk").lowercase()
            if (extension !in setOf("apk", "pck", "zip")) throw CoreFailure("UNSUPPORTED_INPUT", name)
            val target = File(inputDirectory, "$i.$extension")
            val temporary = File(inputDirectory, "$i.part")
            val expected = if (source.has("file")) File(source.getString("file")).length() else {
                runCatching { contentResolver.query(Uri.parse(source.getString("uri")), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
                } ?: 0L }.getOrDefault(0L)
            }
            try {
                val free = directory.usableSpace
                if (expected > free) throw CoreFailure("NO_SPACE", "Insufficient space for input")
                val input = if (source.has("uri")) contentResolver.openInputStream(Uri.parse(source.getString("uri")))
                    ?: throw CoreFailure("READ_PERMISSION", name)
                else File(source.getString("file")).inputStream()
                input.use { stream -> temporary.outputStream().use { output ->
                    SafeFiles.copy(stream, output, free, ::checkCanceled) { count -> progress(CoreProgress("import", name, count, expected, i, sources.length())) }
                } }
                if (expected > 0 && temporary.length() != expected) throw CoreFailure("SIZE_CHANGED", name)
                if (!temporary.renameTo(target)) throw CoreFailure("WRITE_FAILED", name)
                manifest.put(JSONObject().put("name", name).put("local", "input/${target.name}"))
            } finally { temporary.delete() }
            target
        }
        store.writeJson(taskId, "inputs.json", manifest.toString())
        return inputs
    }

    /**
     * 抛出任务取消，供流式循环检查。
     */
    private fun checkCanceled() { if (canceled.get()) throw CancellationException("Canceled") }

    /**
     * 节流并发送真实字节进度。
     * @param value 核心进度。
     */
    private fun progress(value: CoreProgress) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (value.path.isNotEmpty() && now - lastProgress < 120 && value.bytes != value.totalBytes) return
        lastProgress = now
        emit(JSONObject().put("type", "progress").put("stage", value.stage).put("path", value.path).put("bytes", value.bytes)
            .put("totalBytes", value.totalBytes).put("files", value.files).put("totalFiles", value.totalFiles))
    }

    /**
     * 发送未结束任务的事件。
     * @param event 核心事件。
     */
    private fun emit(event: JSONObject) {
        if (terminal.get()) return
        event.put("id", taskId)
        if (event.optString("type") == "progress") {
            currentStage = event.optString("stage", currentStage)
            currentPath = event.optString("path")
        }
        lastEvent = event.toString()
        send(lastEvent)
        val now = android.os.SystemClock.elapsedRealtime()
        if (event.optString("type") == "progress" && now - lastNotification > 1000) {
            lastNotification = now
            val label = getString(com.winter.godot.tool.ui.components.stageResource(event.optString("stage")))
            main.post { if (!terminal.get()) getSystemService(NotificationManager::class.java).notify(10, notification(label)) }
        }
    }

    /**
     * 保存唯一终态事件，便于进程退出后恢复记录。
     * @param event 完成或失败事件。
     */
    @Synchronized
    private fun finish(event: JSONObject) {
        if (terminal.get()) return
        event.put("id", taskId)
        if (event.optString("type") == "error" && !event.has("summary")) {
            store.result(taskId)?.optJSONObject("summary")?.takeIf { it.optInt("resources") > 0 }?.let { summary ->
                summary.put("warning", if (event.optString("code") == "CANCELED") "RECOVERY_CANCELED" else "RECOVERY_FINALIZATION_FAILED")
                    .put("errorCode", event.optString("code")).put("diagnostic", event.optString("detail"))
                event.put("summary", summary)
            }
        }
        outputArchive?.takeIf { it.length() == 0L }?.delete()
        if (taskId.isNotEmpty()) runCatching { store.writeJson(taskId, "engine-result.json", event.toString()) }.onFailure { error ->
            event.put("type", "error").put("code", "WRITE_FAILED").put("detail", error.message.orEmpty().take(3000))
        }
        terminal.set(true)
        lastEvent = event.toString()
        send(lastEvent)
        main.post { stopForeground(STOP_FOREGROUND_REMOVE) }
        shutdown()
    }

    /**
     * 向当前订阅者投递消息。
     * @param payload 小型事件 JSON。
     */
    private fun send(payload: String) {
        runCatching { listener?.send(Message.obtain(null, EngineProtocol.EVENT).apply { data = Bundle().apply { putString(EngineProtocol.PAYLOAD, payload) } }) }
    }

    /**
     * 记录错误并回收隔离进程。
     * @param error 原始异常。
     */
    private fun fail(error: Throwable) {
        val code = when (error) { is CoreFailure -> error.code; is CancellationException -> "CANCELED"; is SecurityException -> "READ_PERMISSION"; else -> "IO_FAILED" }
        val detail = "$currentStage: $currentPath\n${error.message.orEmpty()}".take(3000)
        val event = JSONObject().put("type", "error").put("code", code).put("detail", detail)
        runCatching {
            val summary = store.result(taskId)?.optJSONObject("summary")
            if (summary != null && summary.optInt("resources") > 0) {
                summary.put("warning", if (code == "CANCELED") "RECOVERY_CANCELED" else "RECOVERY_FINALIZATION_FAILED")
                    .put("errorCode", code).put("diagnostic", detail)
                val directory = store.directory(taskId)
                ProjectAssembler.reportFile(directory, "result.json").writeText(summary.toString(2))
                ProjectAssembler.reportFile(directory, "recovery-report.txt").appendText("\n$code\n$detail\n")
                event.put("summary", summary)
                if (!canceled.get()) runCatching {
                    PublicOutputs(StorageAccess.outputRoot()).publish(directory, System.currentTimeMillis(), {}, {}, outputArchive)
                }
            }
        }
        runCatching { finish(event) }
        shutdown()
    }

    /**
     * 将引擎输出增量送到首页，单次消息保持有界。
     * @param directory 私有任务目录。
     */
    private fun streamEngineLog(directory: File) {
        scope.launch {
            var offset = 0L
            var pending = ""
            while (!terminal.get() && !canceled.get()) {
                delay(500)
                val file = File(directory, "engine.log")
                if (!file.isFile) continue
                runCatching {
                    val lines = JSONArray()
                    RandomAccessFile(file, "r").use { input ->
                        if (offset > input.length()) offset = 0
                        input.seek(offset)
                        val bytes = ByteArray(8192)
                        val count = input.read(bytes)
                        if (count > 0) {
                            val text = pending + String(bytes, 0, count, Charsets.UTF_8)
                            val parts = text.split('\n')
                            parts.dropLast(1).takeLast(20).forEach { lines.put(it.take(1000)) }
                            pending = parts.last().takeLast(1000)
                        }
                        offset = input.filePointer
                    }
                    if (lines.length() > 0) emit(JSONObject().put("type", "log").put("lines", lines))
                }
            }
        }
    }

    /**
     * 取消任务并终止正在执行的原生调用。
     * @param code 取消或超时原因。
     */
    private fun cancelTask(code: String) {
        canceled.set(true)
        scope.launch {
            runCatching { finish(JSONObject().put("type", "error").put("code", code)) }
            shutdown()
        }
    }

    /**
     * 延迟回收整个核心进程，避免原生全局状态跨任务残留。
     */
    private fun shutdown() {
        main.postDelayed({ stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); Process.killProcess(Process.myPid()) }, 600)
    }

    /**
     * 构造包含取消入口的前台通知。
     * @param text 当前状态。
     * @return 前台通知。
     */
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, EngineService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "extraction").setSmallIcon(R.drawable.ic_task_notification).setContentTitle(getString(R.string.app_name))
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(0, getString(R.string.cancel), cancel).build()
    }
}
