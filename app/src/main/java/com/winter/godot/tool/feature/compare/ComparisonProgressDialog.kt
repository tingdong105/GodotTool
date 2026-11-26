package com.winter.godot.tool.feature.compare

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.ToolDevice
import com.winter.godot.tool.ui.theme.Theme
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 平滑展示实际进度，完整显示零与完成状态后自动退出。
 * @param progress 当前操作的真实进度。
 * @param onCancel 取消当前解析或对比。
 * @param onFinished 关闭动画结束后确认完成。
 */
@Composable
fun ComparisonProgressDialog(progress: ComparisonProgress, onCancel: () -> Unit, onFinished: (String) -> Unit) {
    val displayed = remember(progress.id) { Animatable(0f) }
    val started = remember(progress.id) { SystemClock.elapsedRealtime() }
    var ready by remember(progress.id) { mutableStateOf(false) }
    var show by remember(progress.id) { mutableStateOf(true) }
    val finish by rememberUpdatedState(onFinished)
    val dismiss: () -> Unit = {
        if (show) {
            show = false
            if (!progress.finished) onCancel()
        }
    }
    LaunchedEffect(progress.id) {
        withFrameNanos { }
        delay(250)
        ready = true
    }
    LaunchedEffect(progress.id, ready, progress.fraction, progress.finished) {
        if (!ready) return@LaunchedEffect
        val target = progress.fraction.coerceIn(displayed.value, 1f)
        val remaining = 1000L - (SystemClock.elapsedRealtime() - started) - 180L
        val duration = if (target == 1f) maxOf(250, remaining.toInt()) else 250
        displayed.animateTo(target, tween(duration))
        if (progress.finished && displayed.value == 1f) {
            delay((1000L - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(180L))
            show = false
        }
    }
    OverlayDialog(show = show, title = stringResource(if (progress.comparing) R.string.compare_progress_title else R.string.compare_importing),
        maxWidth = if (ToolDevice.isPad) Theme.sizes.dialogMax else DialogDefaults.MaxWidth,
        onDismissRequest = dismiss, onDismissFinished = { if (!show) finish(progress.id) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Theme.spacing.lg)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (progress.comparing) R.string.compare_progress_label else R.string.compare_import_progress_label),
                    Modifier.weight(1f), style = Theme.typography.body)
                Text(stringResource(R.string.progress_percent, (displayed.value * 100).toInt()),
                    style = Theme.typography.title.copy(fontFeatureSettings = "tnum"), color = MiuixTheme.colorScheme.primary)
            }
            LinearProgressIndicator(progress = displayed.value)
            if (progress.comparing && progress.saving && !progress.finished) {
                Text(stringResource(R.string.compare_saving), style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            TextButton(stringResource(R.string.cancel), dismiss, Modifier.fillMaxWidth(), enabled = !progress.finished && show)
        }
    }
}
