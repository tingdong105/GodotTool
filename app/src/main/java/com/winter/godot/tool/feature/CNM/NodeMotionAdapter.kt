package com.winter.godot.tool.feature.CNM

import com.winter.godot.core.io.ArchiveSet
import com.winter.godot.core.io.GodotDependencies
import com.winter.godot.core.io.ModelDependencies
import com.winter.godot.core.io.SafeFiles
import com.winter.godot.core.model.CoreFailure
import com.winter.godot.core.model.CoreProgress
import com.winter.godot.core.model.ResourceEntry
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NodeMotion 的自动识别、解密与资源校验。
 * @author Tingdong
 * @date 2026-11-21
 */
object NodeMotionAdapter {
    const val PACKAGE_NAME = "com.shallwaystudio.nodemotion"
    private const val INDEX = "assets/assets.sparsepck"
    private const val LIBRARY = "lib/arm64-v8a/libgodot_android.so"
    private const val MAX_INDEX = 128 * 1024 * 1024

    /**
     * 识别 NM 风格的定制资源包。
     * @param sources 已打开的 APK 集合。
     * @param check 取消检查。
     * @return 是否需要 NM 自动适配。
     * @throws CoreFailure 缺少可分析的引擎文件。
     */
    fun probe(sources: ArchiveSet, check: () -> Unit = {}, packageName: String? = null): Boolean {
        check()
        val expected = packageName == PACKAGE_NAME
        if (!sources.contains(INDEX)) {
            if (expected) throw CoreFailure("NM_BAD_INDEX", "NodeMotion resource index is missing")
            return false
        }
        val header = sources.open(INDEX).use { readExact(it, 8) }
        val magic = little(header).int
        if (magic == 0x43504447 || little(header).getInt(4) != 4) {
            if (expected) throw CoreFailure("NM_BUILD_UNSUPPORTED", "Unsupported NodeMotion resource layout")
            return false
        }
        if (!sources.contains(LIBRARY)) throw CoreFailure("NM_BAD_ENGINE", "Missing arm64 engine")
        return true
    }

    /**
     * 解密索引与资源并逐项核对大小和 MD5。
     * @param sources APK 与分包集合。
     * @param output 明文资源目录。
     * @param check 取消检查。
     * @param progress 字节进度回调。
     * @param include 只解密所选内容和解析依赖。
     * @param ensureVersionProbe 为缺少脚本的精简选择保留一个版本探针。
     * @return 已通过校验的资源条目。
     * @throws CoreFailure 索引、密文或资源校验失败。
     */
    fun extract(sources: ArchiveSet, output: File, check: () -> Unit = {}, progress: (CoreProgress) -> Unit = {}, include: (String) -> Boolean = { true },
        ensureVersionProbe: Boolean = false): List<ResourceEntry> {
        val pack = sources.open(INDEX).use { input ->
            ByteArrayOutputStream().also { SafeFiles.copy(input, it, MAX_INDEX.toLong(), check) }.toByteArray()
        }
        val parent = requireNotNull(output.absoluteFile.parentFile).apply { mkdirs() }
        val engine = File.createTempFile(".cnm-engine-", ".so", parent)
        val directory = try {
            progress(CoreProgress("decrypt"))
            sources.open(LIBRARY).use { input -> engine.outputStream().use { sink -> SafeFiles.copy(input, sink, 256L * 1024 * 1024, check) } }
            NmKeyResolver.resolve(engine, pack, check)
        } finally { engine.delete() }
        val key = directory.key
        try {
            val count = directory.count
            val index = little(directory.plain)
            val names = HashSet<String>()
            val entries = ArrayList<ResourceEntry>(count)
            repeat(count) {
                check()
                if (index.remaining() < 4) throw CoreFailure("NM_BAD_INDEX", "Truncated path length")
                val length = index.int
                if (length !in 1..16384 || length > index.remaining() - 36) throw CoreFailure("NM_BAD_INDEX", "Invalid path length")
                val bytes = ByteArray(length).also(index::get)
                val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
                if ((end until bytes.size).any { bytes[it] != 0.toByte() }) throw CoreFailure("NM_BAD_INDEX", "Invalid path padding")
                val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, end)).toString()
                val normalized = SafeFiles.relative(name)
                if (!names.add(normalized)) throw CoreFailure("DUPLICATE_PATH", normalized)
                index.long
                val size = index.long
                val md5 = ByteArray(16).also(index::get)
                val flags = index.int
                if (size < 0 || flags and 1.inv() != 0) throw CoreFailure("NM_BAD_INDEX", "Unsupported entry flags or size")
                entries += ResourceEntry(name, size, SafeFiles.hex(md5), flags and 1 != 0)
            }
            if (index.hasRemaining()) throw CoreFailure("NM_BAD_INDEX", "Unexpected trailing index data")
            val mapping = findMapping(sources, entries, directory.salt)
            val selected = entries.filter { include(it.path) }.toMutableList()
            if (ensureVersionProbe && selected.none { it.path.endsWith(".gdc", true) })
                entries.firstOrNull { it.path.endsWith(".gdc", true) }?.let(selected::add)
            return extractSelected(sources, output, entries, selected, key, mapping, check, progress)
        } finally {
            key.fill(0)
        }
    }

    /**
     * 分批并行提取，依赖发现与进度仅由调用线程管理。
     * @param sources 已打开的资源归档。
     * @param output 明文目录。
     * @param entries 完整资源清单。
     * @param selected 初始选择及随后发现的依赖。
     * @param key 本次任务临时密钥。
     * @param mapping 路径前缀与盐。
     * @param check 取消检查。
     * @param progress 调用线程进度回调。
     * @return 按选择和依赖发现顺序排列的资源。
     * @throws CoreFailure 资源损坏或空间不足。
     */
    private fun extractSelected(sources: ArchiveSet, output: File, entries: List<ResourceEntry>, selected: MutableList<ResourceEntry>,
        key: ByteArray, mapping: Pair<String, ByteArray>, check: () -> Unit, progress: (CoreProgress) -> Unit): List<ResourceEntry> {
        val parallelism = 2
        val byPath = entries.associateBy { SafeFiles.relative(it.path) }
        val scheduled = selected.mapTo(hashSetOf()) { SafeFiles.relative(it.path) }
        var total = selected.fold(0L) { sum, entry -> Math.addExact(sum, entry.size) }
        output.mkdirs()
        if (output.usableSpace < total) throw CoreFailure("NO_SPACE", "Insufficient workspace capacity")
        val staging = File(output.parentFile, ".nm-staging").apply { mkdirs() }
        val stopped = AtomicBoolean(false)
        val copied = AtomicLong()
        val workerCheck: () -> Unit = {
            if (stopped.get() || Thread.currentThread().isInterrupted) throw CancellationException("Canceled")
            check()
        }
        val executor = Executors.newFixedThreadPool(parallelism) { action -> Thread(action, "NmResourceWorker") }
        val completion = ExecutorCompletionService<Pair<String, File>>(executor)
        try {
            var position = 0
            while (position < selected.size) {
                check()
                val batch = selected.subList(position, minOf(position + parallelism, selected.size)).toList()
                val futures = batch.map { entry -> completion.submit(Callable {
                    var previous = 0L
                    val target = extractResource(sources, output, staging, entry, key, mapping, workerCheck) { bytes ->
                        copied.addAndGet(bytes - previous)
                        previous = bytes
                    }
                    entry.path to target
                }) }
                val ready = mutableMapOf<String, File>()
                try {
                    while (ready.size < batch.size) {
                        check()
                        val finished = completion.poll(120, TimeUnit.MILLISECONDS)
                        if (finished != null) {
                            val (path, file) = try { finished.get() }
                            catch (error: ExecutionException) { throw (error.cause ?: error) }
                            ready[path] = file
                        }
                        progress(CoreProgress("extract", batch.first().path, copied.get(), total, position, selected.size))
                    }
                    for (entry in batch) {
                        check()
                        val target = ready.getValue(entry.path)
                        val dependencies = when {
                            target.extension in setOf("import", "remap") && target.length() <= 2097152 -> GodotDependencies.paths(target.readText())
                            ModelDependencies.supports(entry.path) -> ModelDependencies.paths(entry.path, target)
                            else -> emptySet()
                        }
                        for (path in dependencies) {
                            val dependency = byPath[path] ?: continue
                            if (scheduled.add(path)) {
                                selected += dependency
                                total = Math.addExact(total, dependency.size)
                                if (output.usableSpace < total - copied.get()) throw CoreFailure("NO_SPACE", "Insufficient space for selected dependencies")
                            }
                        }
                        position++
                        progress(CoreProgress("extract", entry.path, copied.get(), total, position, selected.size))
                    }
                } finally {
                    futures.forEach { it.cancel(true) }
                }
            }
            return selected
        } finally {
            stopped.set(true)
            executor.shutdownNow()
            var interrupted = false
            while (true) {
                try { if (executor.awaitTermination(120, TimeUnit.MILLISECONDS)) break }
                catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * 提取单个资源，校验成功后提交临时文件。
     * @param sources 资源归档。
     * @param output 明文目录。
     * @param staging 临时文件目录。
     * @param entry 当前资源。
     * @param key 临时密钥。
     * @param mapping 路径前缀与盐。
     * @param check 取消检查。
     * @param progress 当前资源已写入字节数。
     * @return 已校验并提交的资源。
     * @throws CoreFailure 大小、摘要或写入验证失败。
     */
    private fun extractResource(sources: ArchiveSet, output: File, staging: File, entry: ResourceEntry, key: ByteArray,
        mapping: Pair<String, ByteArray>, check: () -> Unit, progress: (Long) -> Unit): File {
        check()
        val resourcePath = mapping.first + entry.path.removePrefix("res://")
        val hash = MessageDigest.getInstance("SHA-256").digest(resourcePath.toByteArray(Charsets.UTF_8) + mapping.second)
        val asset = "assets/${SafeFiles.hex(hash)}"
        val target = SafeFiles.resolve(output, entry.path)
        if (target.exists()) throw CoreFailure("DUPLICATE_PATH", entry.path)
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile("resource-", ".part", staging)
        val digest = MessageDigest.getInstance("MD5")
        try {
            temporary.outputStream().use { file ->
                val sink = object : OutputStream() {
                    /** 写入单字节并同步摘要。 */
                    override fun write(value: Int) { file.write(value); digest.update(value.toByte()) }
                    /** 写入数据块并同步摘要。 */
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { file.write(bytes, offset, length); digest.update(bytes, offset, length) }
                }
                sources.open(asset).use { input ->
                    val actual = if (entry.encrypted) decrypt(input, sink, key, entry.size, check, progress)
                    else SafeFiles.copy(input, sink, entry.size, check, progress)
                    if (actual != entry.size) throw CoreFailure("CHECKSUM", entry.path)
                }
            }
            if (SafeFiles.hex(digest.digest()) != entry.md5) throw CoreFailure("CHECKSUM", entry.path)
            check()
            if (!temporary.renameTo(target)) throw CoreFailure("WRITE_FAILED", entry.path)
            return target
        } finally {
            temporary.delete()
        }
    }

    /**
     * 根据真实资源名选择路径前缀与盐编码。
     * @param sources APK 集合。
     * @param entries 已校验目录。
     * @param salt 原始盐字段。
     * @return 路径前缀与编码后的盐。
     */
    private fun findMapping(sources: ArchiveSet, entries: List<ResourceEntry>, salt: ByteArray): Pair<String, ByteArray> {
        val salts = listOf(String(salt, Charsets.ISO_8859_1).toByteArray(Charsets.UTF_8), salt)
        for (prefix in listOf("res://", "")) for (candidate in salts) {
            if (entries.take(16).all { entry ->
                val hash = MessageDigest.getInstance("SHA-256").digest((prefix + entry.path.removePrefix("res://")).toByteArray(Charsets.UTF_8) + candidate)
                sources.contains("assets/${SafeFiles.hex(hash)}")
            }) return prefix to candidate
        }
        throw CoreFailure("NM_BAD_INDEX", "Resource name mapping did not match APK entries")
    }

    /**
     * 解密 Godot 风格 CFB 数据块并核对内嵌摘要。
     * @param input 密文流。
     * @param output 明文输出流。
     * @param key 临时密钥。
     * @param limit 最大明文长度。
     * @param check 取消检查。
     * @param progress 明文累计进度。
     * @return 明文长度。
     */
    private fun decrypt(input: InputStream, output: OutputStream, key: ByteArray, limit: Long, check: () -> Unit, progress: (Long) -> Unit = {}): Long {
        val header = readExact(input, 40)
        val length = little(header).getLong(16)
        if (length < 0 || length > limit || length > Long.MAX_VALUE - 15) throw CoreFailure("NM_BAD_CIPHER", "Invalid encrypted length")
        val cipher = Cipher.getInstance("AES/CFB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(header.copyOfRange(24, 40)))
        val digest = MessageDigest.getInstance("MD5")
        var remaining = (length + 15) and -16L
        var written = 0L
        val buffer = ByteArray(128 * 1024)
        while (remaining > 0) {
            check()
            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (count <= 0) throw CoreFailure("NM_BAD_CIPHER", "Truncated encrypted data")
            val decoded = cipher.update(buffer, 0, count) ?: ByteArray(0)
            val take = minOf(decoded.size.toLong(), length - written).toInt()
            output.write(decoded, 0, take)
            digest.update(decoded, 0, take)
            written += take
            remaining -= count
            progress(written)
        }
        val tail = cipher.doFinal()
        val take = minOf(tail.size.toLong(), length - written).toInt()
        output.write(tail, 0, take)
        digest.update(tail, 0, take)
        written += take
        if (written != length || !MessageDigest.isEqual(digest.digest(), header.copyOfRange(0, 16))) throw CoreFailure("CHECKSUM", "Decrypted block MD5 mismatch")
        return written
    }

    /**
     * 完整读取固定长度数据。
     * @param input 输入流。
     * @param size 所需长度。
     * @return 固定长度字节。
     * @throws CoreFailure 数据截断。
     */
    private fun readExact(input: InputStream, size: Int): ByteArray {
        val data = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(data, offset, size - offset)
            if (count <= 0) throw CoreFailure("TRUNCATED", "Unexpected end of file")
            offset += count
        }
        return data
    }

    /**
     * 将字节视图设置为小端格式。
     */
    private fun little(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
