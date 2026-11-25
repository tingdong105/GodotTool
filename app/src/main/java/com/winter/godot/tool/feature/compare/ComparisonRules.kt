package com.winter.godot.tool.feature.compare

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 对比结果的规则匹配方式。
 * @author Tingdong
 * @date 2026-11-25
 */
enum class RuleMatch { CONTAINS, PREFIX, SUFFIX, EXACT, WILDCARD, REGEX }

/**
 * 过滤规则匹配的名称或路径范围。
 * @author Tingdong
 * @date 2026-11-25
 */
enum class RuleTarget { ALL, METHOD, FIELD, PATH }

/**
 * 过滤规则列表的排序依据。
 * @author Tingdong
 * @date 2026-11-25
 */
enum class RuleSort(val defaultReversed: Boolean) { TIME(true), NAME(false) }

/**
 * 路径与名称的过滤规则及保留的关注配置。
 * @author Tingdong
 * @date 2026-11-25
 */
data class ComparisonRule(val expression: String, val match: RuleMatch = RuleMatch.CONTAINS, val exclude: Boolean = false,
    val target: RuleTarget = RuleTarget.ALL, val note: String = "", val id: String = "", val createdAt: Long = 0)

/**
 * 排列规则列表，保持同名和同时间规则的顺序稳定。
 * 使用示例：sortComparisonRules(rules, RuleSort.TIME, true)。
 * @param rules 待展示规则。
 * @param order 排序依据。
 * @param reversed 是否逆向排列。
 * @return 不修改原列表的排序结果。
 */
fun sortComparisonRules(rules: List<ComparisonRule>, order: RuleSort, reversed: Boolean): List<ComparisonRule> {
    val comparator = when (order) {
        RuleSort.TIME -> compareBy<ComparisonRule> { it.createdAt }.thenBy { it.id }
        RuleSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { rule: ComparisonRule -> rule.expression }
            .thenBy { it.createdAt }.thenBy { it.id }
    }
    return rules.sortedWith(if (reversed) comparator.reversed() else comparator)
}

/**
 * 预编译的结果筛选与关注规则。
 * @author Tingdong
 * @date 2026-11-25
 */
class ComparisonRules(rules: List<ComparisonRule>) {
    private val compiled = rules.map { rule ->
        val expression = when (rule.match) {
            RuleMatch.CONTAINS -> Regex.escape(rule.expression)
            RuleMatch.PREFIX -> "\\A" + Regex.escape(rule.expression)
            RuleMatch.SUFFIX -> Regex.escape(rule.expression) + "\\z"
            RuleMatch.EXACT -> "\\A" + Regex.escape(rule.expression) + "\\z"
            RuleMatch.WILDCARD -> "\\A" + rule.expression.map { when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) } }.joinToString("") + "\\z"
            RuleMatch.REGEX -> rule.expression
        }
        rule to Regex(expression, RegexOption.IGNORE_CASE)
    }

    /**
     * 检查方法是否被排除。
     * 使用示例：rules.excludes(path, change)。
     * @param path 原项目脚本路径。
     * @param change 方法变化。
     * @return 是否命中排除规则。
     */
    fun excludes(path: String, change: MethodChange): Boolean = matches(path, change, true)

    /**
     * 检查方法是否被关注。
     * 使用示例：rules.focuses(path, change)。
     * @param path 原项目脚本路径。
     * @param change 方法变化。
     * @return 是否命中关注规则。
     */
    fun focuses(path: String, change: MethodChange): Boolean = matches(path, change, false)

    /**
     * 在指定范围的实际名称或完整相对路径中匹配规则。
     * @param path 脚本路径。
     * @param change 方法变化。
     * @param exclude 是否匹配排除规则。
     * @return 是否命中。
     */
    private fun matches(path: String, change: MethodChange, exclude: Boolean): Boolean {
        val normalizedPath = path.replace('\\', '/')
        val name = change.key.substringAfterLast("]::").substringAfterLast('.')
        return compiled.any { (rule, regex) ->
            if (rule.exclude != exclude) return@any false
            val pathMatches = (rule.target == RuleTarget.ALL || rule.target == RuleTarget.PATH) && regex.containsMatchIn(normalizedPath)
            val nameMatches = name.isNotEmpty() && (rule.target == RuleTarget.ALL ||
                rule.target == RuleTarget.METHOD && change.mode == ComparisonMode.METHODS ||
                rule.target == RuleTarget.FIELD && change.mode == ComparisonMode.CONSTANTS) && regex.containsMatchIn(name)
            pathMatches || nameMatches
        }
    }

    companion object {
        /**
         * 校验并规范化规则。
         * 使用示例：ComparisonRules.validate(rule)。
         * @param rule 待添加规则。
         * @return 已去除首尾空白的规则。
         */
        fun validate(rule: ComparisonRule): ComparisonRule = rule.copy(expression = rule.expression.trim(), note = rule.note.trim()).also {
            require(it.expression.length in 1..256) { "COMPARISON_RULE_INVALID" }
            require(it.createdAt >= 0 && (it.id.isEmpty() || it.id.matches(Regex("[0-9a-f-]{36}")))) { "COMPARISON_RULE_INVALID" }
            ComparisonRules(listOf(it))
        }

        /**
         * 读取应用私有规则文件。
         * @param file 规则文件。
         * @return 当前规则列表。
         */
        fun load(file: File): List<ComparisonRule> {
            if (!file.isFile) return emptyList()
            require(file.length() <= 1024 * 1024) { "COMPARISON_RULE_INVALID" }
            return JsonReader.of(file.source().buffer()).use { reader ->
                val rules = mutableListOf<ComparisonRule>()
                reader.beginArray()
                while (reader.hasNext()) {
                    require(rules.size < 256) { "COMPARISON_RULE_INVALID" }
                    var expression = ""; var match = RuleMatch.CONTAINS; var exclude = false
                    var target = RuleTarget.ALL; var note = ""
                    var id = ""; var createdAt = 0L
                    reader.beginObject()
                    while (reader.hasNext()) when (reader.nextName()) {
                        "expression" -> expression = reader.nextString()
                        "match" -> match = RuleMatch.valueOf(reader.nextString())
                        "exclude" -> exclude = reader.nextBoolean()
                        "target" -> target = RuleTarget.valueOf(reader.nextString())
                        "note" -> note = reader.nextString()
                        "id" -> id = reader.nextString()
                        "createdAt" -> createdAt = reader.nextLong()
                        else -> reader.skipValue()
                    }
                    reader.endObject()
                    // 旧规则按原有追加次序补齐稳定身份与时间。
                    if (id.isEmpty()) id = UUID.nameUUIDFromBytes("${rules.size}:$expression:$match:$exclude:$target:$note".toByteArray(Charsets.UTF_8)).toString()
                    if (createdAt == 0L) createdAt = rules.size.toLong() + 1
                    require(rules.none { it.id == id }) { "COMPARISON_RULE_INVALID" }
                    rules += validate(ComparisonRule(expression, match, exclude, target, note, id, createdAt))
                }
                reader.endArray()
                require(reader.peek() == JsonReader.Token.END_DOCUMENT)
                rules.distinct()
            }
        }

        /**
         * 原子保存规则列表。
         * @param file 私有规则文件。
         * @param rules 待保存规则。
         */
        fun save(file: File, rules: List<ComparisonRule>) {
            require(rules.size <= 256) { "COMPARISON_RULE_INVALID" }
            val ids = rules.map { it.id }.filter { it.isNotEmpty() }
            require(ids.size == ids.distinct().size) { "COMPARISON_RULE_INVALID" }
            val temporary = File(file.parentFile, ".${file.name}.part")
            try {
                JsonWriter.of(temporary.sink().buffer()).use { writer ->
                    writer.beginArray()
                    for (raw in rules.distinct()) {
                        val rule = validate(raw)
                        writer.beginObject().name("expression").value(rule.expression).name("match").value(rule.match.name)
                            .name("exclude").value(rule.exclude).name("target").value(rule.target.name).name("note").value(rule.note)
                            .name("id").value(rule.id).name("createdAt").value(rule.createdAt).endObject()
                    }
                    writer.endArray()
                }
                require(temporary.length() <= 1024 * 1024) { "COMPARISON_RULE_INVALID" }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
    }
}
