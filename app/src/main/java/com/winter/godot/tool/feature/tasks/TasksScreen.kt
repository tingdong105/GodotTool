package com.winter.godot.tool.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.winter.godot.tool.R
import com.winter.godot.tool.data.TaskRecord
import com.winter.godot.tool.data.TaskStatus
import com.winter.godot.tool.ui.components.ConfirmDialog
import com.winter.godot.tool.ui.components.EmptyContent
import com.winter.godot.tool.ui.components.PrimaryButton
import com.winter.godot.tool.ui.components.TaskProgress
import com.winter.godot.tool.ui.components.ToolDialog
import com.winter.godot.tool.ui.components.errorResource
import com.winter.godot.tool.ui.components.stageResource
import com.winter.godot.tool.ui.components.statusResource
import com.winter.godot.tool.ui.theme.Theme
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import java.text.DateFormat
import java.util.Date

/**
 * 按任务显示状态和时间，点击记录查看结果、诊断及操作。
 * @param active 当前任务标识。
 */
@Composable
fun TasksScreen(tasks: List<TaskRecord>, active: String?, onCancel: () -> Unit, onOpen: (String) -> Unit, onRetry: (TaskRecord) -> Unit, onDelete: (String) -> Unit) {
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    if (tasks.isEmpty()) {
        EmptyContent(stringResource(R.string.no_tasks), stringResource(R.string.no_tasks_detail), R.drawable.ic_tasks)
        return
    }
    Card(Modifier.padding(horizontal = Theme.spacing.md, vertical = Theme.spacing.sm)) {
        LazyColumn {
            items(tasks, key = { it.id }) { task ->
                if (task.status == TaskStatus.RUNNING) {
                    BasicComponent(title = task.name, summary = stringResource(stageResource(task.stage)))
                    TaskProgress(task, onCancel)
                } else {
                    val date = remember(task.createdAt) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.createdAt)) }
                    ArrowPreference(title = task.name, summary = date, endActions = { Text(stringResource(statusResource(task)), style = Theme.typography.detail) },
                        onClick = { detail = task.id })
                }
            }
        }
    }
    tasks.firstOrNull { it.id == detail }?.let { task ->
        val summary = remember(task.summary) { runCatching { JSONObject(task.summary) }.getOrDefault(JSONObject()) }
        val ready = task.status == TaskStatus.SUCCEEDED || task.status == TaskStatus.PARTIAL
        ToolDialog(task.name, { detail = null }) {
            Text(stringResource(statusResource(task)), style = Theme.typography.title)
            if (ready) {
                Text(if (task.operation == "export") stringResource(R.string.export_totals, summary.optInt("exportedFiles"))
                    else stringResource(R.string.recovery_totals, summary.optInt("resources"), summary.optInt("scripts")))
                if (summary.optInt("failedScripts") + summary.optInt("failedConversions") > 0)
                    Text(stringResource(R.string.conversion_failures, summary.optInt("failedScripts"), summary.optInt("failedConversions")))
            }
            if (summary.has("warning")) Text(stringResource(R.string.partial_recovery))
            val output = summary.optString("outputPath")
            if (output.isNotEmpty()) SelectionContainer { Text(output, style = Theme.typography.detail) }
            if (task.errorCode.isNotEmpty() && task.status != TaskStatus.CANCELED) Text(stringResource(errorResource(task.errorCode)))
            val diagnostic = listOf(summary.optString("diagnostic"), task.errorCode, task.errorDetail).filter { it.isNotBlank() }.joinToString("\n")
            if (diagnostic.isNotEmpty()) SelectionContainer { Text(diagnostic, style = Theme.typography.code) }
            if (ready && task.operation == "recover") PrimaryButton(stringResource(R.string.view_files),
                { detail = null; onOpen(task.id) }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(Theme.spacing.sm)) {
                if (task.operation == "recover" && task.status != TaskStatus.SUCCEEDED) TextButton(stringResource(R.string.retry),
                    { detail = null; onRetry(task) }, Modifier.weight(1f), enabled = active == null)
                TextButton(stringResource(R.string.delete), { detail = null; deleting = task.id }, Modifier.weight(1f), enabled = active == null)
                TextButton(stringResource(R.string.close), { detail = null }, Modifier.weight(1f))
            }
        }
    }
    deleting?.let { id -> ConfirmDialog(stringResource(R.string.delete_title), stringResource(R.string.delete_detail),
        { deleting = null }, { deleting = null; onDelete(id) }) }
}
