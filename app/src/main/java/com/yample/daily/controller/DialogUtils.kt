package com.yample.daily.controller

import android.content.Context

/**
 * 破坏性操作确认框（删除 / 解绑等不可逆操作）。
 * 现委托 [UnifiedDialogKit.showWarning] 实现，统一 ② 类弹窗外观：
 * 红色警示图标 + 错误色加粗确认按钮，与双端规范一致。
 */
fun showDestructiveConfirm(
    context: Context,
    title: String,
    message: String,
    confirmText: String = "删除",
    onConfirm: () -> Unit
) {
    UnifiedDialogKit.showWarning(
        ctx = context,
        title = title,
        message = message,
        confirmText = confirmText,
        onConfirm = onConfirm
    )
}
