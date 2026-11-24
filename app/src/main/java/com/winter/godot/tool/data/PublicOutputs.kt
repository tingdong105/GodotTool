package com.winter.godot.tool.data

import com.winter.godot.core.io.SafeFiles
import com.winter.godot.core.model.CoreFailure
import com.winter.godot.core.model.CoreProgress
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 将私有工作区原子发布为日期次数 ZIP。
 * @author Tingdong
 * @date 2026-11-24
 */
class PublicOutputs(private val root: File) {
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
     * @return 已完整提交的 ZIP。
     */
    fun publish(workspace: File, time: Long, check: () -> Unit, progress: (CoreProgress) -> Unit, destination: File? = null): File {
        val boundary = workspace.canonicalFile.toPath()
        val files = mutableListOf<File>()
        for (folder in listOf("raw", "recovered", "project", "reports")) {
            val source = File(workspace, folder)
            if (!source.exists()) continue
            source.walkTopDown().onEnter {
                check()
                if (!it.canonicalFile.toPath().startsWith(boundary)) throw CoreFailure("UNSAFE_PATH", "Output directory escaped workspace")
                true
            }.filter { it.isFile }.forEach {
                check()
                if (!it.canonicalFile.toPath().startsWith(boundary)) throw CoreFailure("UNSAFE_PATH", "Output file escaped workspace")
                if (files.size >= 200000) throw CoreFailure("ENTRY_LIMIT", "Too many output files")
                files += it
            }
        }
        for (name in listOf("raw-index.json", "result.json", "recovery-report.txt", "engine.log", "bootstrap.log", "engine-startup.log", "engine-logcat.log"))
            File(workspace, name).takeIf { it.isFile }?.let(files::add)
        if (files.isEmpty()) throw CoreFailure("EMPTY_EXPORT", "No recovered files to save")
        val target = destination ?: allocate(time)
        if (target.canonicalFile.parentFile != root.canonicalFile || !target.name.matches(Regex("\\d{6}_\\d+\\.zip")))
            throw CoreFailure("UNSAFE_PATH", "Invalid output archive")
        val temporary = File(root, ".${target.name}-${UUID.randomUUID()}.part")
        val snapshots = files.map { it to it.length() }
        val total = snapshots.fold(0L) { sum, item -> Math.addExact(sum, item.second) }
        try {
            if (root.usableSpace < total + 1024 * 1024) throw CoreFailure("NO_SPACE", "Insufficient output capacity")
            var done = 0L
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                snapshots.forEachIndexed { index, (file, expected) ->
                    check()
                    val relative = SafeFiles.relative(file.relativeTo(workspace).invariantSeparatorsPath)
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
}
