package com.yample.daily.controller

import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 概览类页面「含义不清 + 点击无反应」信息条的统一说明绑定器。
 *
 * 复用项目双端统一的 [UnifiedDialogKit.showInfo] 单按钮信息弹窗；同时补一层透明 ripple，
 * 让「这一条可以点」在视觉上可感知（InfoChip / RowInfo 样式本身没有按压反馈）。
 *
 * - message 为 lambda，延迟到点击时求值，保证读到的是最新快照，而不是注册监听时的旧值；
 * - alive 用于 Fragment 页面销毁后守卫（页面已 detach 时不再弹窗，避免 requireContext 抛异常）；
 *   Activity 页面不需要传，默认 true。
 * - 仅在 onViewCreated 等一次性生命周期里绑定；不要放进会被多次调用的 render()。
 */
fun View.bindHelp(
    title: String,
    message: () -> String,
    alive: () -> Boolean = { true }
) {
    isClickable = true
    isFocusable = true
    val rippleAttr = TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleAttr, true)
    if (rippleAttr.resourceId != 0) {
        ContextCompat.getDrawable(context, rippleAttr.resourceId)?.let { foreground = it }
    }
    setOnClickListener {
        if (!alive()) return@setOnClickListener
        UnifiedDialogKit.showInfo(context, title, message())
    }
}

/** 设备时间压缩显示：yyyy-MM-dd HH:mm:ss → MM-dd HH:mm（chip / 行宽有限，秒级无意义）。
 *  非标准格式（含「—」占位）原样返回，兼容旧版 DT 与异常数据。 */
fun formatDeviceTimeShort(raw: String?): String {
    val v = raw?.takeIf { it.isNotBlank() } ?: return "--"
    return runCatching {
        DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .format(LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
    }.getOrDefault(v)
}

/** 时长压缩显示：秒 → 「X秒 / X分Y秒 / X时Y分 / X天Y时」，用于离线持续时长等诊断指标。 */
fun formatDurationShort(seconds: Long): String {
    if (seconds < 0L) return "—"
    return when {
        seconds < 60 -> "${seconds}秒"
        seconds < 3600 -> "${seconds / 60}分${seconds % 60}秒"
        seconds < 86400 -> "${seconds / 3600}时${seconds % 3600 / 60}分"
        else -> "${seconds / 86400}天${seconds % 86400 / 3600}时"
    }
}
