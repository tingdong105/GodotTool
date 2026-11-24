package com.winter.godot.tool.feature.compare

import android.content.Context
import com.winter.godot.tool.R
import java.io.IOException

/**
 * 对比与解包报告共用的失败原因说明。
 * @author Tingdong
 * @date 2026-11-25
 */
object SnapshotReasonText {
    /**
     * 保留分析错误码，并区分源码编码与读取失败。
     * 使用示例：SnapshotReasonText.code(error)。
     * @param error 分析时捕获的异常。
     * @return 不包含设备路径的诊断代码。
     */
    fun code(error: Exception): String = when (error) {
        is CharacterCodingException -> "SOURCE_ENCODING"
        is IOException -> "SOURCE_READ_FAILED"
        else -> error.message.orEmpty().takeIf { it.matches(Regex("[A-Z_]+")) } ?: "ANALYSIS_FAILED"
    }

    /**
     * 将诊断代码转换成中文说明，同时保留原始代码。
     * 使用示例：SnapshotReasonText.describe(context, "INDENTATION")。
     * @param context 字符串资源环境。
     * @param reason 快照中的原因代码，旧记录可能为空。
     * @return 可展示或写入报告的单行说明。
     */
    fun describe(context: Context, reason: String): String {
        val code = reason.trim().replace('\r', ' ').replace('\n', ' ').take(256)
        val label = context.getString(resource(code))
        return if (code.isEmpty() || code in setOf("ANALYSIS_OK", "NOT_PRESENT", "DIAGNOSTIC_NOT_SAVED")) label
            else context.getString(R.string.snapshot_reason_code, label, code)
    }

    /**
     * 在无法确认的条目中分别列出两个版本的状态。
     * 使用示例：SnapshotReasonText.line(context, path, change)。
     * @param context 字符串资源环境。
     * @param path 原项目相对路径。
     * @param change 无法确认的差异，before/after 保存对应原因。
     * @return 支持换行开关的单行诊断。
     */
    fun line(context: Context, path: String, change: MethodChange): String {
        val name = path.replace('\r', ' ').replace('\n', ' ')
        return if (change.before.isBlank() && change.after.isBlank()) context.getString(R.string.compare_unknown_legacy, name)
            else context.getString(R.string.compare_unknown_reason, name, describe(context, change.before), describe(context, change.after))
    }

    /**
     * 匹配受支持的分析失败类别。
     * @param code 原始诊断代码。
     * @return 对应说明资源，未知代码采用通用解释。
     */
    private fun resource(code: String): Int = when (code) {
        "", "DIAGNOSTIC_NOT_SAVED" -> R.string.snapshot_reason_not_saved
        "ANALYSIS_OK" -> R.string.snapshot_reason_ok
        "NOT_PRESENT" -> R.string.snapshot_reason_absent
        "CATALOG_INCOMPLETE" -> R.string.snapshot_reason_catalog
        "CONTAINER_CHANGED" -> R.string.snapshot_reason_container
        "RECOVERY_FAILED" -> R.string.snapshot_reason_recovery
        "SOURCE_ENCODING" -> R.string.snapshot_reason_encoding
        "SOURCE_READ_FAILED", "SNAPSHOT_READ_FAILED" -> R.string.snapshot_reason_read
        "SOURCE_LIMIT" -> R.string.snapshot_reason_source_limit
        "INDENTATION" -> R.string.snapshot_reason_indentation
        "STRUCTURE" -> R.string.snapshot_reason_structure
        "METHOD_STRUCTURE" -> R.string.snapshot_reason_method
        "MEMBER_STRUCTURE" -> R.string.snapshot_reason_member
        "ANNOTATION_STRUCTURE" -> R.string.snapshot_reason_annotation
        "CLASS_STRUCTURE" -> R.string.snapshot_reason_class
        "PROPERTY_STRUCTURE" -> R.string.snapshot_reason_property
        "STRING_STRUCTURE" -> R.string.snapshot_reason_string
        "BRACKETS" -> R.string.snapshot_reason_brackets
        "DUPLICATE_METHOD" -> R.string.snapshot_reason_duplicate_method
        "DUPLICATE_MEMBER" -> R.string.snapshot_reason_duplicate_member
        "SIGNATURE_LIMIT" -> R.string.snapshot_reason_signature
        "IDENTIFIER_LIMIT" -> R.string.snapshot_reason_identifier
        "METHOD_LIMIT", "TOKEN_LIMIT", "EMBEDDED_METHOD_LIMIT", "EMBEDDED_MEMBER_LIMIT", "RESOURCE_REFERENCE_LIMIT" -> R.string.snapshot_reason_limit
        "EMBEDDED_IDENTITY_UNKNOWN" -> R.string.snapshot_reason_identity
        "EMBEDDED_SOURCE_MISSING" -> R.string.snapshot_reason_embedded_source
        "RESOURCE_STRUCTURE" -> R.string.snapshot_reason_resource
        "RESOURCE_DUPLICATE" -> R.string.snapshot_reason_duplicate_resource
        "RESOURCE_REFERENCE" -> R.string.snapshot_reason_reference
        "RESOURCE_REFERENCE_CYCLE" -> R.string.snapshot_reason_reference_cycle
        "RESOURCE_STRING", "RESOURCE_STRING_ESCAPE" -> R.string.snapshot_reason_resource_string
        else -> R.string.snapshot_reason_unknown
    }
}
