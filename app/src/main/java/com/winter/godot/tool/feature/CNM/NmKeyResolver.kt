package com.winter.godot.tool.feature.CNM

import com.winter.godot.core.model.CoreFailure
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 通过置换材料与目录摘要自动恢复 NM 密钥。
 * @author Tingdong
 * @date 2026-11-21
 */
internal object NmKeyResolver {
    /**
     * 已验证的资源目录与临时密钥。
     * @author Tingdong
     * @date 2026-11-21
     */
    data class Directory(val key: ByteArray, val plain: ByteArray, val count: Int, val salt: ByteArray, val method: String = "scan") {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Directory

            if (count != other.count) return false
            if (method != other.method) return false
            if (!key.contentEquals(other.key)) return false
            if (!plain.contentEquals(other.plain)) return false
            if (!salt.contentEquals(other.salt)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = count
            result = 31 * result + key.contentHashCode()
            result = 31 * result + plain.contentHashCode()
            result = 31 * result + salt.contentHashCode()
            result = 31 * result + method.hashCode()
            return result
        }
    }

    /**
     * 已检查边界的只读 ELF 段。
     * @author Tingdong
     * @date 2026-11-24
     */
    private data class Segment(val address: Long, val flags: Int, val bytes: ByteBuffer)

    /**
     * 搜索可读 ELF 段中的密钥材料并校验完整目录。
     * @param engine 输入 APK 中的引擎副本。
     * @param pack sparsepck 原始数据。
     * @param check 取消检查。
     * @param useCapstone 优先分析指令，关闭时仅使用材料扫描。
     * @return 通过 MD5 校验的密钥与目录。
     */
    fun resolve(engine: File, pack: ByteArray, check: () -> Unit, useCapstone: Boolean = true): Directory {
        val offsets = directoryOffsets(pack)
        RandomAccessFile(engine, "r").use { file ->
            val header = ByteArray(64).also(file::readFully)
            if (!header.copyOfRange(0, 6).contentEquals(byteArrayOf(127, 69, 76, 70, 2, 1))) throw CoreFailure("NM_BAD_ENGINE", "Expected little-endian ELF64")
            val elf = little(header)
            val phOffset = elf.getLong(32)
            val phSize = elf.getShort(54).toInt() and 65535
            val phCount = elf.getShort(56).toInt() and 65535
            if (phSize < 56 || phCount !in 1..256 || phOffset < 0 || phOffset > file.length() - phSize.toLong() * phCount) throw CoreFailure("NM_BAD_ENGINE", "Invalid ELF program table")
            val segments = ArrayList<Segment>()
            for (segment in 0 until phCount) {
                check()
                file.seek(phOffset + segment * phSize)
                val data = little(ByteArray(56).also(file::readFully))
                val type = data.int
                val flags = data.int
                val offset = data.long
                val address = data.long
                val length = data.getLong(32)
                // Android 链接器可将只读常量合入 R-X 段，依据写权限筛选。
                if (type != 1 || flags and 4 == 0 || flags and 2 != 0 || length < 32) continue
                if (offset < 0 || length > 128L * 1024 * 1024 || offset > file.length() - length || address < 0 || address > Long.MAX_VALUE - length) throw CoreFailure("NM_BAD_ENGINE", "Invalid ELF data segment")
                val bytes = file.channel.map(FileChannel.MapMode.READ_ONLY, offset, length)
                segments += Segment(address, flags, bytes)
            }
            if (useCapstone && elf.getShort(18).toInt() == 183 && CapstoneLocator.available) {
                fromInstructions(segments, pack, offsets, check)?.let { return it }
            }
            val candidates = ArrayList<Pair<ByteBuffer, Int>>()
            for (segment in segments) {
                val bytes = segment.bytes
                val counts = IntArray(256)
                var distinct = 0
                for (i in 0 until bytes.limit()) {
                    if (i and 65535 == 0) check()
                    val value = bytes.get(i).toInt() and 255
                    if (value < 32 && counts[value]++ == 0) distinct++
                    if (i >= 32) {
                        val previous = bytes.get(i - 32).toInt() and 255
                        if (previous < 32 && --counts[previous] == 0) distinct--
                    }
                    if (i >= 31 && distinct == 32) {
                        val position = i - 31
                        // 优先检查同类构建的相对布局，完全脱离绝对地址与 SO 指纹。
                        test(bytes, position, position + 0x160, position + 0x1c0, pack, offsets)?.let { return it }
                        if (candidates.size < 96) candidates += bytes to position
                    }
                }
            }
            candidates.sortByDescending { (bytes, position) -> (0 until 32).count { bytes.get(position + it).toInt() and 255 != it } }
            var attempts = 0
            for ((bytes, position) in candidates) {
                val start = maxOf(0, position - 1536)
                val end = minOf(bytes.limit() - 32, position + 1536)
                for (first in start..end step 16) {
                    for (second in first + 32..end step 16) {
                        if (++attempts and 1023 == 0) check()
                        if (attempts > 2000000) throw CoreFailure("NM_KEY_NOT_FOUND", "No validated key in supported material layouts")
                        test(bytes, position, first, second, pack, offsets)?.let { return it }
                    }
                }
            }
        }
        throw CoreFailure("NM_KEY_NOT_FOUND", "No key candidate passed directory MD5 verification")
    }

    /**
     * 按真实指令引用定位三组材料，完整目录校验失败时交回扫描。
     * @param segments SO 只读段。
     * @param pack 加密资源目录。
     * @param offsets 目录候选位置。
     * @param check 取消检查。
     * @return 已校验的目录或空。
     */
    private fun fromInstructions(segments: List<Segment>, pack: ByteArray, offsets: List<Int>, check: () -> Unit): Directory? {
        val attempted = hashSetOf<List<Long>>()
        for (code in segments.filter { it.flags and 1 != 0 }) {
            var start = 0
            while (start < code.bytes.limit()) {
                check()
                val length = minOf(1024 * 1024, code.bytes.limit() - start)
                val groups = CapstoneLocator.references(code.bytes, code.address, start, length)
                var cursor = 0
                while (cursor < groups.size) {
                    val count = groups[cursor++].toInt()
                    if (count !in 3..8 || count > groups.size - cursor) break
                    val addresses = groups.copyOfRange(cursor, cursor + count).toList()
                    cursor += count
                    val materials = addresses.mapNotNull { address ->
                        segments.firstOrNull { address >= it.address && address - it.address <= it.bytes.limit() - 32L }
                            ?.let { segment -> address to ByteArray(32) { segment.bytes.get((address - segment.address).toInt() + it) } }
                    }
                    for ((permutationAddress, permutation) in materials) {
                        if (permutation.map { it.toInt() and 255 }.toSet() != (0 until 32).toSet()) continue
                        val other = materials.filter { it.first != permutationAddress }
                        for (first in other.indices) for (second in first + 1 until other.size) {
                            check()
                            if (!attempted.add(listOf(permutationAddress, other[first].first, other[second].first))) continue
                            val key = ByteArray(32)
                            for (i in 0 until 32) key[permutation[i].toInt() and 255] = (other[first].second[i].toInt() xor other[second].second[i].toInt()).toByte()
                            validate(key, pack, offsets, "capstone")?.let { return it }
                        }
                    }
                }
                if (length < 1024 * 1024) break
                start += 1024 * 1024 - 256
            }
        }
        return null
    }

    /**
     * 用首块结构筛选候选，再验证整个加密目录。
     * @param bytes ELF 只读数据。
     * @param permutation 置换表位置。
     * @param first 第一组异或材料。
     * @param second 第二组异或材料。
     * @param pack 原始目录包。
     * @param offsets 目录候选位置。
     * @return 验证通过的目录，失败时为空。
     */
    private fun test(bytes: ByteBuffer, permutation: Int, first: Int, second: Int, pack: ByteArray, offsets: List<Int>): Directory? {
        if (first < 0 || second < 0 || first > bytes.limit() - 32 || second > bytes.limit() - 32) return null
        val key = ByteArray(32)
        for (i in 0 until 32) key[bytes.get(permutation + i).toInt() and 255] = (bytes.get(first + i).toInt() xor bytes.get(second + i).toInt()).toByte()
        return validate(key, pack, offsets, "scan")
    }

    /**
     * 校验候选密钥，失败时清除临时字节。
     * @param key 候选密钥。
     * @param pack 加密目录。
     * @param offsets 目录候选位置。
     * @param method 本次定位方式。
     * @return 通过完整 MD5 校验的目录。
     */
    private fun validate(key: ByteArray, pack: ByteArray, offsets: List<Int>, method: String): Directory? {
        var valid = false
        try {
            val block = Cipher.getInstance("AES/ECB/NoPadding")
            block.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            for (offset in offsets) {
                val cipherStart = offset + 44
                val probe = block.doFinal(pack.copyOfRange(offset + 28, offset + 44))
                for (i in probe.indices) probe[i] = (probe[i].toInt() xor pack[cipherStart + i].toInt()).toByte()
                val pathLength = little(probe).int
                if (pathLength !in 1..16384 || probe[4] == 0.toByte()) continue
                val size = little(pack).getLong(offset + 20).toInt()
                val cipherSize = (size + 15) and -16
                val cipher = Cipher.getInstance("AES/CFB/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(pack.copyOfRange(offset + 28, offset + 44)))
                val decoded = cipher.doFinal(pack, cipherStart, cipherSize).copyOf(size)
                val expected = pack.copyOfRange(offset + 4, offset + 20)
                if (MessageDigest.isEqual(MessageDigest.getInstance("MD5").digest(decoded), expected)) {
                    valid = true
                    return Directory(key, decoded, little(pack).getInt(offset), pack.copyOfRange(40, 72), method)
                }
            }
        } finally { if (!valid) key.fill(0) }
        return null
    }

    /**
     * 根据加密块结构识别目录，兼容头部偏移掩码变化。
     * @param pack 原始 sparsepck。
     * @return 结构合法的目录偏移。
     */
    private fun directoryOffsets(pack: ByteArray): List<Int> {
        if (pack.size < 120 || little(pack).getInt(4) != 4) throw CoreFailure("NM_BAD_INDEX", "Unsupported sparse pack header")
        val data = little(pack)
        val result = mutableListOf<Int>()
        for (offset in 72..minOf(pack.size - 60, 65536) step 4) {
            val count = data.getInt(offset)
            val length = data.getLong(offset + 20)
            if (count !in 1..200000 || length !in 16..(128L * 1024 * 1024)) continue
            val encrypted = (length + 15) and -16L
            if (offset + 44L + encrypted == pack.size.toLong()) result += offset
            if (result.size >= 8) break
        }
        if (result.isEmpty()) throw CoreFailure("NM_BAD_INDEX", "Encrypted directory header not found")
        return result
    }

    /**
     * 创建小端字节视图。
     */
    private fun little(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
