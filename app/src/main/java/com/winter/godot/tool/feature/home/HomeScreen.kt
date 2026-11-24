package com.winter.godot.tool.feature.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.winter.godot.tool.R
import com.winter.godot.tool.data.TaskLogLine
import com.winter.godot.tool.data.TaskRecord
import com.winter.godot.tool.data.TaskStatus
import com.winter.godot.tool.ui.components.PrimaryButton
import com.winter.godot.tool.ui.components.SectionTitle
import com.winter.godot.tool.ui.components.TaskProgress
import com.winter.godot.tool.ui.components.stageResource
import com.winter.godot.tool.ui.components.statusResource
import com.winter.godot.tool.ui.theme.Theme
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 首页提供文件导入和最近一次结果的快捷入口。
 * @param latest 最近任务，运行期间优先显示当前任务。
 * @param busy 引擎占用状态。
 */
@Composable
fun HomeScreen(latest: TaskRecord?, busy: Boolean, onChoose: () -> Unit, onHistory: () -> Unit, onOpen: (String) -> Unit, onCancel: () -> Unit, logs: List<TaskLogLine> = emptyList()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Theme.spacing.md, vertical = Theme.spacing.sm)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(title = stringResource(R.string.unpack_project), summary = stringResource(R.string.supported_packages))
                PrimaryButton(stringResource(R.string.select_file), onChoose,
                    Modifier.fillMaxWidth().padding(horizontal = Theme.spacing.lg).padding(bottom = Theme.spacing.lg), enabled = !busy)
            }
        }
        if (latest != null) item {
            SectionTitle(stringResource(if (latest.status == TaskStatus.RUNNING) R.string.task_progress else R.string.recent_result))
            Card(Modifier.fillMaxWidth()) {
                if (latest.status == TaskStatus.RUNNING) {
                    BasicComponent(title = latest.name, summary = stringResource(stageResource(latest.stage)))
                    TaskProgress(latest, onCancel, logs)
                } else {
                    val ready = latest.operation == "recover" && latest.status in setOf(TaskStatus.SUCCEEDED, TaskStatus.PARTIAL)
                    val summary = remember(latest.summary) { runCatching { JSONObject(latest.summary) }.getOrNull() }
                    ArrowPreference(title = latest.name, summary = if (ready && latest.status == TaskStatus.SUCCEEDED && summary != null)
                        stringResource(R.string.recovery_totals, summary.optInt("resources"), summary.optInt("scripts"))
                        else stringResource(statusResource(latest)),
                        onClick = { if (ready) onOpen(latest.id) else onHistory() })
                }
            }
        }
    }
}
