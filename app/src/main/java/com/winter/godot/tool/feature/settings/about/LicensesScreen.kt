package com.winter.godot.tool.feature.settings.about

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.LocalToolContentPadding
import com.winter.godot.tool.ui.components.LoadingContent
import com.winter.godot.tool.ui.theme.Theme
import com.winter.godot.tool.ui.toolContentPadding
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
        state.licenseFailed -> Box(Modifier.fillMaxSize().padding(LocalToolContentPadding.current), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(Theme.spacing.xl), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Theme.spacing.md)) {
                Text(stringResource(R.string.about_licenses_failed), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                TextButton(stringResource(R.string.retry), onRetry)
            }
        }
        state.licenses == null -> Box(Modifier.fillMaxSize().padding(LocalToolContentPadding.current)) { LoadingContent() }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = toolContentPadding(PaddingValues(Theme.spacing.xl))) {
                itemsIndexed(state.licenses, key = { index, _ -> index }) { _, text ->
                    SelectionContainer {
                        Text(text, style = Theme.typography.detail, color = MiuixTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
