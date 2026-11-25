package com.winter.godot.tool.feature.settings

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.winter.godot.tool.R
import com.winter.godot.tool.feature.compare.RuleSort
import com.winter.godot.tool.ui.theme.Theme
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu

/**
 * 使用 miuix 官方分组下拉菜单切换规则排序。
 * @param order 时间或名称排序。
 * @param reversed 是否逆向排列。
 * @param enabled 是否允许操作。
 * @param onChange 更新并保存排序偏好。
 */
@Composable
fun ComparisonRulesSortButton(order: RuleSort, reversed: Boolean, enabled: Boolean, onChange: (RuleSort, Boolean) -> Unit) {
    val orders = RuleSort.entries.map { option ->
        DropdownItem(text = stringResource(if (option == RuleSort.TIME) R.string.compare_rules_sort_time else R.string.sort_by_name),
            selected = option == order, onClick = {
                if (option != order) onChange(option, option.defaultReversed)
            })
    }
    val reverse = DropdownItem(text = stringResource(R.string.sort_reversed), selected = reversed,
        onClick = { onChange(order, !reversed) })
    OverlayIconDropdownMenu(entries = listOf(DropdownEntry(orders, enabled), DropdownEntry(listOf(reverse), enabled)),
        enabled = enabled, minHeight = Theme.sizes.largeIcon, minWidth = Theme.sizes.largeIcon) {
        Icon(MiuixIcons.Sort, stringResource(R.string.sort_files), Modifier.size(Theme.sizes.icon))
    }
}
