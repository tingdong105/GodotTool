package com.winter.godot.tool.feature.settings.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.theme.Theme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 带 QQ 高清头像和 GitHub 入口的开发者卡片。
 * @param modifier 布局与页面共用的模糊效果。
 * @param transparent 是否使用外部模糊背景。
 * @param onClick 打开开发者 GitHub 主页。
 */
@Composable
internal fun DeveloperCard(modifier: Modifier = Modifier, transparent: Boolean, onClick: () -> Unit) {
    Card(modifier = modifier, cornerRadius = Theme.spacing.lg,
        colors = CardDefaults.defaultColors(if (transparent) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer)) {
        ArrowPreference(title = stringResource(R.string.about_developer_name),
            summary = stringResource(R.string.about_developer_description),
            startAction = { DeveloperAvatar() }, onClick = onClick)
    }
}

/**
 * 用 Coil 3 加载并缓存圆形头像，预览和失败时使用默认图标。
 */
@Composable
private fun DeveloperAvatar() {
    val placeholder = rememberVectorPainter(MiuixIcons.ContactsCircle)
    val modifier = Modifier.size(Theme.sizes.largeIcon).clip(CircleShape)
        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
    if (LocalInspectionMode.current) {
        Image(placeholder, null, modifier)
        return
    }
    val context = LocalContext.current
    val request = remember(context) {
        ImageRequest.Builder(context).data(AboutViewModel.DEVELOPER_AVATAR_URL).crossfade(true).build()
    }
    AsyncImage(model = request, contentDescription = null, modifier = modifier,
        contentScale = ContentScale.Crop, placeholder = placeholder, error = placeholder, fallback = placeholder)
}
