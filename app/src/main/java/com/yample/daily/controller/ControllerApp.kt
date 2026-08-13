package com.yample.daily.controller

import android.app.Application
import com.yample.mqttprotocol.ThemeManager

/**
 * 控制端 Application：冷启动时恢复主题；若已开启「设备通知」，尽快拉起监测服务以恢复 Broker 告警会话。
 */
class ControllerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.apply(this)
        if (OfflineMonitorService.isEnabled(this)) {
            try {
                OfflineMonitorService.startCompat(this)
            } catch (_: Exception) {
            }
        }
    }
}
