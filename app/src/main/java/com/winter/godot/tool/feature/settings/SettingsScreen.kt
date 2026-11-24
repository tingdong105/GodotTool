package com.winter.godot.tool.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.winter.godot.tool.R
import com.winter.godot.tool.data.ThemeMode
import com.winter.godot.tool.data.ToolSettings
import com.winter.godot.tool.feature.recovery.recoveryMode
import com.winter.godot.tool.ui.components.ConfirmDialog
import com.winter.godot.tool.ui.components.SectionTitle
import com.winter.godot.tool.ui.components.ToolDialog
import com.winter.godot.tool.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/**
 * 分组管理外观、恢复偏好、存储和许可。
 * @param onDefaults 打开默认恢复选项。
 */
@Composable
fun SettingsScreen(settings: ToolSettings, busy: Boolean, onChange: (ToolSettings) -> Unit, onClear: () -> Unit, onDefaults: () -> Unit) {
    var clearing by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(horizontal = Theme.spacing.md, vertical = Theme.spacing.sm)) {
        item {
            SectionTitle(stringResource(R.string.theme))
            Card {
                OverlayDropdownPreference(items = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark)),
                    selectedIndex = settings.theme.ordinal, title = stringResource(R.string.appearance_mode),
                    onSelectedIndexChange = { onChange(settings.copy(theme = ThemeMode.entries[it])) })
            }
        }
        item {
            SectionTitle(stringResource(R.string.recovery_content))
            Card { ArrowPreference(title = stringResource(R.string.default_recovery),
                endActions = { Text(stringResource(recoveryMode(settings.selection)), style = Theme.typography.detail) }, onClick = onDefaults) }
        }
        item {
            SectionTitle(stringResource(R.string.storage))
            Card {
                BasicComponent(title = stringResource(R.string.output_directory), summary = stringResource(R.string.output_path))
                ArrowPreference(title = stringResource(R.string.clear_cache), enabled = !busy, onClick = { clearing = true })
            }
        }
        item {
            SectionTitle(stringResource(R.string.about))
            Card {
                BasicComponent(title = stringResource(R.string.app_name), summary = stringResource(R.string.about_core))
                ArrowPreference(title = stringResource(R.string.licenses), onClick = { licenses = true })
            }
        }
    }
    if (clearing) ConfirmDialog(stringResource(R.string.clear_cache_title), stringResource(R.string.clear_cache_detail),
        { clearing = false }, { clearing = false; onClear() })
    if (licenses) {
        val context = LocalContext.current
        val text by produceState("") { value = withContext(Dispatchers.IO) { context.assets.open("licenses.txt").bufferedReader().use { it.readText() } } }
        ToolDialog(stringResource(R.string.licenses), { licenses = false }) {
            Text(text, style = Theme.typography.detail)
            TextButton(stringResource(R.string.close), { licenses = false }, Modifier.fillMaxWidth())
        }
    }
}
