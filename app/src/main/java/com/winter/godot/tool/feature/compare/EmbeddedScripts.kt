package com.winter.godot.tool.feature.compare

/**
 * 从 Godot 文本资源静态提取内嵌脚本。
 * @author Tingdong
 * @date 2026-11-25
 */
object EmbeddedScripts {
    /**
     * 已解析的资源区段及引用属性。
     * @author Tingdong
     * @date 2026-11-25
     */
    private data class Section(val kind: String, val id: String, val type: String, val anchor: String,
        val values: MutableMap<String, String> = linkedMapOf())

    /**
     * 按引用位置而非子资源编号分析内嵌代码。
     * 使用示例：EmbeddedScripts.analyze("main.tscn", text)。
     * @param path 场景或资源的原项目路径。
     * @param text 已恢复的文本资源。
     * @param check 取消检查。
     * @return 容器内所有脚本的方法记录。
     */
    fun analyze(path: String, text: String, check: () -> Unit = {}): ScriptSnapshot {
        require(text.length <= SnapshotFormat.MAX_SOURCE) { "SOURCE_LIMIT" }
        val sections = mutableListOf<Section>()
        var current: Section? = null
        for (statement in statements(text, check)) {
            val value = statement.trim()
            if (value.startsWith("[")) {
                require(value.endsWith("]")) { "RESOURCE_STRUCTURE" }
                val kind = value.drop(1).takeWhile { !it.isWhitespace() && it != ']' }
                val attrs = Regex("([\\w/]+)\\s*=\\s*(\"(?:\\\\.|[^\"\\\\])*\"|[^\\s\\]]+)")
                    .findAll(value).associate { it.groupValues[1] to scalar(it.groupValues[2]) }
                val anchor = when (kind) {
                    "node" -> "node:" + attrs["parent"].orEmpty().trimEnd('/') + "/" + attrs["name"].orEmpty()
                    "resource" -> "resource"
                    else -> ""
                }
                current = Section(kind, attrs["id"].orEmpty(), attrs["type"].orEmpty(), anchor)
                sections += current
            } else if (value.isNotBlank()) {
                val split = value.indexOf('=')
                require(split > 0 && current != null) { "RESOURCE_STRUCTURE" }
                val key = value.substring(0, split).trim()
                require(current.values.put(key, value.substring(split + 1).trim()) == null) { "RESOURCE_DUPLICATE" }
            }
        }
        require(sections.firstOrNull()?.kind in setOf("gd_scene", "gd_resource")) { "RESOURCE_STRUCTURE" }
        val resources = sections.filter { it.kind == "sub_resource" }.associateBy { it.id }
        require(resources.size == sections.count { it.kind == "sub_resource" }) { "RESOURCE_DUPLICATE" }
        val incoming = mutableMapOf<String, MutableList<Pair<Section, String>>>()
        for (section in sections) for ((property, value) in section.values) {
            if (property == "script/source" || property == "source_code") continue
            // 扫描引用时跳过字符串，避免脚本文本或资源名称产生假引用。
            val masked = maskStrings(value)
            for ((ordinal, match) in Regex("SubResource\\s*\\(").findAll(masked).withIndex()) {
                val argument = value.substring(match.range.last + 1).substringBefore(')').trim()
                val id = scalar(argument)
                require(id in resources) { "RESOURCE_REFERENCE" }
                incoming.getOrPut(id) { mutableListOf() }.add(section to "$property@$ordinal")
            }
        }
        fun locations(id: String, visited: Set<String> = emptySet()): List<String> {
            require(visited.size < 64 && id !in visited) { "RESOURCE_REFERENCE_CYCLE" }
            return incoming[id].orEmpty().flatMap { (owner, property) ->
                if (owner.anchor.isNotEmpty()) listOf("${owner.anchor}/$property")
                else if (owner.kind == "sub_resource") locations(owner.id, visited + id).map { "$it/$property" }
                else emptyList()
            }.distinct().sorted().also { require(it.size <= 4096) { "RESOURCE_REFERENCE_LIMIT" } }
        }
        val methods = mutableListOf<MethodSnapshot>()
        val members = mutableListOf<MemberSnapshot>()
        val outside = mutableListOf<Pair<String, String>>()
        for (script in resources.values.filter { it.type == "GDScript" }) {
            check()
            val encoded = script.values["script/source"] ?: script.values["source_code"] ?: error("EMBEDDED_SOURCE_MISSING")
            val anchors = locations(script.id)
            require(anchors.isNotEmpty()) { "EMBEDDED_IDENTITY_UNKNOWN" }
            val identity = anchors.joinToString("|")
            val analyzed = ScriptAnalyzer.analyze(path, scalar(encoded), check)
            outside += identity to analyzed.outside
            methods += analyzed.methods.map { it.copy(key = "[$identity]::${it.key}") }
            members += analyzed.members.map { it.copy(key = "[$identity]::${it.key}") }
        }
        val sorted = methods.sortedBy { it.key }
        require(sorted.size <= 100000 && sorted.all { it.key.length <= SnapshotFormat.MAX_TEXT }) { "EMBEDDED_METHOD_LIMIT" }
        require(sorted.zipWithNext().none { it.first.key == it.second.key }) { "EMBEDDED_IDENTITY_UNKNOWN" }
        val sortedMembers = members.sortedBy { it.key }
        require(sortedMembers.size <= 100000 && sortedMembers.all { it.key.length <= SnapshotFormat.MAX_TEXT } && sortedMembers.zipWithNext().none { it.first.key == it.second.key }) { "EMBEDDED_MEMBER_LIMIT" }
        return ScriptSnapshot(path, container = true, outside = ScriptAnalyzer.fingerprint(outside.sortedBy { it.first }.flatMap { listOf(it.first, it.second) }),
            methods = sorted, members = sortedMembers)
    }

    /**
     * 隐去字符串内容但保留位置。
     * @param value 资源属性值。
     * @return 等长的引用检索文本。
     */
    private fun maskStrings(value: String): String {
        val chars = value.toCharArray()
        var quoted = false
        var escaped = false
        for (i in chars.indices) {
            val c = chars[i]
            if (quoted) {
                chars[i] = ' '
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else if (c == '"') { quoted = true; chars[i] = ' ' }
        }
        return String(chars)
    }

    /**
     * 按引号与括号边界拆分资源语句。
     * @param text 文本资源。
     * @param check 取消检查。
     * @return 逐条逻辑语句。
     */
    private fun statements(text: String, check: () -> Unit): Sequence<String> = sequence {
        val buffer = StringBuilder()
        var quoted = false
        var escaped = false
        var comment = false
        var depth = 0
        for ((index, c) in text.withIndex()) {
            if (index and 4095 == 0) check()
            if (comment) { if (c != '\n') continue else comment = false }
            if (!quoted && c == ';') { comment = true; continue }
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else {
                if (c == '"') quoted = true
                if (c in "([{") depth++
                if (c in ")]}") depth--
                require(depth >= 0) { "RESOURCE_STRUCTURE" }
            }
            if (c == '\n' && !quoted && depth == 0) { yield(buffer.toString()); buffer.setLength(0) }
            else buffer.append(c)
        }
        require(!quoted && depth == 0) { "RESOURCE_STRUCTURE" }
        if (buffer.isNotBlank()) yield(buffer.toString())
    }

    /**
     * 解码 Godot 字符串或数字资源编号。
     * @param encoded 资源文本中的字面量。
     * @return 解码字符串。
     */
    private fun scalar(encoded: String): String {
        if (!encoded.startsWith('"')) return encoded
        require(encoded.endsWith('"') && encoded.length >= 2) { "RESOURCE_STRING" }
        val result = StringBuilder()
        var cursor = 1
        while (cursor < encoded.lastIndex) {
            val c = encoded[cursor++]
            if (c != '\\') { result.append(c); continue }
            require(cursor < encoded.lastIndex) { "RESOURCE_STRING" }
            when (val escaped = encoded[cursor++]) {
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'a' -> result.append('\u0007')
                'v' -> result.append('\u000b')
                '\\', '"', '\'' -> result.append(escaped)
                'u', 'U' -> {
                    val count = if (escaped == 'u') 4 else 6
                    require(cursor + count <= encoded.lastIndex) { "RESOURCE_STRING" }
                    val point = encoded.substring(cursor, cursor + count).toInt(16)
                    require(Character.isValidCodePoint(point)) { "RESOURCE_STRING" }
                    result.append(String(Character.toChars(point))); cursor += count
                }
                else -> error("RESOURCE_STRING_ESCAPE")
            }
        }
        return result.toString()
    }
}
