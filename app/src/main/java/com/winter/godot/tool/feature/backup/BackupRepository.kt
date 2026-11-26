package com.winter.godot.tool.feature.backup

import android.content.Context
import android.util.AtomicFile
import com.winter.godot.core.io.SafeFiles
import com.winter.godot.tool.feature.compare.ComparisonHistory
import com.winter.godot.tool.feature.compare.ComparisonRules
import com.winter.godot.tool.feature.compare.SnapshotFormat
import com.winter.godot.tool.feature.files.StorageAccess
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 流式 ZIP 备份与带持久回滚日志的选择性还原。
 * @author Tingdong
 * @date 2026-11-26
 */
class BackupRepository(private val context: Context) {
    private val files = context.filesDir
    private val historyRoot = File(files, "comparison-history")
    private val rulesFile = File(files, "comparison-rules.json")
    private val transaction = File(files, ".backup-restore")

    /**
     * 只读取有界清单及目录，数据校验在确认还原后执行。
     * @param path 用户选择的共享存储 ZIP。
     * @return 冻结的清单与可还原范围。
     */
    fun inspect(path: String): BackupArchive {
        val file = File(path).canonicalFile
        require(file.toPath().startsWith(StorageAccess.root().canonicalFile.toPath()) && file.isFile) { "BACKUP_INVALID" }
        return ZipFile(file).use { zip ->
            val manifest = readManifest(zip)
            val json = JSONObject(manifest)
            validateManifest(zip, json)
            BackupArchive(file.absolutePath, manifest, scopes(json), json.getLong("createdAt"))
        }
    }

    /**
     * 从冻结的数据范围导出，完成前仅保留临时 ZIP。
     * @param selected 备份范围。
     * @param check 取消检查。
     * @param progress 已处理的真实数据比例。
     * @param committing 进入不可取消的文件发布阶段。
     * @return 最终公开文件路径。
     */
    suspend fun export(selected: Set<BackupScope>, check: () -> Unit, progress: (Float) -> Unit, committing: suspend () -> Unit): String {
        require(selected.isNotEmpty())
        recover()
        val stage = File(context.cacheDir, "backup-${UUID.randomUUID()}")
        check(stage.mkdirs())
        var temporary: File? = null
        try {
            val sources = linkedMapOf<String, File>()
            if (BackupScope.HISTORY in selected && historyRoot.exists()) {
                val history = ComparisonHistory(historyRoot, StorageAccess.diffRoot())
                history.validateBackup(check = check)
                for (folder in historyRoot.listFiles().orEmpty().filter { !it.name.startsWith('.') }.sortedBy { it.name }) {
                    for (file in folder.listFiles().orEmpty().filter { !it.name.startsWith('.') }.sortedBy { it.name }) {
                        sources["history/${folder.name}/${file.name}"] = file
                    }
                }
            }
            if (selected.any { it == BackupScope.FILTER_RULES || it == BackupScope.FOCUS_RULES }) {
                val rules = ComparisonRules.load(rulesFile)
                for (scope in listOf(BackupScope.FILTER_RULES, BackupScope.FOCUS_RULES).filter { it in selected }) {
                    val file = File(stage, scope.entry)
                    ComparisonRules.save(file, rules.filter { it.exclude == (scope == BackupScope.FILTER_RULES) })
                    sources[scope.entry] = file
                }
            }
            if (BackupScope.SETTINGS in selected) {
                val file = File(stage, BackupScope.SETTINGS.entry)
                val settings = BackupSettings.capture()
                BackupSettings.validate(settings)
                writeJson(file, settings)
                sources[BackupScope.SETTINGS.entry] = file
            }
            require(sources.size <= MAX_FILES) { "BACKUP_LIMIT" }
            val total = sources.values.fold(0L) { sum, file -> Math.addExact(sum, file.length()) }
            val outputRoot = StorageAccess.backupRoot()
            check(outputRoot.isDirectory || outputRoot.mkdirs()) { "BACKUP_WRITE" }
            requireSpace(outputRoot, total + MANIFEST_LIMIT + SPACE_MARGIN)
            temporary = File.createTempFile(".GodotTool_Backup_", ".part", outputRoot)
            val entries = JSONArray()
            var done = 0L
            FileOutputStream(temporary).use { stream ->
                ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                    for ((path, source) in sources) {
                        check()
                        val size = source.length()
                        require(size <= entryLimit(path)) { "BACKUP_LIMIT" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        zip.putNextEntry(ZipEntry(path))
                        source.inputStream().use { input ->
                            val copied = SafeFiles.copy(input, object : java.io.OutputStream() {
                                /** 按单字节接口转发写入。 */
                                override fun write(value: Int) { zip.write(value); digest.update(value.toByte()) }
                                /** 同时压缩并计算文件校验和。 */
                                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                                    zip.write(buffer, offset, length); digest.update(buffer, offset, length)
                                }
                            }, size, check) { bytes -> progress(((done + bytes).toDouble() / total.coerceAtLeast(1)).toFloat() * 0.98f) }
                            require(copied == size) { "BACKUP_CHANGED" }
                        }
                        zip.closeEntry()
                        entries.put(JSONObject().put("path", path).put("size", size).put("sha256", SafeFiles.hex(digest.digest())))
                        done += size
                    }
                    val manifest = JSONObject().put("format", FORMAT).put("version", VERSION)
                        .put("createdAt", System.currentTimeMillis())
                        .put("scopes", JSONArray(BackupScope.entries.filter { it in selected }.map { it.name })).put("files", entries)
                        .toString().toByteArray(Charsets.UTF_8)
                    require(manifest.size <= MANIFEST_LIMIT) { "BACKUP_LIMIT" }
                    zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest); zip.closeEntry()
                    zip.finish(); zip.flush(); stream.fd.sync()
                }
            }
            check()
            var target: File
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
            var timestamp = System.currentTimeMillis()
            do {
                val date = dateFormat.format(Date(timestamp))
                target = File(outputRoot, "GodotTool_Backup_$date.zip")
                timestamp += 1000L
            } while (target.exists())
            committing()
            Files.move(temporary.toPath(), target.toPath())
            progress(1f)
            return target.absolutePath
        } finally {
            temporary?.delete()
            runCatching { removeOwned(stage, context.cacheDir) }
        }
    }

    /**
     * 完整校验到私有暂存区后，事务替换选中范围。
     * @param archive 范围选择时冻结的备份。
     * @param selected 需要覆盖的范围。
     * @param check 取消检查。
     * @param progress 真实数据字节比例。
     * @param committing 进入不可取消的提交阶段。
     */
    suspend fun restore(archive: BackupArchive, selected: Set<BackupScope>, check: () -> Unit,
        progress: (Float) -> Unit, committing: suspend () -> Unit) {
        require(selected.isNotEmpty() && archive.scopes.containsAll(selected)) { "BACKUP_INVALID" }
        recover()
        val stage = File(context.cacheDir, "backup-${UUID.randomUUID()}")
        check(stage.mkdirs())
        var commitStarted = false
        try {
            ZipFile(archive.path).use { zip ->
                val manifest = readManifest(zip)
                require(manifest == archive.manifest) { "BACKUP_CHANGED" }
                val json = JSONObject(manifest)
                val entries = validateManifest(zip, json)
                val total = entries.fold(0L) { sum, entry -> Math.addExact(sum, entry.getLong("size")) }
                requireSpace(stage, total + SPACE_MARGIN)
                var done = 0L
                for (entry in entries) {
                    check()
                    val path = entry.getString("path")
                    val size = entry.getLong("size")
                    val target = SafeFiles.resolve(stage, path)
                    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(zip.getEntry(path)).use { input ->
                        FileOutputStream(target).use { stream ->
                            val output = java.security.DigestOutputStream(BufferedOutputStream(stream), digest)
                            val copied = SafeFiles.copy(input, output, size, check) { bytes ->
                                progress(((done + bytes).toDouble() / total.coerceAtLeast(1)).toFloat() * 0.95f)
                            }
                            output.flush(); stream.fd.sync()
                            require(copied == size) { "BACKUP_INVALID" }
                        }
                    }
                    require(SafeFiles.hex(digest.digest()) == entry.getString("sha256")) { "BACKUP_CHECKSUM" }
                    done += size
                }
            }
            validateData(stage, archive.scopes, check)
            check()
            prepareTransaction(stage, selected)
            check()
            committing()
            commitStarted = true
            commit(selected)
            progress(1f)
        } finally {
            try {
                if (!commitStarted && transaction.exists()) {
                    try { recover() } catch (error: Exception) {
                        BackupAccess.requireRecovery()
                        throw IllegalStateException("BACKUP_RECOVERY_REQUIRED", error)
                    }
                }
            } finally { runCatching { removeOwned(stage, context.cacheDir) } }
        }
    }

    /**
     * 校验所有业务数据，清除外部设备的报告发布关联。
     * @param stage 完整解压的私有目录。
     * @param available 清单包含的范围。
     * @param check 取消检查。
     */
    private fun validateData(stage: File, available: Set<BackupScope>, check: () -> Unit) {
        for (scope in available) {
            check()
            when (scope) {
                BackupScope.HISTORY -> {
                    val root = File(stage, "history")
                    check(root.isDirectory || root.mkdirs())
                    ComparisonHistory(root, StorageAccess.diffRoot()).validateBackup(clearPublication = true, check = check)
                }
                BackupScope.FILTER_RULES, BackupScope.FOCUS_RULES -> {
                    val rules = ComparisonRules.load(File(stage, scope.entry))
                    require(rules.all { it.exclude == (scope == BackupScope.FILTER_RULES) }) { "BACKUP_INVALID" }
                }
                BackupScope.SETTINGS -> BackupSettings.validate(JSONObject(File(stage, scope.entry).readText()))
            }
        }
    }

    /**
     * 准备替换文件及旧设置，日志在所有暂存文件落盘后才发布。
     * @param stage 已校验输入。
     * @param selected 待覆盖范围。
     */
    private fun prepareTransaction(stage: File, selected: Set<BackupScope>) {
        require(!transaction.exists()) { "BACKUP_RECOVERY_REQUIRED" }
        check(transaction.mkdir())
        if (BackupScope.HISTORY in selected) {
            Files.move(File(stage, "history").toPath(), File(transaction, "new-history").toPath())
        }
        val ruleScopes = listOf(BackupScope.FILTER_RULES, BackupScope.FOCUS_RULES)
        if (ruleScopes.any { it in selected }) {
            val preserved = if (ruleScopes.all { it in selected }) emptyList() else ComparisonRules.load(rulesFile)
                .filterNot { if (it.exclude) BackupScope.FILTER_RULES in selected else BackupScope.FOCUS_RULES in selected }
            val restored = ruleScopes.filter { it in selected }.flatMap { ComparisonRules.load(File(stage, it.entry)) }
            ComparisonRules.save(File(transaction, "new-rules"), preserved + restored)
            FileOutputStream(File(transaction, "new-rules"), true).use { it.fd.sync() }
        }
        if (BackupScope.SETTINGS in selected) {
            writeJson(File(transaction, "old-settings"), BackupSettings.capture())
            writeJson(File(transaction, "new-settings"), JSONObject(File(stage, BackupScope.SETTINGS.entry).readText()))
        }
        val journal = JSONObject().put("history", BackupScope.HISTORY in selected)
            .put("rules", selected.any { it == BackupScope.FILTER_RULES || it == BackupScope.FOCUS_RULES })
            .put("settings", BackupScope.SETTINGS in selected)
            .put("historyExisted", historyRoot.exists()).put("rulesExisted", rulesFile.exists())
        writeJson(File(transaction, "journal.json"), journal)
    }

    /**
     * 写入事务，任何失败均回滚；调用期间禁止取消。
     * @param selected 待覆盖范围。
     */
    private fun commit(selected: Set<BackupScope>) {
        try {
            if (BackupScope.HISTORY in selected) replace(historyRoot, "history")
            if (selected.any { it == BackupScope.FILTER_RULES || it == BackupScope.FOCUS_RULES }) replace(rulesFile, "rules")
            if (BackupScope.SETTINGS in selected) BackupSettings.apply(JSONObject(File(transaction, "new-settings").readText()))
            writeJson(File(transaction, "committed"), JSONObject().put("done", true))
        } catch (error: Exception) {
            try { recover() } catch (rollback: Exception) {
                BackupAccess.requireRecovery()
                throw IllegalStateException("BACKUP_RECOVERY_REQUIRED", rollback)
            }
            throw error
        }
        // 提交标记已落盘，清理失败留给下次启动，不误报还原失败。
        runCatching { cleanupTransaction() }
    }

    /**
     * 保留原件后将已校验的新文件切换为正式数据。
     * @param target 正式文件或目录。
     * @param name 事务资源名称。
     */
    private fun replace(target: File, name: String) {
        if (target.exists()) Files.move(target.toPath(), File(transaction, "old-$name").toPath())
        Files.move(File(transaction, "new-$name").toPath(), target.toPath())
    }

    /** 在仓库加载前重放未完成还原，失败时保留日志供下次重试。 */
    fun recover() {
        if (!transaction.exists()) return
        val journalFile = File(transaction, "journal.json")
        val hasJournal = journalFile.exists() || File(transaction, "journal.json.bak").exists()
        val hasCommit = File(transaction, "committed").exists() || File(transaction, "committed.bak").exists()
        if (hasJournal && !hasCommit) {
            val journal = readJournal(journalFile)
            if (journal != null) {
                if (journal.getBoolean("history")) rollback(historyRoot, "history", journal.getBoolean("historyExisted"))
                if (journal.getBoolean("rules")) rollback(rulesFile, "rules", journal.getBoolean("rulesExisted"))
                if (journal.getBoolean("settings")) BackupSettings.apply(JSONObject(File(transaction, "old-settings").readText()))
            } else {
                // 旧日志首次写入中断，正式文件尚未替换。
                val oldSettings = File(transaction, "old-settings")
                if (oldSettings.isFile) BackupSettings.apply(JSONObject(oldSettings.readText()))
            }
            writeJson(File(transaction, "committed"), JSONObject().put("done", true))
        }
        cleanupTransaction()
    }

    /**
     * 兼容旧系统日志，仅丢弃尚未替换正式文件的残缺日志。
     * @param file 已发布的日志或旧版备份文件。
     * @return 完整事务日志，首次写入中断时为空。
     * @throws IllegalStateException 残缺日志已有原件移入事务，需保留现场。
     */
    private fun readJournal(file: File): JSONObject? = try {
        JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8)).also { journal ->
            listOf("history", "rules", "settings", "historyExisted", "rulesExisted").forEach { journal.getBoolean(it) }
        }
    } catch (error: JSONException) {
        check(!File(transaction, "old-history").exists() && !File(transaction, "old-rules").exists()) { "BACKUP_RECOVERY_REQUIRED" }
        null
    }

    /** 先清理旧数据，再清除日志与终态标记，保持清理中断可重试。 */
    private fun cleanupTransaction() {
        val markers = listOf("journal.json", "journal.json.bak", "committed", "committed.bak")
        for (child in transaction.listFiles().orEmpty().filter { it.name !in markers }) removeOwned(child, transaction)
        for (name in markers) {
            val marker = File(transaction, name)
            check(!marker.exists() || marker.delete())
        }
        check(!transaction.exists() || transaction.delete())
    }

    /** 启动时清理上次进程留下的备份缓存与未发布 ZIP。 */
    fun cleanupAbandoned() {
        context.cacheDir.listFiles().orEmpty().filter { it.name.matches(Regex("backup-[0-9a-f-]{36}")) }
            .forEach { runCatching { removeOwned(it, context.cacheDir) } }
        if (StorageAccess.granted(context)) StorageAccess.backupRoot().listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(".GodotTool_Backup_") && it.name.endsWith(".part") }
            .forEach { runCatching { it.delete() } }
    }

    /**
     * 幂等恢复旧文件，支持回滚本身被进程终止后重试。
     * @param target 正式资源。
     * @param name 日志资源名。
     * @param existed 事务前是否存在。
     */
    private fun rollback(target: File, name: String, existed: Boolean) {
        val old = File(transaction, "old-$name")
        if (old.exists()) {
            if (target.exists()) removeOwned(target, files)
            Files.move(old.toPath(), target.toPath())
        } else if (!existed && target.exists()) removeOwned(target, files)
    }

    /**
     * 校验清单和 ZIP 目录的一一对应关系及大小边界。
     * @param zip 待读取归档。
     * @param json 清单对象。
     * @return 规范文件条目。
     */
    private fun validateManifest(zip: ZipFile, json: JSONObject): List<JSONObject> {
        require(json.getString("format") == FORMAT) { "BACKUP_INVALID" }
        require(json.getInt("version") == VERSION) { "BACKUP_VERSION" }
        require(json.getLong("createdAt") >= 0) { "BACKUP_INVALID" }
        val available = scopes(json)
        val array = json.getJSONArray("files")
        require(array.length() <= MAX_FILES) { "BACKUP_LIMIT" }
        val entries = (0 until array.length()).map { array.getJSONObject(it) }
        val names = hashSetOf<String>()
        var total = 0L
        for (entry in entries) {
            val path = entry.getString("path")
            require(names.add(path) && path == SafeFiles.relative(path)) { "BACKUP_INVALID" }
            val scope = if (path.startsWith("history/")) BackupScope.HISTORY else BackupScope.entries.firstOrNull { it.entry == path }
            require(scope in available) { "BACKUP_INVALID" }
            val size = entry.get("size").toString().toLongOrNull() ?: error("BACKUP_INVALID")
            require(size in 0..entryLimit(path)) { "BACKUP_LIMIT" }
            require(entry.getString("sha256").matches(Regex("[0-9a-f]{64}"))) { "BACKUP_INVALID" }
            total = Math.addExact(total, size)
            val archived = zip.getEntry(path)
            require(archived != null && !archived.isDirectory && archived.size == size) { "BACKUP_INVALID" }
        }
        for (scope in available.filter { it != BackupScope.HISTORY }) require(scope.entry in names) { "BACKUP_INVALID" }
        val actual = zip.entries().asSequence().map { it.name }.toList()
        require(actual.size == names.size + 1 && actual.toSet() == names + MANIFEST) { "BACKUP_INVALID" }
        return entries
    }

    /**
     * 严格读取非空范围列表。
     * @param json 备份清单。
     * @return 无重复的已知范围。
     */
    private fun scopes(json: JSONObject): Set<BackupScope> {
        val array = json.getJSONArray("scopes")
        require(array.length() in 1..BackupScope.entries.size) { "BACKUP_INVALID" }
        val values = (0 until array.length()).map { BackupScope.valueOf(array.getString(it)) }
        require(values.distinct().size == values.size) { "BACKUP_INVALID" }
        return values.toSet()
    }

    /**
     * 有界读取清单，避免外部 ZIP 撑满内存。
     * @param zip 备份归档。
     * @return 清单原文，用于确认后检测更换。
     */
    private fun readManifest(zip: ZipFile): String {
        require(zip.size() in 1..MAX_FILES + 1) { "BACKUP_LIMIT" }
        val entry = zip.getEntry(MANIFEST) ?: error("BACKUP_INVALID")
        require(entry.size in 1..MANIFEST_LIMIT.toLong()) { "BACKUP_LIMIT" }
        return zip.getInputStream(entry).use { input ->
            val output = java.io.ByteArrayOutputStream()
            SafeFiles.copy(input, output, MANIFEST_LIMIT.toLong())
            output.toString(Charsets.UTF_8.name())
        }
    }

    /**
     * 按当前业务格式限制每种归档文件。
     * @param path 归档相对路径。
     * @return 最大允许字节数。
     */
    private fun entryLimit(path: String): Long = when {
        path == "settings.json" -> 64L * 1024
        path in setOf("filter-rules.json", "focus-rules.json") -> 1024L * 1024
        path.matches(Regex("history/[0-9a-f-]{36}/(record\\.json|result\\.bin|index\\.json|change\\.txt|stars\\.json)")) -> when (path.substringAfterLast('/')) {
            "record.json" -> 1024L * 1024
            "index.json", "stars.json" -> SnapshotFormat.MAX_BYTES
            else -> SnapshotFormat.MAX_BYTES * 4
        }
        else -> error("BACKUP_INVALID")
    }

    companion object {
        private const val FORMAT = "GodotToolBackup"
        private const val VERSION = 1
        private const val MANIFEST = "manifest.json"
        private const val MANIFEST_LIMIT = 8 * 1024 * 1024
        private const val MAX_FILES = 50000
        private const val SPACE_MARGIN = 4L * 1024 * 1024

        /**
         * 原子写入并同步事务元数据。
         * 使用示例：writeJson(file, JSONObject())。
         * @param file 私有目标文件。
         * @param json 待持久化内容。
         */
        private fun writeJson(file: File, json: JSONObject) {
            val temporary = File(file.parentFile, ".${file.name}.part")
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(json.toString().toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }

        /**
         * 检查当前文件系统空间。
         * 使用示例：requireSpace(stage, bytes)。
         * @param directory 目标文件系统目录。
         * @param bytes 所需字节。
         */
        private fun requireSpace(directory: File, bytes: Long) {
            require(bytes >= 0 && directory.usableSpace > bytes) { "BACKUP_SPACE" }
        }

        /**
         * 仅清理指定所有者目录中的直接子资源。
         * 使用示例：removeOwned(stage, context.cacheDir)。
         * @param target 应用拥有的文件或目录。
         * @param owner 允许的父目录。
         */
        private fun removeOwned(target: File, owner: File) {
            require(target.canonicalFile.parentFile == owner.canonicalFile) { "BACKUP_INVALID" }
            check(!target.exists() || target.deleteRecursively()) { "BACKUP_CLEANUP" }
        }
    }
}
