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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.room.Room
import com.google.gson.JsonParser
import com.yample.mqttprotocol.BrokerUtils
import com.yample.mqttprotocol.MqttPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 设备通知前台服务（Broker 会话缓存版）：
 * - 固定 ClientId `ctl-mon-{deviceId}` + cleanSession=false，订阅 dt/{id}/alert（QoS1）
 * - 进程被杀 / 短暂离线后，Broker 在会话有效期内（EMQX Cloud MQTT3 默认约 2 小时）缓存离线告警，
 *   服务重启重连后补收并写入 [AlertHistory] + 系统通知
 * - 开关在 App 设置「设备通知」，默认关闭；须至少成功连上一次才会建立可缓存的会话
 */
class OfflineMonitorService : Service() {

    companion object {
        private const val TAG = "OfflineMonitor"
        private const val CHANNEL_ALERT = "offline_alerts"
        private const val CHANNEL_SERVICE = "offline_service"
        private const val SERVICE_NOTIF_ID = 1001
        private const val ALERT_NOTIF_BASE = 2001
        private const val SYNC_INTERVAL_MS = 60_000L
        /** 自动重连长时间失败后再重建客户端，避免每分钟拆会话 */
        private const val RECREATE_AFTER_MS = 5 * 60_000L

        const val PREFS = "daily_app"
        const val KEY_ENABLED = "notify_offline"
        const val KEY_LAST_OFFLINE_MS = "last_offline_ms"
        const val KEY_LAST_OFFLINE_DEVICE = "last_offline_device"

        const val ACTION_BATTERY_ALERT = "com.yample.daily.action.BATTERY_ALERT"
        const val ACTION_DEVICE_OFFLINE = "com.yample.daily.action.DEVICE_OFFLINE"
        const val ACTION_REFRESH_DEVICES = "com.yample.daily.action.REFRESH_ALERT_MONITOR"

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

        fun requestRefresh(context: Context) {
            if (!isEnabled(context)) return
            context.sendBroadcast(Intent(ACTION_REFRESH_DEVICES).setPackage(context.packageName))
        }

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

        /** 监测连接固定 ClientId，与设备控制页 ctl-{id} 分离，避免互踢 */
        fun monitorClientId(deviceId: String) = "ctl-mon-$deviceId"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var db: AppDatabase
    private val deviceById = ConcurrentHashMap<String, DeviceRecord>()
    private val clients = ConcurrentHashMap<String, MqttClient>()
    private val connFingerprint = ConcurrentHashMap<String, String>()
    private val lastConnectedAt = ConcurrentHashMap<String, Long>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private var receiversRegistered = false

    private val syncRunnable = object : Runnable {
        override fun run() {
            scope.launch { syncAlertClients() }
            mainHandler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    private val batteryAlertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val deviceName = intent?.getStringExtra("deviceName") ?: return
            val deviceId = intent.getStringExtra("deviceId") ?: return
            val battery = intent.getIntExtra("battery", -1)
            val predictedTime = intent.getStringExtra("predictedTime") ?: ""
            val title = "⚠️ 电量耗尽预警"
            val text = if (battery >= 0) "设备「$deviceName」当前电量 $battery%，预计 $predictedTime 耗尽"
            else "设备「$deviceName」将在 $predictedTime 耗尽，请及时充电"
            notifyAlert("battery_$deviceId", title, text)
        }
    }

    private val offlineReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val deviceName = intent?.getStringExtra("deviceName") ?: return
            val deviceId = intent.getStringExtra("deviceId") ?: return
            val ts = intent.getLongExtra("ts", System.currentTimeMillis())
            recordLastOffline(this@OfflineMonitorService, deviceName, deviceId, ts)
            val title = "📴 设备已离线"
            val text = "设备「$deviceName」已于 ${formatTs(ts)} 离线"
            AlertHistory.add(
                this@OfflineMonitorService,
                deviceId,
                AlertRecord(ts = ts, type = "device_offline", title = title, msg = text, battery = -1)
            )
            notifyAlert("offline_$deviceId", title, text)
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scope.launch { syncAlertClients() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "daily-db")
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        startAsForeground()
        if (!receiversRegistered) {
            registerLocal(batteryAlertReceiver, ACTION_BATTERY_ALERT)
            registerLocal(offlineReceiver, ACTION_DEVICE_OFFLINE)
            registerLocal(refreshReceiver, ACTION_REFRESH_DEVICES)
            receiversRegistered = true
        }
        mainHandler.removeCallbacks(syncRunnable)
        mainHandler.post(syncRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(syncRunnable)
        if (receiversRegistered) {
            runCatching { unregisterReceiver(batteryAlertReceiver) }
            runCatching { unregisterReceiver(offlineReceiver) }
            runCatching { unregisterReceiver(refreshReceiver) }
            receiversRegistered = false
        }
        // 非 clean 断连：保留 Broker 侧会话，便于会话有效期内补收 QoS1 告警
        clients.keys.toList().forEach { detachClient(it, clearSessionHint = false) }
        scope.cancel()
        super.onDestroy()
    }

    private fun registerLocal(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private suspend fun syncAlertClients() {
        val paired = try {
            db.deviceDao().getAll().filter { it.bound && it.sessionSecret.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "读设备列表失败", e)
            return
        }
        val ids = paired.map { it.deviceId }.toSet()
        (deviceById.keys - ids).forEach { detachClient(it, clearSessionHint = true) }
        paired.forEach { device ->
            deviceById[device.deviceId] = device
            ensureClient(device)
        }
        val n = clients.count { (_, c) ->
            try {
                c.isConnected
            } catch (_: Exception) {
                false
            }
        }
        updateForegroundText("会话监听 ${paired.size} 台（在线 $n）· 断线约 2h 内可补收")
    }

    private fun fingerprint(device: DeviceRecord) =
        "${device.broker}|${device.ctlUser}|${device.ctlPass}|${device.sessionSecret}"

    private fun ensureClient(device: DeviceRecord) {
        val id = device.deviceId
        val fp = fingerprint(device)
        deviceById[id] = device
        val existing = clients[id]
        if (existing != null) {
            if (connFingerprint[id] == fp) {
                val connected = try {
                    existing.isConnected
                } catch (_: Exception) {
                    false
                }
                if (connected) return
                val last = lastConnectedAt[id] ?: 0L
                // 交给 Paho automaticReconnect；仅长时间失败才重建，避免拆掉 Broker 会话
                if (last > 0L && System.currentTimeMillis() - last < RECREATE_AFTER_MS) return
                Log.w(TAG, "监测长时间未连上，重建客户端 $id")
                detachClient(id, clearSessionHint = false)
            } else {
                Log.i(TAG, "设备凭证/会话变更，重建监测连接 $id")
                detachClient(id, clearSessionHint = true)
            }
        }
        deviceById[id] = device
        if (!connecting.add(id)) return
        try {
            val persistDir = File(filesDir, "mqtt-mon-$id").apply { mkdirs() }
            val client = MqttClient(
                BrokerUtils.normalizeBroker(device.broker),
                monitorClientId(id),
                MqttDefaultFilePersistence(persistDir.absolutePath)
            )
            val opts = MqttConnectOptions().apply {
                userName = device.ctlUser
                password = device.ctlPass.toCharArray()
                isCleanSession = false
                isAutomaticReconnect = true
                connectionTimeout = 10
                keepAliveInterval = 60
                maxReconnectDelay = 60_000
            }
            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    lastConnectedAt[id] = System.currentTimeMillis()
                    try {
                        client.subscribe("${MqttPacket.TOPIC_PREFIX}/$id/alert", 1)
                        Log.d(TAG, "已订阅 alert deviceId=$id reconnect=$reconnect clientId=${monitorClientId(id)}")
                    } catch (e: Exception) {
                        Log.e(TAG, "订阅 alert 失败 $id", e)
                    }
                    val n = clients.count { (_, c) -> try { c.isConnected } catch (_: Exception) { false } }
                    updateForegroundText("会话监听中（在线 $n）· 断线约 2h 内可补收")
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "监测连接断开 $id: ${cause?.message}")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == null || message == null || !topic.endsWith("/alert")) return
                    handleAlertPayload(id, String(message.payload))
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            clients[id] = client
            connFingerprint[id] = fp
            client.connect(opts)
            if (client.isConnected) {
                lastConnectedAt[id] = System.currentTimeMillis()
                client.subscribe("${MqttPacket.TOPIC_PREFIX}/$id/alert", 1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "监测连接失败 $id", e)
            detachClient(id, clearSessionHint = false)
        } finally {
            connecting.remove(id)
        }
    }

    private fun handleAlertPayload(deviceId: String, payload: String) {
        val rec = deviceById[deviceId] ?: return
        val fromInbox = DeviceAlertInbox.accept(this, rec, payload)
        val accepted = fromInbox ?: run {
            val rid = try {
                JsonParser.parseString(payload).asJsonObject.get("rid")?.asString.orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (rid.isBlank()) return
            AlertHistory.load(this, deviceId).firstOrNull { it.rid == rid }
        } ?: return
        val name = rec.name.ifBlank { rec.deviceId }
        notifyAlert(
            "alert_${deviceId}_${accepted.rid.ifBlank { accepted.ts.toString() }}",
            accepted.title,
            "设备「$name」${accepted.msg}"
        )
    }

    /**
     * @param clearSessionHint true=解绑/换凭证，用 clean 连接清掉 Broker 会话；false=保留会话供补收
     */
    private fun detachClient(deviceId: String, clearSessionHint: Boolean) {
        connecting.remove(deviceId)
        val cached = deviceById.remove(deviceId)
        connFingerprint.remove(deviceId)
        val client = clients.remove(deviceId) ?: return
        val serverUri = try {
            client.serverURI
        } catch (_: Exception) {
            null
        }
        try {
            client.setCallback(null)
        } catch (_: Exception) {
        }
        if (clearSessionHint && !serverUri.isNullOrBlank()) {
            try {
                if (client.isConnected) client.disconnectForcibly(0, 500, true)
            } catch (_: Exception) {
            }
            try {
                client.close(true)
            } catch (_: Exception) {
            }
            try {
                val persistDir = File(filesDir, "mqtt-mon-$deviceId")
                val cleaner = MqttClient(
                    serverUri,
                    monitorClientId(deviceId),
                    MqttDefaultFilePersistence(persistDir.absolutePath)
                )
                val device = cached
                if (device != null && device.ctlPass.isNotBlank()) {
                    cleaner.connect(MqttConnectOptions().apply {
                        userName = device.ctlUser
                        password = device.ctlPass.toCharArray()
                        isCleanSession = true
                        connectionTimeout = 5
                    })
                    cleaner.disconnect()
                }
                cleaner.close(true)
            } catch (_: Exception) {
            }
        } else {
            // 不发 DISCONNECT 包，模拟异常掉线，Broker 按会话过期时间保留离线队列
            try {
                client.disconnectForcibly(0, 0, false)
            } catch (_: Exception) {
            }
            try {
                client.close(true)
            } catch (_: Exception) {
            }
        }
        lastConnectedAt.remove(deviceId)
    }

    private fun startAsForeground() {
        ServiceCompat.startForeground(
            this, SERVICE_NOTIF_ID, buildServiceNotification("正在建立告警会话…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun updateForegroundText(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(SERVICE_NOTIF_ID, buildServiceNotification(text))
    }

    private fun buildServiceNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("设备通知监测中")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .build()

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
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifyAlert(tag: String, title: String, text: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent())
                .build()
            NotificationManagerCompat.from(this).notify(tag, ALERT_NOTIF_BASE, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun formatTs(ts: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        return "%02d-%02d %02d:%02d".format(
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE)
        )
    }
}
