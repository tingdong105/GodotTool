package com.winter.godot.tool.ui

import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

/**
 * 工具页面的导航目标。
 * @author Tingdong
 * @date 2026-11-24
 */
enum class Screen : NavKey { HOME, TASKS, SETTINGS, FILES, WORKSPACE, RECOVERY, DEFAULTS }

/**
 * 为官方 NavDisplay 提供可恢复的业务返回栈。
 * @author Tingdong
 * @date 2026-11-24
 */
class ToolNavigation(private val saved: SavedStateHandle) {
    // SavedStateHandle 持久化枚举名称，返回栈由官方控制器管理。
    val entries = navBackStackOf(*restore().toTypedArray())
    private val controller = NavController(entries)
    private val mutableStack = MutableStateFlow(entries.map { it as Screen })
    val stack = mutableStack.asStateFlow()

    /**
     * 切换一级标签或进入子页面，重复目标返回既有条目。
     * @param screen 目标页面。
     * @return 是否发生页面切换。
     */
    fun navigate(screen: Screen): Boolean {
        val current = stack.value
        if (current.last() == screen) return false
        val index = current.indexOf(screen)
        Snapshot.withMutableSnapshot {
            when {
                screen == Screen.HOME || screen == Screen.SETTINGS -> {
                    controller.popUntil { entries.size == 1 }
                    controller.replace(screen)
                }
                index >= 0 -> controller.popUntil { it == screen }
                else -> controller.push(screen)
            }
        }
        commit()
        return true
    }

    /**
     * 返回上一页面，一级页面交给系统处理退出。
     * @return 是否弹出了子页面。
     */
    fun back(): Boolean {
        if (!controller.pop()) return false
        commit()
        return true
    }

    /**
     * 保存返回栈与兼容旧版本的当前页面名称。
     */
    private fun commit() {
        val value = entries.map { it as Screen }
        saved["navigationStack"] = ArrayList(value.map { it.name })
        saved["screen"] = value.last().name
        mutableStack.value = value
    }

    /**
     * 恢复已保存条目，并兼容旧版本的工作区来源。
     * @return 校验后的返回栈。
     */
    private fun restore(): List<Screen> {
        val stored = saved.get<ArrayList<String>>("navigationStack")
        if (stored != null) {
            val screens = stored.mapNotNull { name -> Screen.entries.firstOrNull { it.name == name } }
            if (screens.size == stored.size && screens.size in 1..Screen.entries.size && screens.distinct().size == screens.size &&
                screens.first() in listOf(Screen.HOME, Screen.SETTINGS) && screens.drop(1).none { it == Screen.HOME || it == Screen.SETTINGS }) return screens
        }
        return when (saved.get<String>("screen")) {
            Screen.SETTINGS.name -> listOf(Screen.SETTINGS)
            Screen.TASKS.name -> listOf(Screen.HOME, Screen.TASKS)
            Screen.WORKSPACE.name -> if (saved.get<String>("workspaceOrigin") == Screen.TASKS.name)
                listOf(Screen.HOME, Screen.TASKS, Screen.WORKSPACE) else listOf(Screen.HOME, Screen.WORKSPACE)
            else -> listOf(Screen.HOME)
        }
    }
}
