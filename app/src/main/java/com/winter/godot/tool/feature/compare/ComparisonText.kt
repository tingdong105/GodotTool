package com.winter.godot.tool.feature.compare

/**
 * 将方法和成员声明整理为单条可读结果。
 * @author Tingdong
 * @date 2026-11-25
 */
object ComparisonText {
    /**
     * 格式化路径与方法参数，修改优先使用新签名。
     * 使用示例：ComparisonText.line("scripts/player.gd", change)。
     * @param path 原项目脚本路径。
     * @param change 一条方法差异。
     * @return 路径 -> 方法名(参数); 或成员声明。
     */
    fun line(path: String, change: MethodChange): String {
        if (change.kind == ChangeKind.UNKNOWN) return "${singleLine(path)} -> UNKNOWN; before: ${singleLine(change.before.ifBlank { "DIAGNOSTIC_NOT_SAVED" })}; after: ${singleLine(change.after.ifBlank { "DIAGNOSTIC_NOT_SAVED" })};"
        if (change.key.isEmpty()) return singleLine(path)
        val signature = change.after.ifEmpty { change.before }
        val name = change.key.substringAfterLast("]::")
        if (change.mode == ComparisonMode.CONSTANTS) {
            val scope = name.substringBeforeLast('.', "")
            return "${singleLine(path.replace('\\', '/'))} -> ${if (scope.isNotEmpty()) "${singleLine(scope)}::" else ""}${compact(signature).trimEnd(';')};"
        }
        return "${singleLine(path.replace('\\', '/'))} -> ${singleLine(name)}(${compact(parameters(signature))});"
    }

    /**
     * 生成顶栏版本方向标题。
     * 使用示例：ComparisonText.title(record)。
     * @param record 当前历史记录。
     * @return 旧版到新版的标题。
     */
    fun title(record: ComparisonRecord): String = "${record.before.version.ifBlank { record.oldName }} → ${record.after.version.ifBlank { record.newName }}"

    /**
     * 忽略字符串中的括号，截取真实参数范围。
     * @param signature 反编译后的方法声明。
     * @return 外层括号中的参数文本。
     */
    private fun parameters(signature: String): String {
        val start = signature.indexOf('(')
        if (start < 0) return ""
        var cursor = start + 1
        var depth = 1
        while (cursor < signature.length) {
            val c = signature[cursor]
            if (c == '\'' || c == '"') { cursor = quotedEnd(signature, cursor); continue }
            if (c == '(') depth++
            if (c == ')' && --depth == 0) return signature.substring(start + 1, cursor)
            cursor++
        }
        return signature.substring(start + 1).trimEnd(' ', ':')
    }

    /**
     * 在保护字符串内容的前提下压缩标点空白。
     * @param value 参数列表。
     * @return 一行参数文本。
     */
    private fun compact(value: String): String {
        val literals = mutableListOf<String>()
        val masked = StringBuilder()
        var cursor = 0
        while (cursor < value.length) {
            if (value[cursor] == '\'' || value[cursor] == '"') {
                val end = quotedEnd(value, cursor)
                masked.append('\u0000').append(literals.size).append('\u0000')
                literals += value.substring(cursor, end).replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\r")
                cursor = end
            } else masked.append(value[cursor++])
        }
        val text = masked.toString().replace(Regex("\\s+"), " ").trim()
            .replace(Regex("@\\s+(?=[\\p{L}_])"), "@")
            .replace(Regex("\\s*([()\\[\\]{},.:])\\s*"), "$1")
            .replace(",", ", ").replace(":", ": ")
            .replace(Regex("(?<![=!<>:])\\s*=\\s*(?!=)"), " = ")
            .replace(Regex("\\s*:\\s*=\\s*"), " := ")
            .replace(Regex("\\)(?=(?:static\\s+)?(?:var|const)\\b)"), ") ")
            .replace(Regex("([=(,\\[])\\s*([+-])\\s+(?=\\d)")) {
                val prefix = it.groupValues[1]
                prefix + (if (prefix in listOf("=", ",")) " " else "") + it.groupValues[2]
            }
            .replace(Regex("(?<=\\d[eE])\\s*([+-])\\s*(?=\\d)"), "$1")
        return Regex("\u0000(\\d+)\u0000").replace(text) { literals[it.groupValues[1].toInt()] }
    }

    /**
     * 跨过单行或三引号字符串及其转义。
     * @param value 输入文本。
     * @param start 起始引号位置。
     * @return 结束引号之后的位置。
     */
    private fun quotedEnd(value: String, start: Int): Int {
        val quote = value[start]
        val delimiter = if (value.getOrNull(start + 1) == quote && value.getOrNull(start + 2) == quote) "$quote$quote$quote" else "$quote"
        var cursor = start + delimiter.length
        while (cursor < value.length) {
            if (value[cursor] == '\\') { cursor = minOf(cursor + 2, value.length); continue }
            if (value.startsWith(delimiter, cursor)) return cursor + delimiter.length
            cursor++
        }
        return value.length
    }

    /**
     * 将名称中的控制换行转成可见文本。
     * @param value 名称或路径。
     * @return 不含实际换行的展示文本。
     */
    private fun singleLine(value: String): String = value.replace("\r", "\\r").replace("\n", "\\n")
}
