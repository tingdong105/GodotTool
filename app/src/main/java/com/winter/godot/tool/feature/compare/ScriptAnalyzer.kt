package com.winter.godot.tool.feature.compare

import java.security.MessageDigest

/**
 * 静态提取 GDScript 方法与有效词法结构。
 * @author Tingdong
 * @date 2026-11-25
 */
object ScriptAnalyzer {
    private const val NL = "\u0001newline"
    private const val IN = "\u0001indent"
    private const val OUT = "\u0001dedent"
    private const val PREVIEW_LENGTH = 2048

    /**
     * 分析一个脚本，不执行目标代码。
     * 使用示例：ScriptAnalyzer.analyze("scripts/main.gd", source)。
     * @param path 原项目中的脚本路径。
     * @param source 已恢复源码。
     * @param check 取消检查。
     * @return 按作用域和方法名排序的指纹。
     */
    fun analyze(path: String, source: String, check: () -> Unit = {}): ScriptSnapshot {
        require(source.length <= SnapshotFormat.MAX_SOURCE) { "SOURCE_LIMIT" }
        val tokens = tokenize(source.replace("\r\n", "\n").replace('\r', '\n'), check)
        val pairs = mutableMapOf<Int, Int>()
        val stack = ArrayDeque<Int>()
        tokens.forEachIndexed { index, token ->
            if (token == IN) stack.addLast(index)
            if (token == OUT) { require(stack.isNotEmpty()) { "INDENTATION" }; pairs[stack.removeLast()] = index }
        }
        require(stack.isEmpty()) { "INDENTATION" }
        val outside = mutableListOf<String>()
        val methods = mutableListOf<MethodSnapshot>()
        val members = mutableListOf<MemberSnapshot>()
        fun block(start: Int, end: Int, scope: String, property: String? = null) {
            var cursor = start
            val annotations = mutableListOf<String>()
            while (cursor < end) {
                check()
                if (tokens[cursor] == NL || tokens[cursor] == ";") { cursor++; continue }
                var lineEnd = (cursor until end).firstOrNull { tokens[it] == NL } ?: end
                var rawHeader = tokens.subList(cursor, lineEnd)
                require(rawHeader.none { it == IN || it == OUT }) { "STRUCTURE" }
                var annotationEnd = 0
                while (rawHeader.getOrNull(annotationEnd) == "@") {
                    require(rawHeader.getOrNull(annotationEnd + 1)?.firstOrNull()?.let { it.isLetter() || it == '_' } == true) { "ANNOTATION_STRUCTURE" }
                    annotationEnd += 2
                    if (rawHeader.getOrNull(annotationEnd) == "(") {
                        var depth = 0
                        do {
                            val token = rawHeader.getOrNull(annotationEnd++) ?: error("ANNOTATION_STRUCTURE")
                            if (token == "(") depth++
                            if (token == ")") depth--
                        } while (depth > 0)
                    }
                }
                val remaining = rawHeader.drop(annotationEnd)
                val fieldStart = if (remaining.firstOrNull() == "static") 1 else 0
                val isField = remaining.getOrNull(fieldStart) in setOf("var", "const")
                val abstractMethod = hasAbstractAnnotation(annotations + rawHeader.take(annotationEnd)) && "func" in remaining
                if (isField || abstractMethod || remaining.firstOrNull() == ";") {
                    statementSeparator(remaining)?.let { separator ->
                        lineEnd = cursor + annotationEnd + separator
                        rawHeader = tokens.subList(cursor, lineEnd)
                    }
                }
                val header = rawHeader.drop(annotationEnd)
                annotations += rawHeader.take(annotationEnd)
                val child = lineEnd + 1
                val hasChild = child < end && tokens[child] == IN
                val childEnd = if (hasChild) pairs[child] ?: error("STRUCTURE") else child
                val func = header.indexOf("func")
                val named = func >= 0 && header.take(func).all { it in setOf("static", "remote", "master", "puppet", "remotesync", "mastersync", "puppetsync", "sync") } && header.getOrNull(func + 2) == "("
                val accessor = property != null && header.firstOrNull() in setOf("get", "set")
                val memberStart = if (header.firstOrNull() == "static") 1 else 0
                val member = header.getOrNull(memberStart) in setOf("const", "var")
                val propertyBlock = hasChild && member && header.getOrNull(memberStart) == "var" && header.lastOrNull() == ":" && "func" !in header
                if (member) {
                    val name = header.getOrNull(memberStart + 1) ?: error("MEMBER_STRUCTURE")
                    require(name.firstOrNull()?.let { it.isLetter() || it == '_' } == true) { "MEMBER_STRUCTURE" }
                    val key = listOf(scope, name).filter { it.isNotEmpty() }.joinToString(".")
                    val declaration = annotations + header
                    require(key.length <= SnapshotFormat.MAX_TEXT) { "IDENTIFIER_LIMIT" }
                    val assignment = header.indexOfFirst { it == "=" || it == ":=" }
                    val signature = signature(declaration.filterNot { it == NL }) {
                        if (assignment >= 0) header.take(assignment + 1).joinToString(" ") + " …"
                        else header.joinToString(" ") + " …"
                    }
                    val value = declaration + if (hasChild && !propertyBlock) tokens.subList(child, childEnd + 1) else emptyList()
                    members += MemberSnapshot(key, signature, fingerprint(value))
                }
                when {
                    header.isEmpty() && annotationEnd > 0 && !hasChild -> annotations += NL
                    named || accessor -> {
                        val name = if (named) header[func + 1] else header.first()
                        var nesting = 0
                        val colon = header.indices.firstOrNull { index ->
                            val token = header[index]
                            if (token in listOf("(", "[", "{")) nesting++
                            if (token in listOf(")", "]", "}")) nesting--
                            token == ":" && nesting == 0
                        }
                        val abstract = named && hasAbstractAnnotation(annotations)
                        if (abstract) require(colon == null && !hasChild) { "METHOD_STRUCTURE" }
                        else require(colon != null && if (hasChild) colon == header.lastIndex else colon < header.lastIndex) { "METHOD_STRUCTURE" }
                        val owner = listOfNotNull(scope.takeIf { it.isNotEmpty() }, property).joinToString(".")
                        val key = listOf(owner, name).filter { it.isNotEmpty() }.joinToString(".")
                        val declaration = if (abstract) header else header.take(requireNotNull(colon))
                        require(key.length <= SnapshotFormat.MAX_TEXT) { "IDENTIFIER_LIMIT" }
                        val signature = signature(declaration) {
                            (if (named) declaration.take(func + 2) else declaration.take(1)).joinToString(" ") + " ( … )"
                        }
                        val body = if (abstract) emptyList() else if (hasChild) tokens.subList(child + 1, childEnd) else header.drop(requireNotNull(colon) + 1) + NL
                        methods += MethodSnapshot(key, signature, fingerprint(annotations + declaration), fingerprint(body))
                        annotations.clear()
                    }
                    header.firstOrNull() == "class" -> {
                        require(hasChild && header.size >= 3) { "CLASS_STRUCTURE" }
                        outside += annotations + header + NL
                        annotations.clear()
                        block(child + 1, childEnd, listOf(scope, header[1]).filter { it.isNotEmpty() }.joinToString("."))
                    }
                    propertyBlock -> {
                        require(header.size >= 3) { "PROPERTY_STRUCTURE" }
                        outside += annotations + header + NL
                        annotations.clear()
                        block(child + 1, childEnd, scope, header[memberStart + 1])
                    }
                    else -> {
                        outside += annotations + header + NL
                        annotations.clear()
                        if (hasChild) outside += tokens.subList(child, childEnd + 1)
                    }
                }
                cursor = if (hasChild) childEnd + 1 else lineEnd + 1
            }
            outside += annotations
        }
        block(0, tokens.size, "")
        val sorted = methods.sortedBy { it.key }
        require(sorted.size <= 100000) { "METHOD_LIMIT" }
        require(sorted.zipWithNext().none { it.first.key == it.second.key }) { "DUPLICATE_METHOD" }
        val sortedMembers = members.sortedBy { it.key }
        require(sortedMembers.size <= 100000 && sortedMembers.zipWithNext().none { it.first.key == it.second.key }) { "DUPLICATE_MEMBER" }
        return ScriptSnapshot(path, outside = fingerprint(outside), methods = sorted, members = sortedMembers)
    }

    /**
     * 识别方法上的抽象注解。
     * @param tokens 累积的前置注解。
     * @return 是否具有 abstract 标记。
     */
    private fun hasAbstractAnnotation(tokens: List<String>): Boolean = tokens.indices.any { tokens[it] == "@" && tokens.getOrNull(it + 1) == "abstract" }

    /**
     * 查找类级声明的分号边界，跳过集合、调用和内联 lambda。
     * @param tokens 当前逻辑行去除前置注解后的元素。
     * @return 分号位置，没有独立语句边界时为空。
     */
    private fun statementSeparator(tokens: List<String>): Int? {
        var depth = 0
        val headerFunction = if (tokens.firstOrNull() == "func") 0 else if (tokens.take(2) == listOf("static", "func")) 1 else -1
        for ((index, token) in tokens.withIndex()) {
            if (token == "func" && depth == 0 && index != headerFunction) return null
            if (token in listOf("(", "[", "{")) depth++
            if (token in listOf(")", "]", "}")) depth--
            if (token == ";" && depth == 0) return index
        }
        return null
    }

    /**
     * 仅缩短展示声明，指纹仍由调用方使用完整词法内容计算。
     * 使用示例：signature(tokens) { "func run ( … )" }。
     * @param tokens 完整声明的词法元素。
     * @param abbreviated 超长声明的结构摘要。
     * @return 满足快照和二进制字段上限的可读声明。
     */
    private fun signature(tokens: List<String>, abbreviated: () -> String): String {
        var length = (tokens.size - 1).coerceAtLeast(0).toLong()
        for (token in tokens) {
            length += token.length
            if (length > PREVIEW_LENGTH) {
                val summary = abbreviated()
                if (summary.length <= PREVIEW_LENGTH) return summary
                var end = PREVIEW_LENGTH - 1
                if (summary[end - 1].isHighSurrogate()) end--
                return summary.substring(0, end) + "…"
            }
        }
        return tokens.joinToString(" ")
    }

    /**
     * 以长度前缀计算词法序列摘要。
     * 使用示例：fingerprint(listOf("return", "1"))。
     * @param values 有序词法元素。
     * @return 十六进制 SHA-256。
     */
    fun fingerprint(values: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in values) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /**
     * 读取保留有效缩进的词法序列。
     * @param text 统一换行的源码。
     * @param check 取消检查。
     * @return 包含逻辑换行与缩进的元素。
     */
    private fun tokenize(text: String, check: () -> Unit): List<String> {
        val result = ArrayList<String>()
        val indents = arrayListOf(0)
        val brackets = ArrayDeque<Char>()
        var cursor = 0
        var lineStart = true
        var continuation = false
        while (cursor < text.length) {
            if (cursor and 4095 == 0) check()
            require(result.size < 1000000) { "TOKEN_LIMIT" }
            if (lineStart) {
                var width = 0
                while (cursor < text.length && text[cursor] in " \t") { width += if (text[cursor] == '\t') 4 else 1; cursor++ }
                if (cursor >= text.length) break
                if (text[cursor] != '\n' && text[cursor] != '#' && brackets.isEmpty() && !continuation) {
                    if (width > indents.last()) { indents += width; result += IN }
                    else while (width < indents.last()) { indents.removeAt(indents.lastIndex); result += OUT }
                    require(width == indents.last()) { "INDENTATION" }
                }
                continuation = false
                lineStart = false
            }
            val c = text[cursor]
            when {
                c == ' ' || c == '\t' || c == '\uFEFF' -> cursor++
                c == '#' -> { while (cursor < text.length && text[cursor] != '\n') cursor++ }
                c == '\\' && text.getOrNull(cursor + 1) == '\n' -> { cursor += 2; lineStart = true; continuation = true }
                c == '\n' -> {
                    if (brackets.isEmpty() && result.lastOrNull() !in listOf(null, NL, IN, OUT)) result += NL
                    cursor++; lineStart = true
                }
                c == '\'' || c == '"' -> {
                    val start = cursor
                    val triple = text.getOrNull(cursor + 1) == c && text.getOrNull(cursor + 2) == c
                    val delimiter = if (triple) "$c$c$c" else "$c"
                    cursor += delimiter.length
                    var closed = false
                    while (cursor < text.length) {
                        if (cursor and 4095 == 0) check()
                        if (text[cursor] == '\\') { cursor += 2; continue }
                        if (text.startsWith(delimiter, cursor)) { cursor += delimiter.length; closed = true; break }
                        require(triple || text[cursor] != '\n') { "STRING_STRUCTURE" }
                        cursor++
                    }
                    require(closed) { "STRING_STRUCTURE" }
                    result += text.substring(start, cursor)
                }
                c.isLetterOrDigit() || c == '_' || c.code > 127 -> {
                    val start = cursor++
                    while (cursor < text.length && (text[cursor].isLetterOrDigit() || text[cursor] == '_' || text[cursor].code > 127)) cursor++
                    result += text.substring(start, cursor)
                }
                else -> {
                    if (c in "([{") brackets.addLast(c)
                    if (c in ")]}") {
                        require(brackets.isNotEmpty() && brackets.removeLast() == when (c) { ')' -> '('; ']' -> '['; else -> '{' }) { "BRACKETS" }
                    }
                    // 运算符作为完整元素，避免 a += b 与 a + = b 指纹相同。
                    val operator = listOf("**=", "<<=", ">>=", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "**", "->", ":=", "&&", "||").firstOrNull { text.startsWith(it, cursor) }
                    result += operator ?: c.toString()
                    cursor += operator?.length ?: 1
                }
            }
        }
        require(brackets.isEmpty()) { "BRACKETS" }
        if (result.lastOrNull() != NL) result += NL
        while (indents.size > 1) { indents.removeAt(indents.lastIndex); result += OUT }
        return result
    }
}
