@file:OptIn(ExperimentalScrollBarApi::class)

package com.winter.godot.tool.feature.settings.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.ToolDevice
import com.winter.godot.tool.ui.components.ToolIcon
import com.winter.godot.tool.ui.theme.Theme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 参考关于页的专用尺寸和模糊参数。
 * @author Tingdong
 * @date 2026-11-25
 */
private object AboutLayout {
    val headerOffset = 92.dp
    val headerGap = 126.dp
    val logoSlot = 88.dp
    val logoMask = 160.dp
    val fallbackIcon = 80.dp
    val nameSize = 35.sp
    val versionSize = 14.sp
    val shape = RoundedCornerShape(16.dp)
}

/**
 * 组合流光、分阶段淡出的应用信息与关于卡片。
 * @param state 当前安装版本。
 * @param active 是否为导航栈当前页面。
 * @param onBack 返回设置。
 * @param onSource 打开项目仓库。
 * @param onLicenses 打开离线许可。
 * @param onDeveloper 打开开发者主页。
 */
@Composable
fun AboutScreen(state: AboutUiState, active: Boolean, onBack: () -> Unit, onSource: () -> Unit, onLicenses: () -> Unit,
    onDeveloper: () -> Unit = {}) {
    val list = rememberLazyListState()
    var spacerHeight by remember { mutableIntStateOf(0) }
    val progress by remember {
        derivedStateOf {
            when {
                list.firstVisibleItemIndex > 0 -> 1f
                spacerHeight <= 0 -> 0f
                else -> (list.firstVisibleItemScrollOffset.toFloat() / spacerHeight).coerceIn(0f, 1f)
            }
        }
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val playing = active && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && progress < 1f
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val supported = isRuntimeShaderSupported() && !LocalInspectionMode.current
    val topBackdrop = if (supported) rememberLayerBackdrop() else null
    val surface = MiuixTheme.colorScheme.surface
    val topColors = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(surface.copy(alpha = 0.8f))))
    val tablet = ToolDevice.isPad
    Scaffold(modifier = Modifier.fillMaxSize().then(if (tablet) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)) else Modifier),
        contentWindowInsets = if (tablet) WindowInsets.safeDrawing else WindowInsets.systemBars.union(WindowInsets.displayCutout), topBar = {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            SmallTopAppBar(title = stringResource(R.string.about_software),
                modifier = Modifier.widthIn(max = Theme.sizes.contentMax).fillMaxWidth().then(
                    if (topBackdrop != null && progress == 1f) Modifier.textureBlur(topBackdrop, RectangleShape,
                        colors = topColors) else Modifier),
                color = if (topBackdrop != null || progress < 1f) Color.Transparent else surface,
                titleColor = MiuixTheme.colorScheme.onSurface.copy(alpha = progress),
                navigationIconPadding = Theme.spacing.xs,
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(Theme.sizes.largeIcon)) {
                        ToolIcon(R.drawable.ic_back, description = stringResource(R.string.back))
                    }
                })
        }
    }) { padding ->
        AboutBackground(dark, playing, { 1f - progress },
            Modifier.fillMaxSize().then(if (topBackdrop != null) Modifier.layerBackdrop(topBackdrop) else Modifier)) { backdrop ->
            AboutContent(state, padding, list, { progress }, backdrop, dark, { spacerHeight = it }, onSource, onLicenses, onDeveloper)
        }
    }
}

/**
 * 将固定应用信息与透明占位的滚动列表叠放。
 * @param onSpacerHeight 测量滚动动画的完整行程。
 * @param progress 返回当前滚动比例。
 * @param backdrop 仅记录流光的背景采样层。
 */
@Composable
private fun AboutContent(state: AboutUiState, padding: PaddingValues, list: LazyListState, progress: () -> Float,
    backdrop: LayerBackdrop?, dark: Boolean, onSpacerHeight: (Int) -> Unit, onSource: () -> Unit, onLicenses: () -> Unit,
    onDeveloper: () -> Unit) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var headerHeight by remember { mutableStateOf(166.dp) }
    val logoColors = remember(dark) {
        BlurColors(blendColors = if (dark) listOf(
            BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
            BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
            BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
        ) else listOf(
            BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
            BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
            BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
        ))
    }
    val cardColors = remember(dark) {
        BlurColors(blendColors = if (dark) listOf(
            BlendColorEntry(Color(0x757a7a7a), BlurBlendMode.Luminosity),
        ) else listOf(
            BlendColorEntry(Color(0x340034f9), BlurBlendMode.Overlay),
            BlendColorEntry(Color(0xb3ffffff), BlurBlendMode.HardLight),
        ))
    }
    val cardModifier = Modifier.padding(horizontal = Theme.spacing.md).padding(bottom = Theme.spacing.md)
        .then(if (backdrop != null) Modifier.textureBlur(backdrop, AboutLayout.shape,
            blurRadius = 60f, colors = cardColors) else Modifier)
    Box(Modifier.fillMaxSize().padding(start = padding.calculateStartPadding(direction),
        end = padding.calculateEndPadding(direction)), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Theme.sizes.contentMax).fillMaxWidth()
            .padding(top = padding.calculateTopPadding() + AboutLayout.headerOffset)
            .onSizeChanged { with(density) { headerHeight = it.height.toDp() } },
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(AboutLayout.logoSlot).aboutHeaderFade(progress, 0.35f), contentAlignment = Alignment.Center) {
                if (backdrop != null) {
                    Image(painterResource(R.drawable.ic_launcher_monochrome), null,
                        Modifier.requiredSize(AboutLayout.logoMask).textureBlur(backdrop, AboutLayout.shape,
                            blurRadius = 200f, colors = logoColors, contentBlendMode = BlendMode.DstIn))
                } else {
                    Box(Modifier.size(AboutLayout.fallbackIcon).clip(AboutLayout.shape)) {
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                    }
                }
            }
            Text(stringResource(R.string.app_name),
                Modifier.padding(top = Theme.spacing.md, bottom = 5.dp).aboutHeaderFade(progress, 0.20f)
                    .then(if (backdrop != null) Modifier.textureBlur(backdrop, AboutLayout.shape,
                        blurRadius = 150f, colors = logoColors, contentBlendMode = BlendMode.DstIn) else Modifier),
                fontWeight = FontWeight.Bold, fontSize = AboutLayout.nameSize)
            Text(if (state.versionName != null) stringResource(R.string.about_version, state.versionName, state.versionCode)
                else stringResource(R.string.about_version_unavailable),
                Modifier.aboutHeaderFade(progress, 0.05f), fontSize = AboutLayout.versionSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
        }
        LazyColumn(state = list, modifier = Modifier.widthIn(max = Theme.sizes.contentMax).fillMaxSize()
            .scrollEndHaptic().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            item(key = "about_header") {
                Spacer(Modifier.fillMaxWidth().height(headerHeight + AboutLayout.headerOffset + AboutLayout.headerGap)
                    .onSizeChanged { onSpacerHeight(it.height) })
            }
            item(key = "about_links") {
                Column(Modifier.fillParentMaxHeight().padding(bottom = padding.calculateBottomPadding())) {
                    SmallTitle(stringResource(R.string.about))
                    Card(modifier = cardModifier,
                        cornerRadius = Theme.spacing.lg,
                        colors = CardDefaults.defaultColors(if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer)) {
                        ArrowPreference(title = stringResource(R.string.about_source), summary = stringResource(R.string.about_source_detail), onClick = onSource)
                        ArrowPreference(title = stringResource(R.string.licenses), summary = stringResource(R.string.about_licenses_detail), onClick = onLicenses)
                    }
                    SmallTitle(stringResource(R.string.about_developer))
                    DeveloperCard(modifier = cardModifier, transparent = backdrop != null, onClick = onDeveloper)
                }
            }
        }
        Box(Modifier.widthIn(max = Theme.sizes.contentMax).fillMaxSize()) {
            VerticalScrollBar(adapter = rememberScrollBarAdapter(list), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                trackPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()))
        }
    }
}

/**
 * 分阶段淡出应用信息，并让完全透明的内容退出无障碍树。
 * 使用示例：Modifier.aboutHeaderFade({ progress }, 0.2f)。
 * @param progress 整体滚动比例。
 * @param start 当前元素开始淡出的比例。
 * @return 带透明度与缩放的修饰符。
 */
@Composable
private fun Modifier.aboutHeaderFade(progress: () -> Float, start: Float): Modifier {
    val hidden by remember(progress, start) { derivedStateOf { progress() >= start + 0.15f } }
    return this.graphicsLayer {
        val fraction = ((progress() - start) / 0.15f).coerceIn(0f, 1f)
        alpha = 1f - fraction
        scaleX = 1f - fraction * 0.05f
        scaleY = scaleX
    }.then(if (hidden) Modifier.clearAndSetSemantics {} else Modifier)
}
