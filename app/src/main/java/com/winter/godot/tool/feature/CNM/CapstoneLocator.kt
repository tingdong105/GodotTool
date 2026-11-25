package com.winter.godot.tool.feature.CNM

import java.nio.ByteBuffer

/**
 * 通过 Capstone 提取 ARM64 密钥初始化指令的数据引用。
 * @author Tingdong
 * @date 2026-11-24
 */
internal object CapstoneLocator {
    val available: Boolean = try {
        System.loadLibrary("cnm")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /**
     * 分析一段只读映射，返回以数量分隔的地址组。
     * 使用示例：references(code, address, 0, code.capacity())。
     * @param code SO 可执行段的直接缓冲区。
     * @param address 该段的虚拟起始地址。
     * @param start 本次扫描偏移。
     * @param length 本次扫描长度。
     * @return 每组的地址数量及对应数据地址。
     */
    fun references(code: ByteBuffer, address: Long, start: Int, length: Int): LongArray =
        if (available) nativeReferences(code, address, start, length) else LongArray(0)

    /**
     * 调用只读指令分析桥接。
     * @param code SO 映射。
     * @param address 虚拟地址。
     * @param start 扫描偏移。
     * @param length 扫描长度。
     * @return 有界数据引用组。
     */
    private external fun nativeReferences(code: ByteBuffer, address: Long, start: Int, length: Int): LongArray
}
