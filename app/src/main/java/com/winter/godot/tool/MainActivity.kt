package com.winter.godot.tool

import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.winter.godot.tool.ui.ToolRoute
import com.winter.godot.tool.ui.startup.StartupViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * 承载 miuix 界面与系统文件选择器。
 * @author Tingdong
 * @date 2026-11-21
 */
class MainActivity : ComponentActivity() {
    private var startupPreDraw: ViewTreeObserver.OnPreDrawListener? = null

    /**
     * 初始化全面屏界面。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startup = ViewModelProvider(this)[StartupViewModel::class.java]
        val windowGeneration = startup.resetWindow()
        setTheme(R.style.Theme_GodotTool)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { splash ->
            val icon = splash.iconView
            val bounds = if (icon != null) {
                val position = IntArray(2)
                icon.getLocationInWindow(position)
                // 系统以自适应图标前景绘制矢量，画布比图案 View 多一圈 inset。
                val inset = AdaptiveIconDrawable.getExtraInsetFraction()
                val extraX = (icon.width * inset).roundToInt()
                val extraY = (icon.height * inset).roundToInt()
                IntRect(position[0] - extraX, position[1] - extraY,
                    position[0] + icon.width + extraX, position[1] + icon.height + extraY)
            } else null
            if (!startup.prepareHandoff(bounds, windowGeneration)) {
                splash.remove()
                return@setOnExitAnimationListener
            }
            lifecycleScope.launch {
                try {
                    withTimeoutOrNull(500) { snapshotFlow { startup.handoffDrawn || startup.finished }.first { it } }
                } finally {
                    window.decorView.post {
                        splash.remove()
                        startup.releaseSystem(windowGeneration)
                    }
                }
            }
        }
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            /** 本地动画就绪或跳过后允许窗口绘制。 */
            override fun onPreDraw(): Boolean {
                if (!startup.canDraw) return false
                window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                startupPreDraw = null
                return true
            }
        }
        startupPreDraw = listener
        window.decorView.viewTreeObserver.addOnPreDrawListener(listener)
        setContent { ToolRoute(startup = startup) }
    }

    /** 清理尚未交接的启动屏绘制监听。 */
    override fun onDestroy() {
        startupPreDraw?.let { window.decorView.viewTreeObserver.removeOnPreDrawListener(it) }
        startupPreDraw = null
        super.onDestroy()
    }
}
