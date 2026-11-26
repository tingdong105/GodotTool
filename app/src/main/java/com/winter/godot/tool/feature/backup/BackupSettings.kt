package com.winter.godot.tool.feature.backup

import com.tencent.mmkv.MMKV
import com.winter.godot.tool.data.ThemeMode
import com.winter.godot.tool.feature.compare.RuleSort
import org.json.JSONObject

/**
 * 仅导出可迁移的软件偏好，不包含任务与设备目录。
 * @author Tingdong
 * @date 2026-11-26
 */
internal object BackupSettings {
    private val toolBooleans = mapOf("floating-bottom-bar" to false, "liquid-glass" to true, "enable-blur" to false,
        "scripts" to true, "scenes" to false, "assets" to false, "plugins" to false,
        "project-config" to false, "keep-raw" to false, "snapshot" to false)
    private val readerBooleans = mapOf("wrap-lines" to true, "rules-reversed" to true, "focus-rules-reversed" to true)
    private val sortKeys = listOf("rules-sort", "focus-rules-sort")

    /** @return 独立于 MMKV 文件布局的完整偏好。 */
    fun capture(): JSONObject {
        val tool = MMKV.mmkvWithID("godottool-settings-v1", MMKV.SINGLE_PROCESS_MODE)
        val reader = MMKV.mmkvWithID("comparison-reader-v1", MMKV.SINGLE_PROCESS_MODE)
        val main = JSONObject().put("theme", tool.decodeString("theme", ThemeMode.SYSTEM.name))
        toolBooleans.forEach { (key, default) -> main.put(key, tool.decodeBool(key, default)) }
        val comparison = JSONObject()
        readerBooleans.forEach { (key, default) ->
            val sortKey = when (key) { "rules-reversed" -> "rules-sort"; "focus-rules-reversed" -> "focus-rules-sort"; else -> null }
            val fallback = if (sortKey != null) RuleSort.valueOf(reader.decodeString(sortKey, RuleSort.TIME.name)!!).defaultReversed else default
            comparison.put(key, reader.decodeBool(key, fallback))
        }
        sortKeys.forEach { key -> comparison.put(key, reader.decodeString(key, RuleSort.TIME.name)) }
        return JSONObject().put("tool", main).put("reader", comparison)
    }

    /**
     * 检查字段类型及枚举，避免容错转换污染设置。
     * @param value 外部偏好对象。
     */
    fun validate(value: JSONObject) {
        val main = value.getJSONObject("tool")
        val reader = value.getJSONObject("reader")
        ThemeMode.valueOf(main.getString("theme"))
        toolBooleans.keys.forEach { key ->
            if (key != "enable-blur" || main.has(key)) require(main.get(key) is Boolean) { "BACKUP_INVALID" }
        }
        readerBooleans.keys.forEach { require(reader.get(it) is Boolean) { "BACKUP_INVALID" } }
        sortKeys.forEach { RuleSort.valueOf(reader.getString(it)) }
    }

    /**
     * 检查每次写入并同步落盘，调用者负责事务回滚。
     * @param value 已校验的完整偏好。
     */
    fun apply(value: JSONObject) {
        validate(value)
        val tool = MMKV.mmkvWithID("godottool-settings-v1", MMKV.SINGLE_PROCESS_MODE)
        val reader = MMKV.mmkvWithID("comparison-reader-v1", MMKV.SINGLE_PROCESS_MODE)
        val main = value.getJSONObject("tool")
        val comparison = value.getJSONObject("reader")
        check(tool.encode("theme", main.getString("theme")))
        toolBooleans.forEach { (key, default) ->
            check(tool.encode(key, if (main.has(key)) main.getBoolean(key) else default))
        }
        readerBooleans.keys.forEach { check(reader.encode(it, comparison.getBoolean(it))) }
        sortKeys.forEach { check(reader.encode(it, comparison.getString(it))) }
        tool.sync(); reader.sync()
    }
}
