package com.winter.godot.tool.data

import com.winter.godot.core.io.SafeFiles
import com.winter.godot.core.model.CoreFailure
import com.winter.godot.core.model.CoreProgress
import com.winter.godot.core.model.RecoverySelection
import java.io.File
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
        val raw = collect(File(directory, "raw"), check)
        val recovered = collect(File(directory, "recovered"), check).filterKeys(selection::includes)
        val candidates = linkedMapOf<String, File>()
        for ((path, file) in raw) {
            check()
            if (!selection.includes(path)) continue
            val extension = path.substringAfterLast('.', "").lowercase()
            val converted = when (extension) {
                "gdc", "gde" -> path.substringBeforeLast('.') + ".gd"
                "scn" -> path.substringBeforeLast('.') + ".tscn"
                "res" -> path.substringBeforeLast('.') + ".tres"
                "binary" -> if (path == "project.binary") "project.godot" else ""
                "remap" -> path.removeSuffix(".remap")
                else -> ""
            }
            if (converted.isNotEmpty() && (converted in recovered || converted in raw && selection.includes(converted))) continue
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
        val target = File(directory, "project")
        if (target.exists() || stage.exists()) throw CoreFailure("OUTPUT_EXISTS", "Project output already exists")
        if (!stage.mkdir()) throw CoreFailure("WRITE_FAILED", "Cannot create project staging directory")
        try {
            val total = candidates.values.fold(0L) { sum, file -> Math.addExact(sum, file.length()) }
            if (stage.usableSpace < total) throw CoreFailure("NO_SPACE", "Insufficient space for recovered project")
            var done = 0L
            candidates.entries.forEachIndexed { index, (path, source) ->
                check()
                val file = SafeFiles.resolve(stage, path)
                file.parentFile?.mkdirs()
                val size = source.length()
                val copied = source.inputStream().use { input -> file.outputStream().use { output ->
                    try {
                        SafeFiles.copy(input, output, size, check) { bytes -> progress(CoreProgress("assemble", path, done + bytes, total, index, candidates.size)) }
                    } catch (error: CoreFailure) {
                        throw CoreFailure(error.code, "$path: ${error.message}; expected=$size, actual=${source.length()}")
                    }
                } }
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
     * 工程提交后移除中间产物，按选项保留原始副本。
     * @param directory 已创建工程的任务目录。
     * @param selection 用户选择。
     */
    fun finish(directory: File, selection: RecoverySelection) {
        check(File(directory, "project").isDirectory)
        removeOwned(directory, File(directory, "recovered"))
        if (!selection.keepRaw) removeOwned(directory, File(directory, "raw"))
        for (name in listOf(".staging", ".nm-staging")) removeOwned(directory, File(directory, name))
    }

    /**
     * 将工具报告收纳到工程外侧，保持项目根目录纯净。
     * @param directory 任务目录。
     */
    fun organizeReports(directory: File) {
        val reports = SafeFiles.resolve(directory, "reports").apply { mkdirs() }
        for (file in directory.listFiles().orEmpty()) {
            if (!file.isFile || file.name in setOf("inputs.json", "request-options.json", "engine-result.json", "workspace.json")) continue
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
        check(target.name in setOf("raw", "recovered", ".staging", ".nm-staging", ".project-staging"))
        if (target.exists() && !target.deleteRecursively()) throw CoreFailure("DELETE_FAILED", target.name)
    }

    companion object {
        /**
         * 定位新版报告目录，兼容旧版直接放在结果根目录的报告。
         * 使用示例：ProjectAssembler.reportFile(output, "result.json")。
         * @param directory 任务根目录。
         * @param name 报告名称。
         * @return 报告的实际位置。
         */
        fun reportFile(directory: File, name: String): File = SafeFiles.resolve(
            if (File(directory, "reports").isDirectory) SafeFiles.resolve(directory, "reports") else directory, name)
    }
}
