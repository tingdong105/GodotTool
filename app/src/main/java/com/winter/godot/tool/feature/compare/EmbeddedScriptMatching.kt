package com.winter.godot.tool.feature.compare

/**
 * 根据共同引用对齐不同版本的内嵌脚本，不依赖完整引用集合保持不变。
 * @author Tingdong
 * @date 2026-11-25
 */
object EmbeddedScriptMatching {
    /**
     * 将旧快照的脚本前缀对齐到新快照，兼容现有 diff.gt。
     * 使用示例：EmbeddedScriptMatching.align(before, after)。
     * @param before 旧版本的容器记录。
     * @param after 新版本的容器记录。
     * @return 对齐后的旧记录；共同引用存在多种对应关系时返回空。
     */
    fun align(before: ScriptSnapshot, after: ScriptSnapshot): ScriptSnapshot? {
        val oldGroups = groups(before)
        val newGroups = groups(after)
        val shared = oldGroups intersect newGroups
        val anchorIndex = mutableMapOf<String, MutableSet<String>>()
        for (group in newGroups - shared) for (anchor in anchors(group)) {
            anchorIndex.getOrPut(anchor) { mutableSetOf() }.add(group)
        }
        val candidates = (oldGroups - shared).associateWith { group ->
            anchors(group).flatMap { anchorIndex[it].orEmpty() }.toSet()
        }
        val incoming = mutableMapOf<String, Int>()
        for (targets in candidates.values) for (target in targets) incoming[target] = incoming.getOrDefault(target, 0) + 1
        if (candidates.values.any { targets -> targets.size > 1 || targets.any { incoming[it] != 1 } }) return null
        val replacements = candidates.mapNotNull { (group, targets) -> targets.singleOrNull()?.let { group to it } }.toMap()
        if (replacements.isEmpty()) return before
        fun key(value: String): String {
            val prefix = group(value)
            return replacements[prefix]?.let { it + value.removePrefix(prefix) } ?: value
        }
        return before.copy(methods = before.methods.map { it.copy(key = key(it.key)) },
            members = before.members.map { it.copy(key = key(it.key)) })
    }

    /**
     * 汇总同时覆盖方法和成员的脚本前缀。
     * @param script 单个容器。
     * @return 脚本分组标识集合。
     */
    private fun groups(script: ScriptSnapshot): Set<String> =
        (script.methods.asSequence().map { group(it.key) } + script.members.asSequence().map { group(it.key) }).toSet()

    /**
     * 截取引用集合前缀，旧式无前缀键保持原样参与比较。
     * @param key 完整元素键。
     * @return 包含分隔符的前缀，未分组的键返回空串。
     */
    private fun group(key: String): String {
        val end = key.lastIndexOf("]::")
        return if (key.startsWith('[') && end > 0) key.substring(0, end + 3) else ""
    }

    /**
     * 解析既有快照的引用集合。
     * @param group 脚本前缀。
     * @return 资源属性或节点属性的引用位置。
     */
    private fun anchors(group: String): Set<String> = if (group.isEmpty()) emptySet()
        else group.substring(1, group.length - 3).split('|').toSet()
}
