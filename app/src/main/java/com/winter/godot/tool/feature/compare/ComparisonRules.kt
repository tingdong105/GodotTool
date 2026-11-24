package com.winter.godot.tool.feature.compare

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.buffer
import okio.sink
import okio.source
import java.io.File

/**
 * 对比结果的规则匹配方式。
 * @author Tingdong
 * @date 2026-11-25
 */
enum class RuleMatch { CONTAINS, PREFIX, SUFFIX, EXACT, WILDCARD, REGEX }

/**
 * 排除或关注一个方法的规则。
 * @author Tingdong
 * @date 2026-11-25
 */
data class ComparisonRule(val expression: String, val match: RuleMatch = RuleMatch.CONTAINS, val exclude: Boolean = false)

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
     * 在路径、名称与新旧签名中匹配规则。
     * @param path 脚本路径。
     * @param change 方法变化。
     * @param exclude 是否匹配排除规则。
     * @return 是否命中。
     */
    private fun matches(path: String, change: MethodChange, exclude: Boolean): Boolean = compiled.any { (rule, regex) ->
        rule.exclude == exclude && listOf(path, change.key, change.before, change.after, "$path::${change.key}").any { regex.containsMatchIn(it) }
    }

    companion object {
        /**
         * 校验并规范化规则。
         * 使用示例：ComparisonRules.validate(rule)。
         * @param rule 待添加规则。
         * @return 已去除首尾空白的规则。
         */
        fun validate(rule: ComparisonRule): ComparisonRule = rule.copy(expression = rule.expression.trim()).also {
            require(it.expression.length in 1..256) { "COMPARISON_RULE_INVALID" }
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
                    reader.beginObject()
                    while (reader.hasNext()) when (reader.nextName()) {
                        "expression" -> expression = reader.nextString()
                        "match" -> match = RuleMatch.valueOf(reader.nextString())
                        "exclude" -> exclude = reader.nextBoolean()
                        else -> reader.skipValue()
                    }
                    reader.endObject()
                    rules += validate(ComparisonRule(expression, match, exclude))
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
            val temporary = File(file.parentFile, ".${file.name}.part")
            try {
                JsonWriter.of(temporary.sink().buffer()).use { writer ->
                    writer.beginArray()
                    for (raw in rules.distinct()) {
                        val rule = validate(raw)
                        writer.beginObject().name("expression").value(rule.expression).name("match").value(rule.match.name).name("exclude").value(rule.exclude).endObject()
                    }
                    writer.endArray()
                }
                check(temporary.renameTo(file)) { "COMPARISON_SAVE_FAILED" }
            } finally { temporary.delete() }
        }
    }
}
