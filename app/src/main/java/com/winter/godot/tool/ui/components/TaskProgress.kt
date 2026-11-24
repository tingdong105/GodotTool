package com.winter.godot.tool.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.winter.godot.tool.R
import com.winter.godot.tool.data.TaskLogLevel
import com.winter.godot.tool.data.TaskLogLine
import com.winter.godot.tool.data.TaskRecord
import com.winter.godot.tool.data.TaskStatus
import com.winter.godot.tool.ui.theme.Theme
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 展示从导入到结果保存的整体进度。
 * @param task 运行中或已结束的任务。
 * @param modifier 布局修饰。
 */
@Composable
fun TaskProgress(task: TaskRecord, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(task.overallProgress, tween(240), label = "overallProgress")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
            if (task.status == TaskStatus.RUNNING) ProcessingAnimation()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Theme.spacing.xs)) {
                Text(stringResource(if (task.status == TaskStatus.RUNNING) stageResource(task.stage) else statusResource(task)), style = Theme.typography.title)
                Text(task.name, style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Text(stringResource(R.string.progress_percent, (task.overallProgress * 100).toInt()),
                style = Theme.typography.title.copy(fontFeatureSettings = "tnum"), color = MiuixTheme.colorScheme.primary)
        }
        LinearProgressIndicator(progress = progress)
        Text(stringResource(R.string.overall_progress), style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/**
 * 播放本地绘制的文件归拢循环，加载失败时显示标准指示器。
 */
@Composable
private fun ProcessingAnimation() {
    val composition = rememberLottieComposition(LottieCompositionSpec.Asset("lottie/processing_files.json"))
    Box(Modifier.size(Theme.sizes.row), contentAlignment = Alignment.Center) {
        if (composition.value != null) LottieAnimation(composition.value, iterations = LottieConstants.IterateForever)
        else CircularProgressIndicator()
    }
}

/**
 * 关键日志按状态着色，追加与换行后始终贴住底部。
 * @param task 当前任务，重建界面时补充当前状态。
 * @param logs 已筛选的用户日志。
 */
@Composable
fun TaskLogs(task: TaskRecord, logs: List<TaskLogLine>) {
    val scroll = rememberScrollState()
    val shown = remember(task.id, task.status, task.stage, logs) {
        if (logs.isNotEmpty()) logs else listOf(TaskLogLine(task.stage, ""))
    }
    LaunchedEffect(task.id, logs) {
        // 监听布局后的高度，长行及卡片尺寸变化也会继续跟随。
        snapshotFlow { scroll.maxValue }.collectLatest { bottom ->
            if (bottom > 0 && bottom != Int.MAX_VALUE) scroll.scrollTo(bottom)
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = Theme.sizes.row * 3).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
        shown.forEach { line ->
            val color = when (line.level) {
                TaskLogLevel.INFO -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                TaskLogLevel.ACCENT -> MiuixTheme.colorScheme.primary
                TaskLogLevel.SUCCESS -> Theme.status.success
                TaskLogLevel.WARNING -> Theme.status.warning
                TaskLogLevel.ERROR -> MiuixTheme.colorScheme.error
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = Theme.spacing.sm).size(Theme.spacing.xs).clip(CircleShape).background(color))
                Text(line.text.ifEmpty { stringResource(if (task.status == TaskStatus.RUNNING) stageResource(task.stage) else statusResource(task)) },
                    color = color, style = Theme.typography.detail,
                    fontWeight = if (line.level == TaskLogLevel.INFO) FontWeight.Normal else FontWeight.SemiBold)
            }
        }
    }
}
