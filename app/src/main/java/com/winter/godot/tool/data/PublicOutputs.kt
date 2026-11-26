package com.winter.godot.tool.data

import com.winter.godot.core.io.SafeFiles
import com.winter.godot.core.model.CoreFailure
import com.winter.godot.core.model.CoreProgress
import com.winter.godot.tool.feature.compare.SnapshotFormat
import com.winter.godot.tool.feature.compare.SnapshotGenerator
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 检查公开产物是否可读取，排除空占位并兼容旧目录产物。
 * 使用示例：isOutputAvailable(path)。
 * @param path 任务保存的完整产物路径。
 * @return 是否存在可读取的产物，路径无效或权限不足时返回 false。
 */
fun isOutputAvailable(path: String): Boolean {
    if (path.isBlank()) return false
    return try {
        val output = File(path)
        output.isAbsolute && output.canRead() && (output.isDirectory || output.isFile && output.length() > 0L)
    } catch (_: SecurityException) {
        false
    }
}

/**
 * 将私有工作区原子发布为日期次数 ZIP。
 * @author Tingdong
 * @date 2026-11-24
 */
class PublicOutputs(private val root: File, private val describeReason: (String) -> String = { it }) {
    /**
     * 预留不覆盖历史文件或旧目录的 ZIP 名称。
     * 使用示例：outputs.allocate(System.currentTimeMillis())。
     * @param time 保存时间。
     * @return 本任务独占的空 ZIP 占位。
     */
    fun allocate(time: Long): File {
        if (!root.isDirectory && !root.mkdirs()) throw CoreFailure("WRITE_FAILED", "Cannot create output root")
        File(root, ".nomedia").createNewFile()
        val date = SimpleDateFormat("yyMMdd", Locale.ROOT).format(Date(time))
        val pattern = Regex("${date}_(\\d+)(?:\\.zip)?")
        var count = root.listFiles().orEmpty().mapNotNull { pattern.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
        while (count > 0) {
            val target = SafeFiles.resolve(root, "${date}_$count.zip")
            if (!File(root, "${date}_$count").exists() && target.createNewFile()) return target
            count++
        }
        throw CoreFailure("WRITE_FAILED", "Output counter exhausted")
    }

    /**
     * 压缩产物与报告，成功后替换本任务的阶段快照。
     * @param workspace 私有工作区。
     * @param time 保存时间。
     * @param check 取消检查。
     * @param progress 未压缩字节进度。
     * @param destination 本任务此前预留或发布的 ZIP。
     * @param checkpoint 是否为恢复前的阶段保存。
     * @return 已完整提交的 ZIP。
     */
    fun publish(workspace: File, time: Long, check: () -> Unit, progress: (CoreProgress) -> Unit, destination: File? = null,
        checkpoint: Boolean = false): File {
        val boundary = workspace.canonicalFile.toPath()
        val files = mutableListOf<Pair<File, String>>()
        val options = File(workspace, "request-options.json").takeIf { it.isFile && it.length() in 1..8192 }?.let { JSONObject(it.readText()) }
        val retained = if (options?.optBoolean("snapshot") == true && !File(workspace, "unpacked").isDirectory)
            SnapshotGenerator.retainedFiles(workspace, recoverySelectionFromJson(options), emptyList(), check) else null
        for ((folder, source) in listOf(
            "unpacked" to ProjectAssembler.contentDirectory(workspace, "unpacked", "project", "recovered"),
            "original" to ProjectAssembler.contentDirectory(workspace, "original", "raw"),
        )) {
            if (!source.exists()) continue
            source.walkTopDown().onEnter {
                check()
                if (!it.canonicalFile.toPath().startsWith(boundary)) throw CoreFailure("UNSAFE_PATH", "Output directory escaped workspace")
                true
            }.filter { it.isFile }.forEach {
                check()
                if (!it.canonicalFile.toPath().startsWith(boundary)) throw CoreFailure("UNSAFE_PATH", "Output file escaped workspace")
                if (files.size >= 200000) throw CoreFailure("ENTRY_LIMIT", "Too many output files")
                if (retained != null && "${source.name}/${it.relativeTo(source).invariantSeparatorsPath}" !in retained) return@forEach
                files += it to SafeFiles.relative("$folder/${it.relativeTo(source).invariantSeparatorsPath}")
            }
        }
        files += RecoveryReport.write(workspace, check, describeReason) to RecoveryReport.FILE_NAME
        File(workspace, SnapshotFormat.FILE_NAME).takeIf { it.isFile }?.let { files += it to SnapshotFormat.FILE_NAME }
        val target = destination ?: allocate(time)
        if (target.canonicalFile.parentFile != root.canonicalFile || !target.name.matches(Regex("\\d{6}_\\d+\\.zip")))
            throw CoreFailure("UNSAFE_PATH", "Invalid output archive")
        val temporary = File(root, ".${target.name}-${UUID.randomUUID()}.part")
        val snapshots = files.map { (file, name) -> Triple(file, name, file.length()) }
        val total = snapshots.fold(0L) { sum, item -> Math.addExact(sum, item.third) }
        try {
            if (root.usableSpace < total + 1024 * 1024) throw CoreFailure("NO_SPACE", "Insufficient output capacity")
            var done = 0L
            ZipOutputStream(temporary.outputStream().buffered(128 * 1024)).use { zip ->
                snapshots.forEachIndexed { index, (file, relative, expected) ->
                    check()
                    zip.setLevel(if (!checkpoint && file.extension.lowercase(Locale.ROOT) in compressedExtensions)
                        Deflater.NO_COMPRESSION else Deflater.BEST_SPEED)
                    zip.putNextEntry(ZipEntry(relative))
                    val copied = file.inputStream().use { input ->
                        val update: (Long) -> Unit = { bytes -> progress(CoreProgress("publish", relative, done + bytes, total, index, files.size)) }
                        try {
                            if (file.extension == "log") SafeFiles.snapshot(input, zip, expected, check, update)
                            else SafeFiles.copy(input, zip, expected, check, update)
                        } catch (error: CoreFailure) {
                            throw CoreFailure(error.code, "$relative: ${error.message}; expected=$expected, actual=${file.length()}")
                        }
                    }
                    if (copied != expected) throw CoreFailure("SIZE_CHANGED", relative)
                    zip.closeEntry()
                    done += copied
                }
            }
            check()
            if (!temporary.renameTo(target)) throw CoreFailure("WRITE_FAILED", "Cannot commit ZIP output")
            progress(CoreProgress("publish", bytes = done, totalBytes = total, files = files.size, totalFiles = files.size))
            return target
        } catch (error: Throwable) {
            if (target.length() == 0L) target.delete()
            throw error
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private val compressedExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "ogg", "mp3", "flac", "aac", "m4a", "opus",
            "mp4", "webm", "ogv", "mov", "mkv", "zip", "apk", "gz", "xz", "zst", "woff2")
    }
}
