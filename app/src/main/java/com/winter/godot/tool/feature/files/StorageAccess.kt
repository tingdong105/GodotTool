package com.winter.godot.tool.feature.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 自有文件选择器与公共目录的权限入口。
 * @author Tingdong
 * @date 2026-11-21
 */
object StorageAccess {
    /**
     * 判断是否可直接读写公共文件。
     * @param context 应用环境。
     * @return 是否已授权。
     */
    fun granted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /**
     * 获取当前用户的共享存储根目录。
     */
    @Suppress("DEPRECATION")
    fun root(): File = Environment.getExternalStorageDirectory()

    /**
     * 获取 GodotTool 的公共输出目录。
     */
    fun outputRoot(): File = File(root(), "GodotTool")

    /**
     * 获取版本对比的公共结果目录。
     * @return 共享存储中的 GodotTool/diff。
     */
    fun diffRoot(): File = File(outputRoot(), "diff")

    /** @return 共享存储中的本地备份目录。 */
    fun backupRoot(): File = File(outputRoot(), "backup")
}
