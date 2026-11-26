package com.winter.godot.tool

import android.app.Application
import com.tencent.mmkv.MMKV
import com.winter.godot.tool.data.ToolRepository
import com.winter.godot.tool.feature.backup.BackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 初始化主进程存储与应用级任务仓库。
 * @author Tingdong
 * @date 2026-11-21
 */
class App : Application() {

    val repository: ToolRepository by lazy {
        ToolRepository(this)
    }

    private var startupClaimed = false

    private val dataPreparation = Mutex()

    private var dataPrepared = false

    /**
     * 仅在主进程初始化 MMKV。
     */
    override fun onCreate() {
        super.onCreate()
        if (getProcessName() == packageName) MMKV.initialize(this)
    }

    /**
     * 领取当前进程唯一的启动动画。
     * @return 是否首次创建主界面。
     */
    fun claimStartup(): Boolean {
        if (startupClaimed) return false
        startupClaimed = true
        return true
    }

    /** 在任何业务仓库读取前，于 IO 线程恢复中断事务。 */
    suspend fun prepareData() = dataPreparation.withLock {
        if (!dataPrepared) {
            withContext(Dispatchers.IO) {
                BackupRepository(this@App).apply { recover(); cleanupAbandoned() }
            }
            dataPrepared = true
        }
    }
}
