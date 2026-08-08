package com.yample.daily.controller

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import com.yample.mqttprotocol.BrokerUtils
import com.yample.mqttprotocol.MqttPacket
import java.util.concurrent.TimeUnit

/**
 * 离线通知前台服务：周期探测已配对设备的在线状态（订阅 retained 状态主题），
 * 设备由「在线 → 离线」时发送本地通知（恢复在线也通知）。开关在 App 设置页，默认关闭。
 */
class OfflineMonitorService : Service() {

    companion object {
        private const val CHANNEL_ALERT = "offline_alerts"
        private const val CHANNEL_SERVICE = "offline_service"
        private const val SERVICE_NOTIF_ID = 1001
        private const val ALERT_NOTIF_BASE = 2001
        private const val MONITOR_INTERVAL_MS = 60_000L
        private const val PROBE_TIMEOUT_MS = 3_000L

        const val PREFS = "daily_app"
        const val KEY_ENABLED = "notify_offline"
        const val KEY_LAST_OFFLINE_MS = "last_offline_ms"

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

        /**
         * 记录「最近一次离线时间」（全局，取最新值）。与「离线通知」开关解耦——
         * 即便未开启通知，设备页 LWT 检测到离线也可写入，保证设置页展示真实最近离线时间。
         * 守卫：仅当 ts 比已存值更新才覆盖，避免多设备/重复到达时把时间戳改旧。
         */
        fun recordLastOffline(context: Context, ts: Long = System.currentTimeMillis()) {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prev = sp.getLong(KEY_LAST_OFFLINE_MS, 0L)
            if (ts > prev) sp.edit().putLong(KEY_LAST_OFFLINE_MS, ts).apply()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db by lazy {
        Room.databaseBuilder(applicationContext, AppDatabase::class.java, "daily-db")
            .fallbackToDestructiveMigration()
            .build()
    }
    /** deviceId → 上次探测的在线状态（null=未知，首轮仅建基线不发通知） */
    private val onlineState = mutableMapOf<String, Boolean?>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        startAsForeground()
        scope.launch { monitorLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_device)
            .setContentTitle("离线通知监测中")
            .setContentText("正在监测已配对设备的在线状态")
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
            NotificationChannel(CHANNEL_ALERT, "设备离线提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "设备由在线变为离线时提醒"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "离线监测服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "离线监测前台服务常驻通知"
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

    private suspend fun monitorLoop() {
        while (true) {
            delay(MONITOR_INTERVAL_MS)
            runOnce()
        }
    }

    /** 探测所有已配对设备，比对状态并通知离线/恢复事件 */
    private suspend fun runOnce() {
        val devices = try {
            db.deviceDao().getAll()
        } catch (_: Exception) {
            return
        }
        val bound = devices.filter { it.sessionSecret.isNotBlank() && it.bound }
        val result = mutableMapOf<DeviceRecord, Boolean?>()
        bound.forEach { result[it] = probe(it) }
        bound.forEach { device ->
            val now = result[device]
            val prev = onlineState[device.deviceId]
            onlineState[device.deviceId] = now
            // 探活结果写共享缓存，供设备列表复用（避免双通道重复探活）
            OnlineStateCache.put(device.deviceId, now, System.currentTimeMillis())
            // 首轮只建立基线不发通知；之后在线→离线提醒，离线→在线也提醒
            if (prev != null) {
                when {
                    prev == true && now == false -> notifyOffline(device)
                    prev == false && now == true -> notifyRecovered(device)
                }
            }
        }
        val ids = bound.map { it.deviceId }.toSet()
        onlineState.keys.retainAll(ids)
    }

    /** 短连接订阅状态主题读取 retained 消息：online→true / offline→false / 其它或超时→null */
    private suspend fun probe(device: DeviceRecord): Boolean? {
        return withContext(Dispatchers.IO) {
            try {
                val client = MqttClient(
                    BrokerUtils.normalizeBroker(device.broker),
                    "ctl-monitor-${device.deviceId}",
                    MemoryPersistence()
                )
                val opts = MqttConnectOptions().apply {
                    userName = device.ctlUser
                    password = device.ctlPass.toCharArray()
                    isCleanSession = true
                    connectionTimeout = 5
                }
                val latch = java.util.concurrent.CompletableFuture<String>()
                client.setCallback(object : MqttCallback {
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        message?.payload?.let { latch.complete(String(it).trim()) }
                    }
                    override fun connectionLost(cause: Throwable?) {}
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                client.connect(opts)
                client.subscribe("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/status", 1)
                val got = try {
                    latch.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    null
                }
                client.disconnect()
                client.close()
                when (got) {
                    "online" -> true
                    "offline" -> false
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun notifyOffline(device: DeviceRecord) {
        // 记录最近一次离线时间（在线→离线的真实转跳，由 runOnce 的 prev/now 判定），供设置页展示
        recordLastOffline(this)
        notifyAlert(device.deviceId, "设备离线", "${device.name} 已离线，请检查其网络与后台状态")
    }

    private fun notifyRecovered(device: DeviceRecord) {
        notifyAlert(device.deviceId, "设备已恢复在线", "${device.name} 已重新上线")
    }

    private fun notifyAlert(tag: String, title: String, text: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_device)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent())
                .build()
            NotificationManagerCompat.from(this).notify(tag, ALERT_NOTIF_BASE, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS 被拒：前台服务照常运行，但不显示提醒
        }
    }
}
