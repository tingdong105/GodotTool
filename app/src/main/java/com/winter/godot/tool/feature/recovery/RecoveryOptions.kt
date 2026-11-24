package com.winter.godot.tool.feature.recovery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.winter.godot.core.model.RecoverySelection
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.components.PrimaryButton
import com.winter.godot.tool.ui.components.SectionTitle
import com.winter.godot.tool.ui.theme.Theme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 根据文件范围显示当前预设名称，原始副本独立于预设。
 * @return 对应的名称资源。
 */
fun recoveryMode(value: RecoverySelection): Int = when (value.copy(keepRaw = false)) {
    RecoverySelection() -> R.string.full_project_preset
    RecoverySelection.scriptsOnly() -> R.string.scripts_only_preset
    else -> R.string.custom_mode
}

/**
 * 选择恢复范围，自定义时展开具体文件类型。
 * @param inputs 本次输入路径，编辑默认值时为空。
 * @param onConfirm 应用本次选择。
 */
@Composable
fun RecoveryOptions(value: RecoverySelection, inputs: Set<String>, enabled: Boolean, onChange: (RecoverySelection) -> Unit, onConfirm: () -> Unit) {
    val modes = listOf(R.string.full_project_preset, R.string.scripts_only_preset, R.string.custom_mode)
    var customMode by remember { mutableStateOf(false) }
    val mode = if (customMode) R.string.custom_mode else recoveryMode(value)
    Column(Modifier.fillMaxSize().padding(horizontal = Theme.spacing.md)) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = Theme.spacing.sm)) {
            if (inputs.isNotEmpty()) item {
                SectionTitle(stringResource(R.string.source_files))
                Card {
                    BasicComponent(title = File(inputs.first()).name, summary = if (inputs.size > 1)
                        stringResource(R.string.more_files, inputs.size - 1) else File(inputs.first()).parent)
                }
            }
            item {
                SectionTitle(stringResource(R.string.recovery_content))
                Card {
                    OverlayDropdownPreference(items = modes.map { stringResource(it) }, selectedIndex = modes.indexOf(mode),
                        title = stringResource(R.string.recovery_mode), onSelectedIndexChange = { index ->
                            customMode = modes[index] == R.string.custom_mode
                            when (modes[index]) {
                                R.string.full_project_preset -> onChange(RecoverySelection(keepRaw = value.keepRaw))
                                R.string.scripts_only_preset -> onChange(RecoverySelection.scriptsOnly().copy(keepRaw = value.keepRaw))
                            }
                        })
                    if (mode == R.string.custom_mode) {
                        SwitchPreference(value.scripts, { onChange(value.copy(scripts = it)) }, stringResource(R.string.project_scripts))
                        SwitchPreference(value.scenes, { onChange(value.copy(scenes = it)) }, stringResource(R.string.scene_resources))
                        SwitchPreference(value.assets, { onChange(value.copy(assets = it)) }, stringResource(R.string.project_assets))
                        SwitchPreference(value.plugins, { onChange(value.copy(plugins = it)) }, stringResource(R.string.project_plugins))
                        SwitchPreference(value.config, { onChange(value.copy(config = it)) }, stringResource(R.string.project_configuration))
                    }
                }
                Text(stringResource(when {
                    !value.hasContent -> R.string.choose_recovery_content
                    mode == R.string.full_project_preset -> R.string.full_mode_detail
                    mode == R.string.scripts_only_preset -> R.string.scripts_mode_detail
                    else -> R.string.partial_project_hint
                }), modifier = Modifier.padding(horizontal = Theme.spacing.lg, vertical = Theme.spacing.md),
                    style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            item {
                Card { SwitchPreference(value.keepRaw, { onChange(value.copy(keepRaw = it)) }, stringResource(R.string.keep_raw),
                    summary = stringResource(R.string.keep_raw_detail)) }
            }
        }
        PrimaryButton(stringResource(if (inputs.isEmpty()) R.string.save_defaults else R.string.start_recovery), onConfirm,
            Modifier.fillMaxWidth().padding(vertical = Theme.spacing.md), enabled = enabled && value.hasContent)
    }
}
