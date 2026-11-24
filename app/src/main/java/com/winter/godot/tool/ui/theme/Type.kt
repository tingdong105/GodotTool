package com.winter.godot.tool.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 跨页面复用的文字层级。
 * @author Tingdong
 * @date 2026-11-21
 */
class ToolTypography {
    val hero: TextStyle @Composable get() = MiuixTheme.textStyles.title1
    val title: TextStyle @Composable get() = MiuixTheme.textStyles.title4
    val body: TextStyle @Composable get() = MiuixTheme.textStyles.main
    val detail: TextStyle @Composable get() = MiuixTheme.textStyles.footnote1
    val code: TextStyle @Composable get() = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace)
    val number: TextStyle @Composable get() = MiuixTheme.textStyles.footnote1.copy(fontFeatureSettings = "tnum")
}
