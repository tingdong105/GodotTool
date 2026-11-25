package com.winter.godot.tool.feature.settings.about

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.components.LoadingContent
import com.winter.godot.tool.ui.components.ToolDialog
import com.winter.godot.tool.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分段阅读可选择复制的离线许可。
 * @param state 许可加载状态。
 * @param onRetry 重新读取失败的许可资源。
 */
@Composable
fun LicensesScreen(state: AboutUiState, onRetry: () -> Unit) {
    when {
        state.licenseFailed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(Theme.spacing.xl), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
                Text(stringResource(R.string.about_licenses_failed), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                TextButton(stringResource(R.string.retry), onRetry)
            }
        }
        state.licenses == null -> LoadingContent()
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(Theme.spacing.xl)) {
                itemsIndexed(state.licenses, key = { index, _ -> index }) { _, text ->
                    SelectionContainer {
                        Text(text, style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/**
 * 从完整原文复制全部许可，独立于列表已显示的文本。
 * @param text 完整许可，未加载或失败时为空。
 */
@Composable
fun LicensesCopyButton(text: String?) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.licenses)
    var copying by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    TextButton(stringResource(R.string.about_copy_licenses), {
        if (!copying && !text.isNullOrEmpty()) {
            copying = true
            scope.launch {
                try {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, R.string.about_licenses_copied, Toast.LENGTH_SHORT).show()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                } finally {
                    copying = false
                }
            }
        }
    }, enabled = !text.isNullOrEmpty() && !copying)
    if (failed) ToolDialog(stringResource(R.string.notice), { failed = false }) {
        Text(stringResource(R.string.about_copy_failed))
        TextButton(stringResource(R.string.confirm), { failed = false })
    }
}
