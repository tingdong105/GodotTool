package com.winter.godot.tool.ui.components

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
 * @param id 操作身份。
 * @param fraction 实际字节进度。
 * @param finished 数据是否已成功提交。
 * @param cancellable 是否仍可取消。
 * @param title 弹窗标题。
 * @param label 进度说明。
 * @param detail 可选阶段说明。
 * @param allowFinishDismiss 完成态是否允许提前手动关闭。
 * @param onCancel 取消当前解析或对比。
 * @param onFinished 关闭动画结束后确认完成。
 */
@Composable
fun OperationProgressDialog(id: String, fraction: Float, finished: Boolean, title: String, label: String,
    onCancel: () -> Unit, onFinished: (String) -> Unit, cancellable: Boolean = true, detail: String? = null, allowFinishDismiss: Boolean = true) {
    val displayed = remember(id) { Animatable(0f) }
    val started = remember(id) { SystemClock.elapsedRealtime() }
    var ready by remember(id) { mutableStateOf(false) }
    var show by remember(id) { mutableStateOf(true) }
    val finish by rememberUpdatedState(onFinished)
    val dismiss: () -> Unit = {
        if (show && (if (finished) allowFinishDismiss else cancellable)) {
            show = false
            if (!finished) onCancel()
        }
    }
    LaunchedEffect(id) {
        withFrameNanos { }
        delay(250)
        ready = true
    }
    LaunchedEffect(id, ready, fraction, finished) {
        if (!ready) return@LaunchedEffect
        val target = (if (finished) 1f else fraction.coerceAtMost(0.99f)).coerceIn(displayed.value, 1f)
        val remaining = 1000L - (SystemClock.elapsedRealtime() - started) - 180L
        val duration = if (target == 1f) maxOf(250, remaining.toInt()) else 250
        displayed.animateTo(target, tween(duration))
        if (finished && displayed.value == 1f) {
            delay((1000L - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(180L))
            show = false
        }
    }
    OverlayDialog(show = show, title = title,
        maxWidth = if (ToolDevice.isPad) Theme.sizes.dialogMax else DialogDefaults.MaxWidth,
        onDismissRequest = dismiss, onDismissFinished = { if (!show) finish(id) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Theme.spacing.lg)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Text(label,
                    Modifier.weight(1f), style = Theme.typography.body)
                Text(stringResource(R.string.progress_percent, (displayed.value * 100).toInt()),
                    style = Theme.typography.title.copy(fontFeatureSettings = "tnum"), color = MiuixTheme.colorScheme.primary)
            }
            LinearProgressIndicator(progress = displayed.value)
            if (detail != null && !finished) {
                Text(detail, style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            TextButton(stringResource(R.string.cancel), dismiss, Modifier.fillMaxWidth(), enabled = !finished && cancellable && show)
        }
    }
}
