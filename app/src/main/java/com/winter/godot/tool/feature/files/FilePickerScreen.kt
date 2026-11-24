package com.winter.godot.tool.feature.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.ToolUiState
import com.winter.godot.tool.ui.components.EmptyContent
import com.winter.godot.tool.ui.components.FileSearch
import com.winter.godot.tool.ui.components.LoadingContent
import com.winter.godot.tool.ui.components.PrimaryButton
import com.winter.godot.tool.ui.components.SectionTitle
import com.winter.godot.tool.ui.components.ToolIcon
import com.winter.godot.tool.ui.components.fileSize
import com.winter.godot.tool.ui.theme.Theme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 浏览共享存储，目录与文件共用连续滚动列表。
 * @param onChoose 切换所选文件或 APK 分包。
 * @param onStart 进入本次恢复选项。
 */
@Composable
fun FilePickerScreen(state: ToolUiState, enabled: Boolean, onBrowse: (String) -> Unit, onChoose: (String) -> Unit, onStart: () -> Unit) {
    var search by rememberSaveable(state.browserPath) { mutableStateOf("") }
    val root = StorageAccess.root().absolutePath
    val relative = state.browserPath.removePrefix(root).trim('/')
    val files = remember(state.browserFiles, search) { state.browserFiles.filter { it.name.contains(search, ignoreCase = true) } }
    Column(Modifier.fillMaxSize().padding(horizontal = Theme.spacing.md)) {
        FileSearch(search, stringResource(R.string.search_directory), { search = it.take(256) }, Modifier.padding(top = Theme.spacing.sm))
        SectionTitle(listOf(stringResource(R.string.shared_storage), relative).filter { it.isNotEmpty() }.joinToString(" / "))
        Box(Modifier.weight(1f)) {
            when {
                state.loading -> LoadingContent()
                files.isEmpty() -> EmptyContent(stringResource(R.string.no_results))
                else -> Card {
                    key(state.browserPath, search) {
                        LazyColumn {
                            items(files, key = { it.path }) { entry ->
                                if (entry.directory) ArrowPreference(title = entry.name, startAction = { ToolIcon(R.drawable.ic_folder, Modifier.padding(end = Theme.spacing.md)) },
                                    onClick = { onBrowse(entry.path) })
                                else BasicComponent(title = entry.name, summary = fileSize(entry.size),
                                    modifier = Modifier.semantics { role = Role.Checkbox; toggleableState = ToggleableState(entry.path in state.chosenFiles) },
                                    startAction = { ToolIcon(R.drawable.ic_file, Modifier.padding(end = Theme.spacing.md)) },
                                    onClick = { onChoose(entry.path) }, endActions = {
                                        Checkbox(ToggleableState(entry.path in state.chosenFiles), onClick = null)
                                    })
                            }
                        }
                    }
                }
            }
        }
        PrimaryButton(if (state.chosenFiles.isEmpty()) stringResource(R.string.next_step) else stringResource(R.string.next_selected, state.chosenFiles.size),
            onStart, Modifier.fillMaxWidth().padding(vertical = Theme.spacing.md), enabled = enabled && state.chosenFiles.isNotEmpty() && !state.loading)
    }
}
