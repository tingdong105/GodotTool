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

// 石墨蓝灰底色，按页面、卡片和浮层递进提亮。
private val toolDarkColors = darkColorScheme(
    primary = Color(0xFF88B8F0),
    onPrimary = Color(0xFF152A43),
    primaryVariant = Color(0xFF355780),
    onPrimaryVariant = Color(0xFFD8E8FF),
    primaryContainer = Color(0xFF355780),
    onPrimaryContainer = Color(0xFFD8E8FF),
    disabledPrimary = Color(0xFF334359),
    disabledOnPrimary = Color(0xFF738399),
    disabledPrimaryButton = Color(0xFF334359),
    disabledOnPrimaryButton = Color(0xFF8393A8),
    disabledPrimarySlider = Color(0xFF465A73),
    secondary = Color(0xFF4B5869),
    onSecondary = Color(0xFFD1DBE8),
    secondaryVariant = Color(0xFF2B3542),
    onSecondaryVariant = Color(0xFFE0E8F2),
    disabledSecondary = Color(0xFF2A323D),
    disabledOnSecondary = Color(0xFF6E7A8C),
    disabledSecondaryVariant = Color(0xFF2A323D),
    disabledOnSecondaryVariant = Color(0xFF758296),
    secondaryContainer = Color(0xFF27303B),
    onSecondaryContainer = Color(0xFFAAB6C6),
    secondaryContainerVariant = Color(0xFF2B3542),
    onSecondaryContainerVariant = Color(0xFFB6C2D2),
    tertiaryContainer = Color(0xFF2E425C),
    onTertiaryContainer = Color(0xFFA8CCF7),
    tertiaryContainerVariant = Color(0xFF2B3542),
    surface = Color(0xFF161B21),
    onSurface = Color(0xFFE7EDF5),
    surfaceVariant = Color(0xFF1E252E),
    onSurfaceSecondary = Color(0xFFCBD5E2),
    onSurfaceVariantSummary = Color(0xFFAAB6C6),
    onSurfaceVariantActions = Color(0xFF98A7BA),
    disabledOnSurface = Color(0xFF758296),
    surfaceContainer = Color(0xFF1E252E),
    onSurfaceContainer = Color(0xFFE7EDF5),
    onSurfaceContainerVariant = Color(0xFFAAB6C6),
    surfaceContainerHigh = Color(0xFF27303B),
    onSurfaceContainerHigh = Color(0xFFACBACC),
    surfaceContainerHighest = Color(0xFF303A48),
    onSurfaceContainerHighest = Color(0xFFE7EDF5),
    background = Color(0xFF27303B),
    onBackground = Color(0xFFE7EDF5),
    onBackgroundVariant = Color(0xFFAAB6C6),
    outline = Color(0xFF4C5A6C),
    dividerLine = Color(0xFF3A4553),
    error = Color(0xFFF0A5A5),
    onError = Color(0xFF451E24),
    errorContainer = Color(0xFF4A2D35),
    onErrorContainer = Color(0xFFFFDADC),
    sliderKeyPoint = Color(0x4DA8CCF7),
    sliderKeyPointForeground = Color(0xFFA8CCF7),
    sliderBackground = Color(0xFF303A48),
)

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
 * @param updateSystemBars 是否即时更新系统栏图标。
 * @param content 应用内容。
 */
@Composable
fun GodotToolTheme(mode: ThemeMode = ThemeMode.SYSTEM, updateSystemBars: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val colors = if (dark) toolDarkColors else lightColorScheme()
    val context = LocalContext.current
    if (updateSystemBars) SideEffect {
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
    val success: Color @Composable get() = if (MiuixTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF85C9A3) else Color(0xFF238754)
    val warning: Color @Composable get() = if (MiuixTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFE2BD7B) else Color(0xFF946000)
}
