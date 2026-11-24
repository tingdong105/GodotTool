package com.winter.godot.tool.ui

import com.winter.godot.tool.data.TaskRecord

/**
 * 恢复草稿步骤或尚未确认的任务结果。
 * 使用示例：restoreHomeStep(savedStep, hasInput, busy, latest)。
 * @param savedStep 系统保存的草稿步骤。
 * @param hasInput 是否仍有待处理文件。
 * @param busy 是否正在执行或重连任务。
 * @param latest 最近一次任务。
 * @return 恢复后应展示的步骤。
 */
internal fun restoreHomeStep(savedStep: Int, hasInput: Boolean, busy: Boolean, latest: TaskRecord?): Int = when {
    busy -> 3
    latest != null && !latest.acknowledged -> 3
    hasInput -> savedStep.coerceIn(1, 3)
    else -> 1
}
