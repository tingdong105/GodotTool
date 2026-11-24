package com.winter.godot.tool.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.winter.godot.tool.data.ThemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 跨组件使用的语义间距。
 * @author Tingdong
 * @date 2026-11-21
 */
class ToolSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/**
 * 手机与平板共享的组件尺寸。
 * @author Tingdong
 * @date 2026-11-21
 */
class ToolSizes {
    val icon = 26.dp
    val largeIcon = 48.dp
    val row = 64.dp
    val corner = 24.dp
    val innerCorner = 12.dp
    val browserWidth = 360.dp
    val contentMax = 680.dp
    val dialogMax = 560.dp
    val tabletBreakpoint = 840.dp
}

/**
 * 共享设计规范入口。
 * @author Tingdong
 * @date 2026-11-21
 */
object Theme {
    val spacing = ToolSpacing()
    val sizes = ToolSizes()
    val typography = ToolTypography()
    val status = ToolStatusColors()
}

/**
 * miuix 主题与固定字体缩放。
 * @param mode 用户主题偏好。
 * @param content 应用内容。
 */
@Composable
fun GodotToolTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    val context = LocalContext.current
    SideEffect {
        (context as? Activity)?.window?.let { window -> WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        } }
    }
    val density = LocalDensity.current.density
    CompositionLocalProvider(LocalDensity provides Density(density, 1f)) { MiuixTheme(colors = colors, content = content) }
}

/**
 * 明暗主题下的日志语义颜色。
 * @author Tingdong
 * @date 2026-11-24
 */
class ToolStatusColors {
    val success: Color @Composable get() = if (MiuixTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF76DBA0) else Color(0xFF238754)
    val warning: Color @Composable get() = if (MiuixTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFEFBD68) else Color(0xFF946000)
}
