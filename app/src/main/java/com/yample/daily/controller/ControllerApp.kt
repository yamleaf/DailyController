package com.yample.daily.controller

import android.app.Application
import com.yample.mqttprotocol.ThemeManager

/**
 * 控制端 Application：冷启动时恢复用户选择的主题（深色 / 浅色 / 跟随系统）。
 */
class ControllerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.apply(this)
    }
}
