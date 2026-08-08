package com.yample.daily.controller

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject

/**
 * 通知前台服务：接收被控端告警（低电量 / 开始充电 / 电量充满 / 电量智能预警）并在本地弹出通知。
 * 告警由 DeviceControlActivity 的 MQTT 连接接收后通过广播转发至此服务。
 * 不再做 MQTT 轮询探活（已由 DeviceControlActivity 的 LWT 及 handleStatus 覆盖）。
 * 开关在 App 设置页，默认关闭。
 */
class OfflineMonitorService : Service() {

    companion object {
        private const val CHANNEL_ALERT = "offline_alerts"
        private const val CHANNEL_SERVICE = "offline_service"
        private const val SERVICE_NOTIF_ID = 1001
        private const val ALERT_NOTIF_BASE = 2001

        const val PREFS = "daily_app"
        const val KEY_ENABLED = "notify_offline"
        const val KEY_LAST_OFFLINE_MS = "last_offline_ms"
        const val KEY_LAST_OFFLINE_DEVICE = "last_offline_device"

        const val ACTION_BATTERY_ALERT = "com.yample.daily.action.BATTERY_ALERT"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        }

        fun startCompat(context: Context) {
            context.startForegroundService(Intent(context, OfflineMonitorService::class.java))
        }

        fun stopCompat(context: Context) {
            context.stopService(Intent(context, OfflineMonitorService::class.java))
        }

        /** 记录最近一次离线时间（按设备分开存储，同一设备只保留最后一次） */
        fun recordLastOffline(context: Context, deviceName: String, deviceId: String, ts: Long = System.currentTimeMillis()) {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = "last_offline_ms_$deviceId"
            val prev = sp.getLong(key, 0L)
            if (ts > prev) {
                sp.edit()
                    .putLong(key, ts)
                    .putString("last_offline_name_$deviceId", deviceName)
                    .apply()
            }
        }

        /** 读取所有设备中最近一次离线的最新一条，返回 (设备名, 时间戳) */
        fun latestOffline(context: Context): Pair<String, Long> {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var bestName = ""
            var bestTs = 0L
            sp.all.forEach { (k, v) ->
                if (k.startsWith("last_offline_ms_") && v is Long && v > bestTs) {
                    bestTs = v
                    bestName = sp.getString("last_offline_name_${k.removePrefix("last_offline_ms_")}", "") ?: ""
                }
            }
            return bestName to bestTs
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val batteryAlertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val deviceName = intent?.getStringExtra("deviceName") ?: return
            val deviceId = intent.getStringExtra("deviceId") ?: return
            val battery = intent.getIntExtra("battery", -1)
            val predictedTime = intent.getStringExtra("predictedTime") ?: ""
            // 电池智能预警通知
            val title = "⚠️ 电量耗尽预警"
            val text = if (battery >= 0) "设备「$deviceName」当前电量 $battery%，预计 $predictedTime 耗尽"
                else "设备「$deviceName」将在 $predictedTime 耗尽，请及时充电"
            notifyAlert("battery_$deviceId", title, text)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        startAsForeground()
        // 注册电池预警广播接收器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryAlertReceiver, IntentFilter(ACTION_BATTERY_ALERT), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryAlertReceiver, IntentFilter(ACTION_BATTERY_ALERT))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryAlertReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_device)
            .setContentTitle("设备通知监测中")
            .setContentText("等待被控端告警事件")
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .build()
        ServiceCompat.startForeground(
            this, SERVICE_NOTIF_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "设备告警", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "被控端低电量、充电状态、电量智能预警等事件通知"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "设备通知监测服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "设备通知监测前台服务常驻通知"
            }
        )
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notifyAlert(tag: String, title: String, text: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_device)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent())
                .build()
            NotificationManagerCompat.from(this).notify(tag, ALERT_NOTIF_BASE, notification)
        } catch (_: SecurityException) { }
    }
}