package com.winter.godot.tool.data

import com.winter.godot.core.io.ModelDependencies
import com.winter.godot.core.io.SafeFiles
import com.winter.godot.core.model.CoreFailure
import com.winter.godot.core.model.CoreProgress
import com.winter.godot.core.model.RecoverySelection
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 将原始资源与恢复产物按真实 Godot 路径合并为工程。
 * @author Tingdong
 * @date 2026-11-21
 */
class ProjectAssembler {
    /**
     * 工程整理的实际文件计数。
     * @author Tingdong
     * @date 2026-11-21
     */
    data class Result(val files: Int, val scripts: Int, val bytes: Long)

    /**
     * 选取内容并原子提交源码工程，转换成功时移除旧字节码和 remap。
     * 使用示例：assembler.assemble(workspace, selection, {}, {})。
     * @param directory 本次任务目录。
     * @param selection 用户选择。
     * @param check 取消检查。
     * @param progress 整理进度。
     * @param fallbackResources 转换失败时仍需保留的原始资源路径。
     * @return 工程内的实际计数。
     */
    fun assemble(directory: File, selection: RecoverySelection, check: () -> Unit, progress: (CoreProgress) -> Unit,
        fallbackResources: List<String> = emptyList()): Result {
        if (!selection.hasContent) throw CoreFailure("EMPTY_SELECTION", "No recovery content selected")
        val raw = collect(contentDirectory(directory, "original", "raw"), check)
        val restored = collect(File(directory, "recovered"), check)
        val modelDependencies = ModelDependencies.collect(raw + restored, selection::includes, check)
        val includes: (String) -> Boolean = { selection.includes(it) || it in modelDependencies }
        val recovered = restored.filterKeys(includes)
        val candidates = linkedMapOf<String, File>()
        for ((path, file) in raw) {
            check()
            if (!includes(path)) continue
            val extension = path.substringAfterLast('.', "").lowercase()
            val converted = when (extension) {
                "gdc", "gde" -> path.substringBeforeLast('.') + ".gd"
                "scn" -> path.substringBeforeLast('.') + ".tscn"
                "res" -> path.substringBeforeLast('.') + ".tres"
                "binary" -> if (path == "project.binary") "project.godot" else ""
                "remap" -> path.removeSuffix(".remap")
                else -> ""
            }
            if (converted.isNotEmpty() && (converted in recovered || converted in raw && includes(converted))) continue
            candidates[path] = file
        }
        // 仅保留导入器明确报告仍需使用的缓存资源，常规缓存不进入源码工程。
        for (path in fallbackResources) {
            check()
            val name = SafeFiles.relative(path)
            raw[name]?.let { candidates[name] = it }
        }
        candidates.putAll(recovered)
        if (candidates.isEmpty()) throw CoreFailure("NO_MATCHING_CONTENT", "The selected content was not found in this project")
        val stage = File(directory, ".project-staging")
        val target = File(directory, "unpacked")
        if (target.exists() || stage.exists()) throw CoreFailure("OUTPUT_EXISTS", "Project output already exists")
        if (!stage.mkdir()) throw CoreFailure("WRITE_FAILED", "Cannot create project staging directory")
        try {
            val total = candidates.values.fold(0L) { sum, file -> Math.addExact(sum, file.length()) }
            var done = 0L
            candidates.entries.forEachIndexed { index, (path, source) ->
                check()
                val file = SafeFiles.resolve(stage, path)
                file.parentFile?.mkdirs()
                val size = source.length()
                val copied = linkOrCopy(source, file, size, check) { bytes ->
                    progress(CoreProgress("assemble", path, done + bytes, total, index, candidates.size))
                }
                if (copied != size) throw CoreFailure("SIZE_CHANGED", path)
                done += copied
            }
            check()
            try {
                Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(stage.toPath(), target.toPath())
            }
            progress(CoreProgress("assemble", bytes = done, totalBytes = total, files = candidates.size, totalFiles = candidates.size))
            return Result(candidates.size, candidates.keys.count { it.endsWith(".gd", true) || it.endsWith(".cs", true) }, done)
        } catch (error: Throwable) {
            removeOwned(directory, stage)
            throw error
        }
    }

    /**
     * 复用已停止写入的资源，文件系统不支持硬链接时回退复制。
     * @param source 已完成恢复的源文件。
     * @param target 工程暂存文件。
     * @param size 预期大小。
     * @param check 取消检查。
     * @param progress 已整理字节数。
     * @return 已整理字节数。
     * @throws CoreFailure 空间不足或文件大小变化。
     */
    private fun linkOrCopy(source: File, target: File, size: Long, check: () -> Unit, progress: (Long) -> Unit): Long {
        check()
        val linked = try {
            Files.createLink(target.toPath(), source.toPath())
            true
        } catch (_: UnsupportedOperationException) { false }
        catch (_: IOException) { false }
        catch (_: SecurityException) { false }
        if (linked) {
            if (target.length() != size) throw CoreFailure("SIZE_CHANGED", source.name)
            progress(size)
            return size
        }
        if (target.parentFile!!.usableSpace < size) throw CoreFailure("NO_SPACE", "Insufficient space for recovered project")
        return source.inputStream().use { input -> target.outputStream().use { output ->
            try { SafeFiles.copy(input, output, size, check, progress) }
            catch (error: CoreFailure) {
                throw CoreFailure(error.code, "${source.name}: ${error.message}; expected=$size, actual=${source.length()}")
            }
        } }
    }

    /**
     * 工程提交后移除中间产物，按选项保留原始副本。
     * @param directory 已创建工程的任务目录。
     * @param selection 用户选择。
     */
    fun finish(directory: File, selection: RecoverySelection) {
        check(File(directory, "unpacked").isDirectory)
        removeOwned(directory, File(directory, "recovered"))
        val original = contentDirectory(directory, "original", "raw")
        if (!selection.keepRaw) removeOwned(directory, original)
        else if (original.name == "raw" && original.exists() && !original.renameTo(File(directory, "original")))
            throw CoreFailure("WRITE_FAILED", "Cannot finalize original resources")
        for (name in listOf(".staging", ".nm-staging")) removeOwned(directory, File(directory, name))
    }

    /**
     * 将工具报告收纳到工程外侧，保持项目根目录纯净。
     * @param directory 任务目录。
     */
    fun organizeReports(directory: File) {
        val reports = SafeFiles.resolve(directory, "reports").apply { mkdirs() }
        for (file in directory.listFiles().orEmpty()) {
            if (!file.isFile || file.name in setOf("inputs.json", "request-options.json", "engine-result.json", "published-result.json", "workspace.json", "report.txt", "diff.gt", RecoveryReport.FILE_NAME)) continue
            if (file.extension !in setOf("json", "log", "txt")) continue
            val destination = SafeFiles.resolve(reports, file.name)
            if (destination.exists() || !file.renameTo(destination)) throw CoreFailure("WRITE_FAILED", "Cannot organize report: ${file.name}")
        }
    }

    /**
     * 有界收集目录内文件，拒绝符号链接越界。
     * @param root 文件根目录。
     * @param check 取消检查。
     * @return 原始相对路径到文件的映射。
     */
    private fun collect(root: File, check: () -> Unit): Map<String, File> {
        if (!root.isDirectory) return emptyMap()
        val result = linkedMapOf<String, File>()
        val boundary = root.canonicalFile.toPath()
        root.walkTopDown().onEnter {
            check()
            if (!it.canonicalFile.toPath().startsWith(boundary)) throw CoreFailure("UNSAFE_PATH", "Project directory escaped root")
            true
        }.filter { it.isFile }.forEach { file ->
            check()
            if (!file.canonicalFile.toPath().startsWith(boundary)) throw CoreFailure("UNSAFE_PATH", "Project file escaped root")
            if (result.size >= 200000) throw CoreFailure("ENTRY_LIMIT", "Project contains too many files")
            result[SafeFiles.relative(file.relativeTo(root).invariantSeparatorsPath)] = file
        }
        return result
    }

    /**
     * 删除任务内由工具创建的指定中间目录。
     * @param directory 任务根目录。
     * @param target 待回收目录。
     */
    private fun removeOwned(directory: File, target: File) {
        check(target.canonicalFile.parentFile == directory.canonicalFile)
        check(target.name in setOf("original", "raw", "recovered", ".staging", ".nm-staging", ".project-staging"))
        if (target.exists() && !target.deleteRecursively()) throw CoreFailure("DELETE_FAILED", target.name)
    }

    companion object {
        /**
         * 优先使用新版内容目录，兼容旧版及处理中目录。
         * 使用示例：ProjectAssembler.contentDirectory(workspace, "unpacked", "project", "recovered")。
         * @param directory 任务目录。
         * @param name 新目录名称。
         * @param legacyNames 旧目录名称。
         * @return 已有目录或新版目标目录。
         */
        fun contentDirectory(directory: File, name: String, vararg legacyNames: String): File =
            (listOf(name) + legacyNames).map { SafeFiles.resolve(directory, it) }.firstOrNull { it.isDirectory }
                ?: SafeFiles.resolve(directory, name)

        /**
         * 定位新版报告目录，兼容旧版直接放在结果根目录的报告。
         * 使用示例：ProjectAssembler.reportFile(output, "result.json")。
         * @param directory 任务根目录。
         * @param name 报告名称。
         * @return 报告的实际位置。
         */
        fun reportFile(directory: File, name: String): File {
            val reports = SafeFiles.resolve(directory, "reports")
            val internal = SafeFiles.resolve(reports, name)
            val previous = SafeFiles.resolve(directory, name)
            return when {
                internal.isFile -> internal
                previous.isFile -> previous
                reports.isDirectory -> internal
                else -> previous
            }
        }
    }
}
