package com.winter.godot.tool.feature.compare

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.winter.godot.core.io.SafeFiles
import okio.buffer
import okio.sink
import okio.source
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipFile
import com.squareup.moshi.JsonReader.Token as JsonToken

/**
 * 有界流式快照读取与原子写入。
 * @author Tingdong
 * @date 2026-11-25
 */
object SnapshotIO {
    /**
     * 原子写入按路径排序的快照。
     * 使用示例：SnapshotIO.write(target, header, records, check)。
     * @param target 目标 JSON。
     * @param header 来源及覆盖信息。
     * @param records 按路径排序的记录。
     * @param check 取消检查。
     */
    fun write(target: File, header: SnapshotHeader, records: Sequence<ScriptSnapshot>, check: () -> Unit) {
        val temporary = File(target.parentFile, ".${target.name}.part")
        try {
            JsonWriter.of(temporary.sink().buffer()).use { writer ->
                writer.beginObject().name("format").value(SnapshotFormat.VERSION).name("rules").value(SnapshotFormat.RULES)
                writer.name("header")
                writeHeader(writer, header)
                writer.name("scripts").beginArray()
                var previous: String? = null
                var count = 0
                for (script in records) {
                    check()
                    require(++count <= SnapshotFormat.MAX_ENTRIES && (previous == null || previous < script.path)) { "SNAPSHOT_ORDER" }
                    previous = script.path
                    writeScript(writer, script)
                    writer.flush()
                    require(temporary.length() <= SnapshotFormat.MAX_BYTES) { "SNAPSHOT_SIZE_LIMIT" }
                }
                writer.endArray().endObject()
            }
            check()
            require(temporary.length() <= SnapshotFormat.MAX_BYTES && temporary.renameTo(target)) { "SNAPSHOT_WRITE_FAILED" }
        } finally { temporary.delete() }
    }

    /**
     * 写入单个脚本及其方法。
     * @param writer 流式写入器。
     * @param script 已排序的脚本记录。
     */
    private fun writeScript(writer: JsonWriter, script: ScriptSnapshot) {
        writer.beginObject().name("path").value(script.path).name("container").value(script.container)
            .name("status").value(script.status).name("reason").value(script.reason).name("outside").value(script.outside)
        writer.name("methods").beginArray()
        for (method in script.methods) writer.beginObject().name("key").value(method.key).name("signature").value(method.signature)
            .name("declaration").value(method.declaration).name("body").value(method.body).endObject()
        writer.endArray().name("members").beginArray()
        for (member in script.members) writer.beginObject().name("key").value(member.key).name("signature").value(member.signature)
            .name("fingerprint").value(member.fingerprint).endObject()
        writer.endArray().endObject()
    }

    /**
     * 打开单独 JSON 或 ZIP 中唯一的根快照。
     * 使用示例：SnapshotIO.open(file).use { it.next() }。
     * @param file 用户选定的 JSON 或 ZIP。
     * @return 关闭时一并释放 ZIP 的游标。
     */
    fun open(file: File): Cursor {
        require(file.isFile && file.canRead()) { "SNAPSHOT_UNAVAILABLE" }
        if (file.extension.equals("gt", true)) {
            require(file.length() in 1..SnapshotFormat.MAX_BYTES) { "SNAPSHOT_SIZE_LIMIT" }
            return Cursor(file.inputStream(), null, file.length())
        }
        require(file.extension.equals("zip", true)) { "SNAPSHOT_INPUT_TYPE" }
        val zip = ZipFile(file)
        try {
            val entries = zip.entries()
            var selected: java.util.zip.ZipEntry? = null
            var count = 0
            while (entries.hasMoreElements()) {
                require(++count <= SnapshotFormat.MAX_ENTRIES + 8) { "SNAPSHOT_ENTRY_LIMIT" }
                val entry = entries.nextElement()
                if (entry.name == SnapshotFormat.FILE_NAME) {
                    require(selected == null && !entry.isDirectory) { "SNAPSHOT_DUPLICATE" }
                    selected = entry
                }
            }
            val entry = selected ?: error("SNAPSHOT_MISSING")
            require(entry.size in 1..SnapshotFormat.MAX_BYTES) { "SNAPSHOT_SIZE_LIMIT" }
            return Cursor(zip.getInputStream(entry), zip, entry.size)
        } catch (error: Throwable) { zip.close(); throw error }
    }

    /**
     * 单个脚本驻留内存的 JSON 游标。
     * @author Tingdong
     * @date 2026-11-25
     */
    class Cursor(input: InputStream, private val archive: Closeable?, val totalBytes: Long) : Closeable {
        private val limited = LimitedInput(input)
        private val source = limited.source().buffer()
        private val reader = JsonReader.of(source)
        val bytesRead: Long get() = (limited.count - source.buffer.size).coerceIn(0L, totalBytes)
        val header: SnapshotHeader
        private var previous: String? = null
        private var count = 0
        private var ended = false

        init {
            try {
                reader.beginObject()
                val fields = mutableSetOf<String>()
                var format = -1
                var rules = ""
                var metadata: SnapshotHeader? = null
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(fields.add(name)) { "SNAPSHOT_DUPLICATE" }
                    if (name == "scripts") break
                    when (name) {
                        "format" -> format = reader.nextInt()
                        "rules" -> rules = string(reader)
                        "header" -> metadata = readHeader(reader)
                        else -> skip(reader)
                    }
                }
                require(format in 1..SnapshotFormat.VERSION && rules == SnapshotFormat.RULES) { "SNAPSHOT_INCOMPATIBLE" }
                require("scripts" in fields && metadata != null) { "SNAPSHOT_INVALID" }
                header = metadata
                reader.beginArray()
            } catch (error: Throwable) { close(); throw error }
        }

        /**
         * 获取下一个脚本，结束时验证完整 JSON。
         * @return 脚本或流结束标记。
         */
        fun next(): ScriptSnapshot? {
            if (ended) return null
            if (!reader.hasNext()) {
                reader.endArray(); reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT) { "SNAPSHOT_TRAILING_DATA" }
                ended = true
                return null
            }
            require(++count <= SnapshotFormat.MAX_ENTRIES) { "SNAPSHOT_ENTRY_LIMIT" }
            val record = readScript(reader, header.membersIndexed)
            require(previous == null || previous!! < record.path) { "SNAPSHOT_ORDER" }
            previous = record.path
            return record
        }

        /**
         * 释放输入流及归档句柄。
         */
        override fun close() { try { reader.close() } finally { archive?.close() } }
    }

    /**
     * 读取头部并验证固定扫描范围。
     * @param reader 输入游标。
     * @return 元数据。
     */
    internal fun readHeader(reader: JsonReader): SnapshotHeader {
        var header = SnapshotHeader()
        var scope = ""
        val names = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            require(names.add(key)) { "SNAPSHOT_DUPLICATE" }
            header = when (key) {
                "name" -> header.copy(name = string(reader))
                "package" -> header.copy(packageName = string(reader))
                "version" -> header.copy(version = string(reader))
                "engine" -> header.copy(engine = string(reader))
                "tool" -> header.copy(tool = string(reader))
                "core" -> header.copy(core = string(reader))
                "catalogComplete" -> header.copy(catalogComplete = reader.nextBoolean())
                "complete" -> header.copy(complete = reader.nextBoolean())
                "membersIndexed" -> header.copy(membersIndexed = reader.nextBoolean())
                "scope" -> { scope = string(reader); header }
                else -> { skip(reader); header }
            }
        }
        reader.endObject()
        require(scope == "gdscript-independent-embedded-addons" && names.containsAll(listOf("catalogComplete", "complete"))) { "SNAPSHOT_INCOMPATIBLE" }
        return header
    }

    /**
     * 写入快照来源，供快照与历史记录共用。
     * @param writer 流式写入器。
     * @param header 来源与覆盖信息。
     */
    internal fun writeHeader(writer: JsonWriter, header: SnapshotHeader) {
        writer.beginObject().name("name").value(header.name).name("package").value(header.packageName).name("version").value(header.version)
        writer.name("engine").value(header.engine).name("tool").value(header.tool).name("core").value(header.core)
        writer.name("scope").value("gdscript-independent-embedded-addons")
        writer.name("catalogComplete").value(header.catalogComplete).name("complete").value(header.complete)
            .name("membersIndexed").value(header.membersIndexed).endObject()
    }

    /**
     * 读取并验证一个脚本。
     * @param reader 输入游标。
     * @param membersIndexed 是否必须包含成员索引。
     * @return 脚本记录。
     */
    private fun readScript(reader: JsonReader, membersIndexed: Boolean): ScriptSnapshot {
        var path = ""
        var container = false
        var status = ""
        var reason = ""
        var outside = ""
        val methods = mutableListOf<MethodSnapshot>()
        val members = mutableListOf<MemberSnapshot>()
        val names = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            require(names.add(key)) { "SNAPSHOT_DUPLICATE" }
            when (key) {
                "path" -> path = string(reader)
                "container" -> container = reader.nextBoolean()
                "status" -> status = string(reader)
                "reason" -> reason = string(reader)
                "outside" -> outside = string(reader)
                "methods" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        require(methods.size < 100000) { "SNAPSHOT_METHOD_LIMIT" }
                        val method = readMethod(reader)
                        require(methods.isEmpty() || methods.last().key < method.key) { "SNAPSHOT_DUPLICATE" }
                        methods += method
                    }
                    reader.endArray()
                }
                "members" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        require(members.size < 100000) { "SNAPSHOT_METHOD_LIMIT" }
                        val member = readMember(reader)
                        require(members.isEmpty() || members.last().key < member.key) { "SNAPSHOT_DUPLICATE" }
                        members += member
                    }
                    reader.endArray()
                }
                else -> skip(reader)
            }
        }
        reader.endObject()
        require(path == SafeFiles.relative(path) && names.containsAll(listOf("container", "methods"))) { "SNAPSHOT_INVALID" }
        require(status in setOf("ok", "failed")) { "SNAPSHOT_INVALID" }
        if (status == "ok") require(hash(outside)) { "SNAPSHOT_INVALID" }
        else require(methods.isEmpty() && members.isEmpty()) { "SNAPSHOT_INVALID" }
        require(!membersIndexed || "members" in names) { "SNAPSHOT_INVALID" }
        return ScriptSnapshot(path, container, status, reason, outside, methods, members)
    }

    /**
     * 读取并校验一个常量或变量声明。
     * @param reader 输入游标。
     * @return 具名声明及其指纹。
     */
    private fun readMember(reader: JsonReader): MemberSnapshot {
        var key = ""; var signature = ""; var fingerprint = ""
        val names = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(names.add(name)) { "SNAPSHOT_DUPLICATE" }
            when (name) {
                "key" -> key = string(reader)
                "signature" -> signature = string(reader)
                "fingerprint" -> fingerprint = string(reader)
                else -> skip(reader)
            }
        }
        reader.endObject()
        require(key.isNotEmpty() && signature.isNotEmpty() && hash(fingerprint)) { "SNAPSHOT_INVALID" }
        return MemberSnapshot(key, signature, fingerprint)
    }

    /**
     * 读取一个方法指纹。
     * @param reader 输入游标。
     * @return 已验证的方法。
     */
    private fun readMethod(reader: JsonReader): MethodSnapshot {
        val values = mutableMapOf<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            require(key !in values) { "SNAPSHOT_DUPLICATE" }
            if (key in setOf("key", "signature", "declaration", "body")) values[key] = string(reader) else { skip(reader); values[key] = "" }
        }
        reader.endObject()
        require(!values["key"].isNullOrEmpty() && values.containsKey("signature") && hash(values["declaration"].orEmpty()) && hash(values["body"].orEmpty())) { "SNAPSHOT_INVALID" }
        return MethodSnapshot(values.getValue("key"), values.getValue("signature"), values.getValue("declaration"), values.getValue("body"))
    }

    /**
     * 有界读取文本字段。
     * @param reader 输入游标。
     * @return 字段文本。
     */
    private fun string(reader: JsonReader): String = reader.nextString().also { require(it.length <= SnapshotFormat.MAX_TEXT) { "SNAPSHOT_STRING_LIMIT" } }

    /**
     * 检查标准摘要。
     * @param value 摘要文本。
     * @return 是否为 SHA-256 十六进制。
     */
    private fun hash(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * 有界跳过扩展字段。
     * @param reader 输入游标。
     * @param depth 嵌套深度。
     */
    private fun skip(reader: JsonReader, depth: Int = 0) {
        require(depth <= 32) { "SNAPSHOT_DEPTH_LIMIT" }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> { reader.beginObject(); while (reader.hasNext()) { reader.nextName(); skip(reader, depth + 1) }; reader.endObject() }
            JsonToken.BEGIN_ARRAY -> { reader.beginArray(); while (reader.hasNext()) skip(reader, depth + 1); reader.endArray() }
            else -> reader.skipValue()
        }
    }

    /**
     * 对解压后输入实施实际字节限制。
     * @author Tingdong
     * @date 2026-11-25
     */
    private class LimitedInput(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set
        /**
         * 检查一个字节的实际读取量。
         * @return 字节值或结束标记。
         */
        override fun read(): Int = super.read().also { if (it >= 0) { count++; require(count <= SnapshotFormat.MAX_BYTES) { "SNAPSHOT_SIZE_LIMIT" } } }
        /**
         * 检查每个数据块的实际读取量。
         * @param bytes 目标缓冲区。
         * @param offset 写入起点。
         * @param length 最大读取量。
         * @return 实际读取量或结束标记。
         */
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also {
            if (it > 0) { count += it; require(count <= SnapshotFormat.MAX_BYTES) { "SNAPSHOT_SIZE_LIMIT" } }
        }
    }
}
