package com.winter.godot.tool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.winter.godot.tool.ui.ToolRoute

/**
 * 承载 miuix 界面与系统文件选择器。
 * @author Tingdong
 * @date 2026-11-21
 */
class MainActivity : ComponentActivity() {
    /**
     * 初始化全面屏界面。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent { ToolRoute() }
    }
}
