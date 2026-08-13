package com.yample.daily.controller

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Android 15 + targetSdk 36 强制 edge-to-edge，[WindowCompat.setDecorFitsSystemWindows](true)
 * 已无法把内容顶出状态栏。给顶栏 AppBar 加 statusBars top padding，避免标题/时钟与系统状态栏重叠。
 */
object UiInsets {
    fun applyStatusBarPadding(activity: Activity, appBar: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }
    }
}
