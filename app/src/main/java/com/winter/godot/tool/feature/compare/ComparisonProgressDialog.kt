package com.winter.godot.tool.feature.compare

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.winter.godot.tool.R
import com.winter.godot.tool.ui.components.OperationProgressDialog

/**
 * 复用完整进度动效，保留导入与对比的标题和收尾行为。
 * @param progress 当前真实进度。
 * @param onCancel 取消解析或对比。
 * @param onFinished 弹窗退出后的完成回调。
 */
@Composable
fun ComparisonProgressDialog(progress: ComparisonProgress, onCancel: () -> Unit, onFinished: (String) -> Unit) {
    OperationProgressDialog(progress.id, progress.fraction, progress.finished,
        stringResource(if (progress.comparing) R.string.compare_progress_title else R.string.compare_importing),
        stringResource(if (progress.comparing) R.string.compare_progress_label else R.string.compare_import_progress_label),
        onCancel, onFinished, detail = if (progress.comparing && progress.saving) stringResource(R.string.compare_saving) else null)
}
