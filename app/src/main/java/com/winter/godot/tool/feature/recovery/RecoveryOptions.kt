package com.winter.godot.tool.feature.recovery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.winter.godot.core.model.RecoverySelection
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.theme.Theme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayCascadingListPopup
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 根据内容范围获取预设名称，原始副本独立于预设。
 * 使用示例：recoveryMode(settings.selection)。
 * @param value 当前解包选项。
 * @return 预设名称资源。
 */
fun recoveryMode(value: RecoverySelection): Int = when (value.copy(keepRaw = false)) {
    RecoverySelection() -> R.string.full_project_preset
    RecoverySelection.scriptsOnly() -> R.string.scripts_only_preset
    else -> R.string.custom_mode
}

/**
 * 级联多选解包内容，独立控制原始资源保留。
 * @param value 当前选项。
 * @param onChange 即时更新所选内容。
 * @param enabled 是否允许修改。
 */
@Composable
fun RecoveryOptions(value: RecoverySelection, onChange: (RecoverySelection) -> Unit, enabled: Boolean = true) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var customMode by rememberSaveable { mutableStateOf(false) }
    val mode = if (customMode) R.string.custom_mode else recoveryMode(value)
    val categories = listOf(
        DropdownItem(stringResource(R.string.project_scripts), selected = value.scripts,
            onClick = { customMode = true; onChange(value.copy(scripts = !value.scripts)) }),
        DropdownItem(stringResource(R.string.scene_resources), selected = value.scenes,
            onClick = { customMode = true; onChange(value.copy(scenes = !value.scenes)) }),
        DropdownItem(stringResource(R.string.project_assets), selected = value.assets,
            onClick = { customMode = true; onChange(value.copy(assets = !value.assets)) }),
        DropdownItem(stringResource(R.string.project_configuration), selected = value.config,
            onClick = { customMode = true; onChange(value.copy(config = !value.config)) }),
        DropdownItem(stringResource(R.string.project_plugins), selected = value.plugins,
            onClick = { customMode = true; onChange(value.copy(plugins = !value.plugins)) }),
    )
    val entries = listOf(DropdownEntry(listOf(
        DropdownItem(stringResource(R.string.full_project_preset), selected = mode == R.string.full_project_preset,
            onClick = { customMode = false; expanded = false; onChange(RecoverySelection(keepRaw = value.keepRaw)) }),
        DropdownItem(stringResource(R.string.scripts_only_preset), selected = mode == R.string.scripts_only_preset,
            onClick = { customMode = false; expanded = false; onChange(RecoverySelection.scriptsOnly().copy(keepRaw = value.keepRaw)) }),
        DropdownItem(stringResource(R.string.custom_mode), selected = mode == R.string.custom_mode, children = categories),
    )))
    Card {
        Box {
            BasicComponent(title = stringResource(R.string.recovery_mode), enabled = enabled, role = Role.DropdownList,
                holdDownState = expanded, onClick = { expanded = true }, endActions = {
                    Text(stringResource(mode), modifier = Modifier.padding(end = Theme.spacing.sm),
                        style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                    DropdownArrowEndAction(actionColor = MiuixTheme.colorScheme.onSurfaceVariantActions)
                })
            OverlayCascadingListPopup(show = expanded && enabled, entries = entries,
                onDismissRequest = { expanded = false }, collapseOnSelection = false)
        }
        SwitchPreference(value.keepRaw, { onChange(value.copy(keepRaw = it)) },
            stringResource(R.string.keep_raw), enabled = enabled)
    }
}
