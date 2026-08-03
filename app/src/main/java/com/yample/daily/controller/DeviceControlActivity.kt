package com.yample.daily.controller

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yample.daily.controller.databinding.ActivityDeviceControlBinding
import com.yample.mqttprotocol.BindingPayload
import com.yample.mqttprotocol.BrokerUtils
import com.yample.mqttprotocol.Hkdf
import com.yample.mqttprotocol.MqttPacket
import com.yample.mqttprotocol.MqttQuota
import com.yample.mqttprotocol.MqttSigner
import com.yample.mqttprotocol.PacketValue
import com.yample.mqttprotocol.PacketValueAdapter
import com.yample.mqttprotocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class DeviceControlActivity : AppCompatActivity() {
    lateinit var binding: ActivityDeviceControlBinding
    lateinit var device: DeviceRecord
        private set
    private lateinit var db: AppDatabase
    private var mqttClient: MqttClient? = null
    private val gson = GsonBuilder()
        .registerTypeAdapter(PacketValue::class.java, PacketValueAdapter)
        .create()
    private val TAG = "DeviceControlActivity"
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var pairRetries = 0
    private val PAIR_MAX_RETRIES = 3
    private val PAIR_TIMEOUT_MS = 12_000L
    private val PAIR_TIMEOUT_RUNNABLE = Runnable { onPairTimeout() }
    private val REFRESH_MS = 15_000L
    private val refreshRunnable = Runnable { doPeriodicRefresh() }
    private val QUERY_TIMEOUT_MS = 12_000L
    private val queryTimeoutRunnable = Runnable { onQueryTimeout() }
    /** D2：动作下发兜底恢复（ACK 丢失时 10s 后自动解灰） */
    private val actionBusyResetRunnable = Runnable { overviewFragment.setActionsBusy(false) }
    private var queryPendingRid: String? = null
    private var queryRetryCount = 0
    private val QUERY_MAX_RETRIES = 2

    private var currentSnapshot: DeviceSnapshot? = null
    /** 原始快照 JSON（按区块合并增量推送用），持久化到本地缓存 */
    private var snapshotJson: JsonObject? = null
    private var currentFragmentTag = TAG_OVERVIEW

    private val prefs by lazy { getSharedPreferences("remote_ctrl", MODE_PRIVATE) }
    private var remoteEnabled = true
    /** 增量推送主题 dt/{id}/push 是否订阅成功；失败则回退到 15s 轮询全量 */
    private var pushAvailable = true
    /** B3：Toast 去重状态 —— 相同 key 在窗口内只弹一次，避免失败/离线态循环轰炸 */
    private var lastToastKey: String? = null
    private var lastToastAt = 0L
    /** B5：最近一次查询发送时间戳，用于推导连接质量(RTT) */
    private var lastQuerySentTs = 0L
    /** B5：最近一次下发指令的展示名，用于「最近指令」回执关联 */
    private var lastCommandLabel = ""

    private lateinit var overviewFragment: OverviewFragment
    private lateinit var tasksFragment: TasksFragment
    private lateinit var calendarFragment: CalendarFragment
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var permissionsFragment: PermissionsFragment

    companion object {
        private const val TAG_OVERVIEW = "overview"
        private const val TAG_TASKS = "tasks"
        private const val TAG_CALENDAR = "calendar"
        private const val TAG_SETTINGS = "settings"
        private const val TAG_PERMISSIONS = "permissions"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val deviceId = intent.getStringExtra("deviceId")
        if (deviceId.isNullOrBlank()) { finish(); return }
        db = Room.databaseBuilder(this, AppDatabase::class.java, "daily-db")
            .fallbackToDestructiveMigration().build()
        val loaded = runBlocking(Dispatchers.IO) { db.deviceDao().getById(deviceId) }
        if (loaded == null) {
            Toast.makeText(this, "设备记录不存在", Toast.LENGTH_SHORT).show(); finish(); return
        }
        device = loaded
        remoteEnabled = prefs.getBoolean("remote_enabled_${device.deviceId}", true)

        // 加载本地缓存快照：即使 MQTT 尚未连接 / 已关闭，也先展示上次同步的设备数据
        lifecycleScope.launch(Dispatchers.IO) {
            val cached = prefs.getString("snapshot_cache_${device.deviceId}", null)
            if (!cached.isNullOrBlank()) {
                try {
                    snapshotJson = JsonParser.parseString(cached).asJsonObject
                    currentSnapshot = parseSnapshot(cached)
                    runOnUiThread {
                        refreshCurrentFragment()
                        // D3：展示的是离线缓存快照，标记「可能已过期」
                        overviewFragment.setStaleBanner(true)
                    }
                } catch (_: Exception) { }
            }
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = device.name

        initFragments()
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_overview -> switchTab(TAG_OVERVIEW)
                R.id.nav_tasks -> switchTab(TAG_TASKS)
                R.id.nav_calendar -> switchTab(TAG_CALENDAR)
                R.id.nav_settings -> switchTab(TAG_SETTINGS)
                R.id.nav_permissions -> switchTab(TAG_PERMISSIONS)
            }
            true
        }

        if (remoteEnabled) {
            setConnStatus("连接中…", false)
            initMqtt()
        } else {
            setConnStatus("MQTT 已关闭", false)
            overviewFragment.setSnapshotHint(SnapshotHint.DISABLED)
        }
    }

    private fun initFragments() {
        overviewFragment = OverviewFragment().apply {
            onRefreshClick = {
                overviewFragment.setRefreshing(false)
                sendQuery()
            }
            onUnbindClick = { confirmUnbind() }
            onRemoteToggle = { on -> this@DeviceControlActivity.setRemoteEnabled(on) }
            onAction = { action -> sendAction(action) }
            onRePairClick = { retryPair() }
            onRetryClick = { retryConnection() }
            setRemoteEnabled(remoteEnabled)
        }
        tasksFragment = TasksFragment().apply {
            onAddTask = { time -> sendTask("add", time, null) }
            onDeleteTask = { item -> sendTask("delete", item.time, item.time) }
        }
        calendarFragment = CalendarFragment()
        settingsFragment = SettingsFragment().apply {
            onToggle = { item, on -> sendUpdate(item.key, PacketValue.BooleanValue(on)) }
            onIntChange = { item, v -> sendUpdate(item.key, PacketValue.IntValue(v)) }
        }
        permissionsFragment = PermissionsFragment()

        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, overviewFragment, TAG_OVERVIEW)
            .add(R.id.fragmentContainer, tasksFragment, TAG_TASKS)
            .add(R.id.fragmentContainer, calendarFragment, TAG_CALENDAR)
            .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
            .add(R.id.fragmentContainer, permissionsFragment, TAG_PERMISSIONS)
            .hide(tasksFragment)
            .hide(calendarFragment)
            .hide(settingsFragment)
            .hide(permissionsFragment)
            .commitNow()
        overviewFragment.setRemoteEnabled(remoteEnabled)
    }

    private fun switchTab(tag: String) {
        currentFragmentTag = tag
        val ft = supportFragmentManager.beginTransaction()
        listOf(overviewFragment, tasksFragment, calendarFragment, settingsFragment, permissionsFragment)
            .forEach { ft.hide(it) }
        val target = when (tag) {
            TAG_OVERVIEW -> overviewFragment
            TAG_TASKS -> tasksFragment
            TAG_CALENDAR -> calendarFragment
            TAG_SETTINGS -> settingsFragment
            TAG_PERMISSIONS -> permissionsFragment
            else -> overviewFragment
        }
        ft.show(target).commitNow()
        currentSnapshot?.let { target.refresh(it) }
    }

    private fun refreshCurrentFragment() {
        val snapshot = currentSnapshot ?: return
        val target = when (currentFragmentTag) {
            TAG_OVERVIEW -> overviewFragment
            TAG_TASKS -> tasksFragment
            TAG_CALENDAR -> calendarFragment
            TAG_SETTINGS -> settingsFragment
            TAG_PERMISSIONS -> permissionsFragment
            else -> overviewFragment
        }
        target.refresh(snapshot)
    }

    // ===================== MQTT =====================
    private fun initMqtt() {
        mqttClient = MqttClient(BrokerUtils.normalizeBroker(device.broker), "ctl-" + device.deviceId, MemoryPersistence())
        val options = MqttConnectOptions().apply {
            userName = device.ctlUser
            password = device.ctlPass.toCharArray()
            // cleanSession=false：断线重连后 broker 保留会话与订阅，
            // 避免重连后收不到 PA/resp/ack/status（表现为一直配对中 / 拿不到快照）。
            isCleanSession = false
            connectionTimeout = 10
            // 远程开关关闭时会强制 close 客户端，因此开启期间允许自动重连；
            // 关闭时通过 setCallback(null)+disconnectForcibly+close 彻底停止，避免仍在通信。
            isAutomaticReconnect = true
            keepAliveInterval = 240
        }

        // 用 MqttCallbackExtended：在（重连）connectComplete 中重新订阅并视情况补发配对，
        // 解决「重连后订阅丢失导致永远收不到 PA/快照」的问题。
        // 注意：初始连接的订阅 + 发配对放在 connect() 之后直接执行（见下方 try 块），
        // 不依赖 connectComplete 是否在初始连接触发——部分 Paho 版本初始连接不回调 connectComplete，
        // 若只在此处处理，会出现“已连接但从未订阅/发配对、永远配对中”的回归。
        mqttClient?.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                MqttQuota.onConnect(this@DeviceControlActivity)
                runOnUiThread { setConnStatus("已连接", true) }
                // 每次连接/重连都重新订阅全部主题；订阅是幂等的。
                lifecycleScope.launch(Dispatchers.IO) { subscribeTopics() }
                if (device.sessionSecret.isBlank()) {
                    if (reconnect) {
                        // 重连场景：延迟一点再发配对，确保上方订阅已对 broker 生效
                        Log.d(TAG, "重连成功，延迟发起配对 deviceId=${device.deviceId}")
                        mainHandler.postDelayed({ lifecycleScope.launch { publishPair() } }, 500)
                    }
                    // 初始连接不在此发配对：connect() 之后的初始流程负责，避免重复发起。
                } else {
                    // connectComplete 跑在 Paho 后台回调线程，必须切回主线程再碰 UI，
                    // 否则 setConnStatus/startRefresh 触发「Animations may only be started on the main thread」。
                    // 与下方初始连接分支（runOnUiThread）保持一致。
                    runOnUiThread {
                        setConnStatus("已连接（已配对）", true)
                        startRefresh()
                    }
                    mainHandler.postDelayed({ sendQuery() }, 1_500L)
                }
            }

            override fun connectionLost(cause: Throwable?) {
                MqttQuota.onDisconnect(this@DeviceControlActivity)
                runOnUiThread { setConnStatus("连接断开", false) }
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                message?.payload?.let {
                    bumpQuota(0, 1)
                    handleIncoming(topic ?: "", String(it))
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        // 关键修复：connect / subscribe 全部移到后台线程，避免主线程阻塞导致黑屏 / ANR。
        // 此前 on 主线程同步 connect + subscribeWithResponse（无超时无限期等待 SUBACK），
        // 在 broker 未及时回 SUBACK 时窗口无法绘制（黑屏），并使 MainActivity 停止超时。
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                mqttClient?.connect(options)
                // 初始连接成功：直接订阅 + 配对（不依赖 connectComplete 是否在初始连接触发，
                // 规避部分 Paho 版本初始连接不回调 connectComplete 导致永远收不到 PA/快照）。
                Log.d(TAG, "初始连接成功 deviceId=${device.deviceId} ctlUser=${device.ctlUser} 主题前缀=${MqttPacket.TOPIC_PREFIX}/${device.deviceId}")
                subscribeTopics()
                if (device.sessionSecret.isBlank()) {
                    pairRetries = 0
                    publishPair()
                } else {
                    runOnUiThread { setConnStatus("已连接（已配对）", true) }
                    startRefresh()
                    mainHandler.postDelayed({ sendQuery() }, 1_500L)
                }
            } catch (e: MqttException) {
                e.printStackTrace()
                runOnUiThread {
                    setConnStatus("连接失败", false)
                    val reason = when (e.reasonCode) {
                        MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt(),
                        MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() ->
                            "用户名或密码错误，请检查 CTL 凭证（${device.ctlUser}）与 EMQX 中是否一致"
                        else -> "MQTT 连接失败：${e.message}（code=${e.reasonCode}）"
                    }
                    toastOnce("conn_fail", reason)
                }
            }
        }
    }

    private suspend fun subscribeTopics() {
        val pushTopic = "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/push"
        val topics = listOf(
            "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/status",
            "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/ack",
            "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/pair/accept",
            "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/resp",
            pushTopic
        )
        var anyDenied = false
        var pushDenied = false
        topics.forEach { topic ->
            try {
                // 用 subscribeWithResponse + 协程超时替代无限期阻塞：
                // broker 未及时回 SUBACK 时最多等 5s 抛 TimeoutCancellationException，避免卡死
                val subAck = withTimeout(5_000) { mqttClient?.subscribeWithResponse(topic, 1) }
                val granted = subAck?.grantedQos?.firstOrNull() ?: 1
                if (granted == 128) {
                    anyDenied = true
                    if (topic == pushTopic) pushDenied = true
                    Log.w(TAG, "订阅被 broker 拒绝(ACL): $topic 账户=${device.ctlUser}")
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "订阅超时(5s): $topic")
                anyDenied = true
                if (topic == pushTopic) pushDenied = true
            } catch (e: MqttException) {
                e.printStackTrace()
                anyDenied = true
                if (topic == pushTopic) pushDenied = true
            }
        }
        if (pushDenied) {
            pushAvailable = false
            Log.w(TAG, "增量推送主题订阅失败，已回退到 15s 轮询全量")
        }
        if (anyDenied) {
            runOnUiThread {
                setConnStatus("已连接（订阅被拒）", true)
                val msg = if (pushDenied) {
                    "增量推送主题 ${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/push 被 broker 拒绝（EMQX ACL 未授权）。已自动回退到每 15 秒轮询全量，如需开启增量推送，请给账户 ${device.ctlUser} 增加该主题的订阅权限。"
                } else {
                    "部分主题订阅被 broker 拒绝（多为 EMQX ACL 未授权 ${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/#）。请检查账户 ${device.ctlUser} 的 ACL 是否允许该主题。"
                }
                toastOnce("sub_denied", msg)
            }
        } else {
            pushAvailable = true
        }
    }

    /** 控制端「MQTT 连接」开关：开启则连接 MQTT，关闭后不再发起任何 MQTT 请求（含快照） */
    private fun setRemoteEnabled(on: Boolean) {
        remoteEnabled = on
        prefs.edit().putBoolean("remote_enabled_${device.deviceId}", on).apply()
        if (on) {
            if (mqttClient?.isConnected == true) return
            // 如果之前有未彻底关闭的客户端，先清理，避免双连接或自动重连仍在跑
            disconnectMqtt()
            setConnStatus("连接中…", false)
            currentSnapshot = null
            overviewFragment.setSnapshotHint(SnapshotHint.WAITING)
            initMqtt()
        } else {
            disconnectMqtt()
            currentSnapshot = null
            overviewFragment.setSnapshotHint(SnapshotHint.DISABLED)
        }
    }

    private fun disconnectMqtt() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(queryTimeoutRunnable)
        mainHandler.removeCallbacks(PAIR_TIMEOUT_RUNNABLE)
        queryPendingRid = null
        queryRetryCount = 0
        val client = mqttClient ?: return
        mqttClient = null
        MqttQuota.onDisconnect(this)
        try { client.setCallback(null) } catch (_: Exception) { }
        try { client.disconnectForcibly(0, 0, false) } catch (_: Exception) { }
        try { client.close(true) } catch (_: Exception) { }
        runOnUiThread { setConnStatus("MQTT 已关闭", false) }
    }

    /** 累加 MQTT 消息计数并刷新额度 UI */
    private fun bumpQuota(published: Int, received: Int) {
        MqttQuota.add(this, published, received)
        refreshQuotaUi()
    }

    private fun refreshQuotaUi() {
        val stats = MqttQuota.get(this)
        runOnUiThread { overviewFragment.showQuota(stats) }
    }

    /** B3：Toast 去重 —— 相同 key 在 dedupeWindowMs 内只弹一次，重复事件只更新状态 UI 不再轰炸 */
    private fun toastOnce(key: String, msg: String, dedupeWindowMs: Long = 4000) {
        val now = System.currentTimeMillis()
        if (key == lastToastKey && now - lastToastAt < dedupeWindowMs) return
        lastToastKey = key
        lastToastAt = now
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private suspend fun publishPair() {
        if (mqttClient?.isConnected != true) {
            Log.w(TAG, "publishPair 跳过：MQTT 未连接")
            return
        }
        mainHandler.removeCallbacks(PAIR_TIMEOUT_RUNNABLE)
        // 防御：每次发起配对前都确保已订阅 pair/accept（若此前订阅/重连丢订阅，先补上再发，
        // 避免“发了 P 但还没订阅 PA 主题”导致配对回执 PA 被 broker 丢弃、永远配对中）。
        subscribeTopics()
        val ts = System.currentTimeMillis()
        val rid = UUID.randomUUID().toString()
        val packet = MqttPacket(c = MqttPacket.CMD_PAIR, f = "", v = PacketValue.StringValue(device.pairingToken), rid = rid, ts = ts, sign = "")
        Log.d(TAG, "发起配对 P -> ${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/pair token长度=${device.pairingToken.length} rid=$rid")
        try {
            mqttClient?.publish("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/pair",
                MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 })
            bumpQuota(1, 0)
            runOnUiThread { setConnStatus("已连接（配对中…）", true) }
            mainHandler.postDelayed(PAIR_TIMEOUT_RUNNABLE, PAIR_TIMEOUT_MS)
        } catch (e: MqttException) {
            e.printStackTrace()
            runOnUiThread { setConnStatus("已连接（配对发送失败）", true) }
        }
    }

    private fun onPairTimeout() {
        if (device.sessionSecret.isNotBlank()) return
        if (pairRetries < PAIR_MAX_RETRIES) {
            pairRetries++
            runOnUiThread { setConnStatus("已连接（配对重试 $pairRetries/$PAIR_MAX_RETRIES）", true) }
            lifecycleScope.launch { publishPair() }
        } else {
            runOnUiThread {
                setConnStatus("已连接（未配对）", true)
                Toast.makeText(this, "配对失败：被控端未确认。请确认被控端已点「生成绑定二维码」，再重新扫码", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleIncoming(topic: String, payload: String) {
        when {
            topic.endsWith("/status") -> {
                val raw = payload.trim()
                val online = raw == "online"
                val text = when (raw) {
                    "online" -> "已连接"
                    "unbound" -> "已解绑"
                    "force_unbound" -> "已被强制解绑"
                    else -> "设备离线"
                }
                Log.d(TAG, "收到被控端状态消息 raw=$raw -> 显示「$text」(deviceId=${device.deviceId})")
                runOnUiThread { setConnStatus(text, online) }
                // 收到被控端 online 且当前没有待返回的快照时，主动请求一次快照
                if (online && device.sessionSecret.isNotBlank() && queryPendingRid == null) {
                    sendQuery()
                }
            }
            topic.endsWith("/pair/accept") -> onPairAccepted()
            topic.endsWith("/resp") -> onSnapshot(payload)
            topic.endsWith("/push") -> onPush(payload)
            topic.endsWith("/ack") -> handleAck(payload)
        }
    }

    private fun onPairAccepted() {
        mainHandler.removeCallbacks(PAIR_TIMEOUT_RUNNABLE)
        Log.d(TAG, "收到配对确认 PA，开始派生会话密钥 deviceId=${device.deviceId}")
        val session = Hkdf.deriveHex(device.pairingToken, device.deviceId, MqttPacket.PAIRING_INFO, MqttPacket.SESSION_KEY_LEN)
        device = device.copy(sessionSecret = session, pairingToken = "", bound = true)
        lifecycleScope.launch(Dispatchers.IO) { db.deviceDao().update(device) }
        runOnUiThread {
            // 方案2保险：配对成功即强制开启远程开关（避免升级安装残留的 false 导致 UI 显示关）
            prefs.edit().putBoolean("remote_enabled_${device.deviceId}", true).apply()
            remoteEnabled = true
            overviewFragment.setRemoteEnabled(true)
            setConnStatus("已连接（已配对）", true)
            Toast.makeText(this, "已与控制端完成配对", Toast.LENGTH_SHORT).show()
        }
        sendQuery()
        startRefresh()
    }

    private fun handleAck(payload: String) {
        Log.d(TAG, "收到 ack: $payload")
        try {
            val packet = gson.fromJson(payload, MqttPacket::class.java)
            val result = packet?.v?.toStringValue() ?: "-"
            runOnUiThread {
                when (result) {
                    "NO_PAIRING" -> {
                        // 被控端尚未生成配对码：停止重试循环，避免一直卡在“配对中”
                        mainHandler.removeCallbacks(PAIR_TIMEOUT_RUNNABLE)
                        setConnStatus("已连接（未配对）", true)
                        Toast.makeText(this, "被控端：尚未生成配对码。请在被控端「远程控制」页点「生成绑定二维码」，再重新扫码", Toast.LENGTH_LONG).show()
                    }
                    "TOKEN_MISMATCH" -> {
                        // 配对码不一致/已过期：停止重试，提示重新生成二维码
                        mainHandler.removeCallbacks(PAIR_TIMEOUT_RUNNABLE)
                        setConnStatus("已连接（未配对）", true)
                        Toast.makeText(this, "被控端：配对码不一致或已过期。请重新生成二维码后重新扫码", Toast.LENGTH_LONG).show()
                    }
                    "UNBOUND" -> Toast.makeText(this, "被控端：当前未绑定，请先用二维码完成配对", Toast.LENGTH_SHORT).show()
                    "SIGN_FAIL" -> Toast.makeText(this, "被控端：指令签名校验失败", Toast.LENGTH_SHORT).show()
                    "SUCCESS" -> { sendQuery() }
                    "TASK_OK" -> { Toast.makeText(this, "任务已更新", Toast.LENGTH_SHORT).show(); sendQuery() }
                    "TASK_FAIL" -> Toast.makeText(this, "任务操作失败：${result.removePrefix("TASK_FAIL:")}", Toast.LENGTH_LONG).show()
                    "SNAPSHOT_FAIL" -> Toast.makeText(this, "快照生成失败", Toast.LENGTH_SHORT).show()
                    "NEED_MANUAL" -> Toast.makeText(this, "该设置需在系统/被控端手动开启（如无障碍、截屏权限）", Toast.LENGTH_LONG).show()
                    "SERVICE_UNAVAILABLE" -> Toast.makeText(this, "被控端服务未就绪（通知监听未运行），动作未执行", Toast.LENGTH_LONG).show()
                    "UNKNOWN_ACTION" -> Toast.makeText(this, "未知动作：$result", Toast.LENGTH_SHORT).show()
                    "DUP_OR_STALE" -> Toast.makeText(this, "被控端：请求已过期或重复（请检查两端时间是否一致）", Toast.LENGTH_LONG).show()
                    else -> Toast.makeText(this, "设备回执：$result", Toast.LENGTH_SHORT).show()
                }
                addRecentCommand(lastCommandLabel, ackFriendly(result))
                // D2：收到回执，恢复快捷按钮并弹 Snackbar 提示结果（仅一次性动作）
                overviewFragment.setActionsBusy(false)
                mainHandler.removeCallbacks(actionBusyResetRunnable)
                if (lastCommandLabel in setOf("打卡", "执行任务", "终止任务", "考勤记录", "截屏")) {
                    showActionSnackbar(result)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** B5：把回执结果转成简短展示文案（用于「最近指令」列表） */
    private fun ackFriendly(result: String): String = when (result) {
        "NO_PAIRING" -> "尚未生成配对码"
        "TOKEN_MISMATCH" -> "配对码不一致或已过期"
        "UNBOUND" -> "当前未绑定"
        "SIGN_FAIL" -> "签名校验失败"
        "SUCCESS" -> "成功"
        "TASK_OK" -> "任务已更新"
        "TASK_FAIL" -> "任务失败：${result.removePrefix("TASK_FAIL:")}"
        "SNAPSHOT_FAIL" -> "快照生成失败"
        "NEED_MANUAL" -> "需手动开启"
        "SERVICE_UNAVAILABLE" -> "服务未就绪"
        "UNKNOWN_ACTION" -> "未知动作"
        "DUP_OR_STALE" -> "请求过期或重复"
        else -> "回执：$result"
    }

    /** B5：转发到总览页「最近指令」列表（fragment 自身维护定长队列与渲染） */
    private fun addRecentCommand(label: String, result: String) {
        overviewFragment.addRecentCommand(label, result)
    }

    // ===================== 查询快照 =====================
    fun sendQuery() {
        if (!remoteEnabled) return
        if (device.sessionSecret.isBlank()) {
            // 尚未配对：把“刷新”当作重新发起配对，自愈（被控端令牌在 TTL 内可重复扫码重试）。
            // 这样「刷新实时数据」按钮与周期刷新都会持续尝试配对，避免停在“配对中”卡死。
            Log.d(TAG, "sendQuery：尚未配对，自动重新发起配对")
            if (mqttClient?.isConnected == true) lifecycleScope.launch { publishPair() }
            return
        }
        if (mqttClient?.isConnected != true) return
        if (queryPendingRid != null) return // 已有待返回查询，避免并发
        val ts = System.currentTimeMillis()
        val rid = UUID.randomUUID().toString()
        queryPendingRid = rid
        lastQuerySentTs = ts
        val sign = MqttSigner.sign(device.sessionSecret, device.deviceId, ts, rid, "snapshot", "", "", MqttPacket.CMD_QUERY)
        val packet = MqttPacket(c = MqttPacket.CMD_QUERY, f = "snapshot", v = null, rid = rid, ts = ts, sign = sign)
        // 发布移到 IO 协程，避免 publish(QoS1 同步等待 PUBACK) 阻塞主线程导致 ANR；
        // 末尾的配额/UI/超时计时仍在主线程执行（sendQuery 由 mainHandler 在主线程调用）。
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                mqttClient?.publish("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/cmd",
                    MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 })
                bumpQuota(1, 0)
                Log.d(TAG, "sendQuery 发布CMD_QUERY rid=$rid deviceId=${device.deviceId}")
                runOnUiThread {
                    if (currentSnapshot == null) overviewFragment.setSnapshotHint(SnapshotHint.WAITING)
                }
                mainHandler.postDelayed(queryTimeoutRunnable, QUERY_TIMEOUT_MS)
            } catch (e: MqttException) {
                e.printStackTrace()
                // 发送失败也要清空挂起 rid，否则会卡死后续查询（与 onQueryTimeout 同理）
                queryPendingRid = null
                Log.d(TAG, "sendQuery 发布失败: ${e.message}")
                runOnUiThread { toastOnce("query_send_fail", "查询发送失败：${e.message}") }
            }
        }
    }

    private fun onQueryTimeout() {
        if (queryPendingRid == null) return
        Log.d(TAG, "onQueryTimeout rid=$queryPendingRid retry=$queryRetryCount")
        if (queryRetryCount < QUERY_MAX_RETRIES) {
            queryRetryCount++
            // 重试前必须清空上一次挂起的 rid，否则 sendQuery 内的并发守护会直接 return，
            // 导致后续所有查询（含本重试、周期刷新、手动刷新）永久失效、快照再也回不来。
            queryPendingRid = null
            // 重试过程不再弹 Toast，减少干扰；只在总览页显示等待提示
            overviewFragment.setSnapshotHint(SnapshotHint.WAITING)
            sendQuery()
        } else {
            queryPendingRid = null
            queryRetryCount = 0
            overviewFragment.setSnapshotHint(SnapshotHint.FAILED)
            runOnUiThread { toastOnce("no_snapshot", "被控端未返回快照，请确认被控端在线且已配对") }
        }
    }

    private fun onSnapshot(payload: String) {
        try {
            val packet = gson.fromJson(payload, MqttPacket::class.java) ?: return
            Log.d(TAG, "onSnapshot 收到 resp rid=${packet.rid} pending=$queryPendingRid")
            if (packet.rid == queryPendingRid) {
                queryPendingRid = null
                queryRetryCount = 0
                mainHandler.removeCallbacks(queryTimeoutRunnable)
            }
            val ok = verifyResp(packet)
            Log.d(TAG, "onSnapshot verifyResp=$ok rid=${packet.rid}")
            if (!ok) {
                runOnUiThread { toastOnce("snapshot_sign_fail", "快照验签失败，已忽略") }
                return
            }
            val json = packet.v?.toStringValue() ?: return
            val snapshot = parseSnapshot(json)
            currentSnapshot = snapshot
            snapshotJson = JsonParser.parseString(json).asJsonObject
            persistSnapshot(json)
            Log.d(TAG, "onSnapshot 解析成功，刷新UI rid=${packet.rid}")
            // 计算 RTT（只读/写属性，避免放进 UI 块里造成线程竞争）
            val rtt = if (lastQuerySentTs > 0) {
                val r = System.currentTimeMillis() - lastQuerySentTs
                lastQuerySentTs = 0
                r
            } else -1
            // messageArrived 跑在 Paho 后台线程，UI 必须在主线程；统一在此包裹
            runOnUiThread {
                overviewFragment.setSnapshotHint(SnapshotHint.NONE)
                overviewFragment.setStaleBanner(false)
                if (rtt >= 0) overviewFragment.setConnQuality(rtt)
                refreshCurrentFragment()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread { Toast.makeText(this, "快照解析失败：${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    /**
     * 处理被控端增量推送（dt/{id}/push）：验签后仅把变化的区块合并进本地缓存（按 key 覆盖），
     * 持久化并刷新 UI。未配对/验签失败忽略。若本地尚无全量基线，则先触发一次全量查询。
     */
    private fun onPush(payload: String) {
        try {
            val packet = gson.fromJson(payload, MqttPacket::class.java) ?: return
            if (!verifyPush(packet)) {
                runOnUiThread { toastOnce("push_sign_fail", "增量推送验签失败，已忽略") }
                return
            }
            val json = packet.v?.toStringValue() ?: return
            val delta = JsonParser.parseString(json).asJsonObject
            val base = snapshotJson ?: JsonObject().also {
                snapshotJson = it
                sendQuery() // 无基线则先拉全量
            }
            delta.entrySet().forEach { (k, v) -> base.add(k, v) }
            val merged = base.toString()
            currentSnapshot = parseSnapshot(merged)
            persistSnapshot(merged)
            // messageArrived 跑在 Paho 后台线程，setSnapshotHint 直接碰 binding，必须回主线程
            runOnUiThread {
                overviewFragment.setSnapshotHint(SnapshotHint.NONE)
                refreshCurrentFragment()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun verifyPush(packet: MqttPacket): Boolean {
        val session = device.sessionSecret
        if (session.isBlank()) return false
        val json = packet.v?.toStringValue() ?: return false
        val expected = MqttSigner.sign(session, device.deviceId, packet.ts, packet.rid, packet.f, "s", json, MqttPacket.CMD_PUSH)
        return expected == packet.sign
    }

    /** 持久化快照 JSON 到本地缓存（deviceId 维度），控制端重启/离线时仍可展示 */
    private fun persistSnapshot(json: String) {
        prefs.edit().putString("snapshot_cache_${device.deviceId}", json).apply()
    }

    private fun verifyResp(packet: MqttPacket): Boolean {
        val session = device.sessionSecret
        if (session.isBlank()) return false
        val json = packet.v?.toStringValue() ?: return false
        val expected = MqttSigner.sign(session, device.deviceId, packet.ts, packet.rid, packet.f, "s", json, MqttPacket.CMD_RESP)
        return expected == packet.sign
    }

    private fun parseSnapshot(json: String): DeviceSnapshot {
        val root = JsonParser.parseString(json).asJsonObject
        fun mapOf(obj: com.google.gson.JsonObject?): Map<String, String> {
            val m = mutableMapOf<String, String>()
            obj?.entrySet()?.forEach { (k, v) ->
                // 跳过数组/对象类字段（如 runtime.batterySeries），避免 asString 抛异常
                if (v.isJsonPrimitive) m[k] = v.asString
            }
            return m
        }
        val settings = mutableListOf<SettingItem>()
        root.getAsJsonArray("settings")?.forEach {
            val o = it.asJsonObject
            val type = o.get("type").asString
            settings.add(SettingItem(
                key = o.get("key").asString,
                label = o.get("label").asString,
                type = type,
                value = if (type == "bool") o.get("value").asBoolean else o.get("value").asInt,
                writable = true,
                min = o.get("min")?.takeIf { it.isJsonPrimitive }?.asInt,
                max = o.get("max")?.takeIf { it.isJsonPrimitive }?.asInt,
                step = o.get("step")?.takeIf { it.isJsonPrimitive }?.asInt
            ))
        }
        val statuses = mutableListOf<StatusItem>()
        root.getAsJsonArray("statuses")?.forEach {
            val o = it.asJsonObject
            statuses.add(StatusItem(o.get("key").asString, o.get("label").asString, o.get("value").asString))
        }
        val tasks = mutableListOf<TaskItem>()
        root.getAsJsonArray("tasks")?.forEach {
            val o = it.asJsonObject
            tasks.add(TaskItem(
                id = o.get("id").asInt,
                time = o.get("time").asString,
                actualTime = o.get("actualTime")?.asString,
                status = o.get("status")?.asString ?: "pending",
                statusLabel = o.get("statusLabel")?.asString ?: "待执行"
            ))
        }
        val calendarObj = root.getAsJsonObject("calendar")
        val days = mutableListOf<CalendarDay>()
        calendarObj?.getAsJsonArray("days")?.forEach {
            val o = it.asJsonObject
            days.add(CalendarDay(
                date = o.get("date").asString,
                weekday = o.get("weekday").asInt,
                status = o.get("status").asString,
                label = o.get("label").asString
            ))
        }
        val calendar = CalendarSnapshot(
            punched = calendarObj?.get("punched")?.asString ?: "0",
            scheduled = calendarObj?.get("scheduled")?.asString ?: "0",
            missed = calendarObj?.get("missed")?.asString ?: "0",
            recentPunch = calendarObj?.get("recentPunch")?.asString ?: "—",
            today = calendarObj?.get("today")?.asString ?: "—",
            days = days
        )
        // B5：解析电池采样序列（runtime.batterySeries 为数组，已在上文 mapOf 中被安全跳过）
        val batterySeries = mutableListOf<BatteryPoint>()
        root.getAsJsonObject("runtime")?.getAsJsonArray("batterySeries")?.forEach {
            val o = it.asJsonObject
            val ts = o.get("ts")?.takeIf { e -> e.isJsonPrimitive }?.asLong ?: 0L
            val level = o.get("level")?.takeIf { e -> e.isJsonPrimitive }?.asInt ?: -1
            if (level >= 0) batterySeries.add(BatteryPoint(ts, level))
        }
        val history = mutableListOf<HistoryItem>()
        root.getAsJsonArray("history")?.forEach {
            val o = it.asJsonObject
            history.add(HistoryItem(o.get("time").asString, o.get("result").asString))
        }
        return DeviceSnapshot(
            device = mapOf(root.getAsJsonObject("device")),
            runtime = mapOf(root.getAsJsonObject("runtime")),
            calendar = calendar,
            settings = settings,
            statuses = statuses,
            tasks = tasks,
            history = history,
            batterySeries = batterySeries,
            syncedAt = System.currentTimeMillis()
        )
    }

    // ===================== 下发指令 =====================
    private fun sendUpdate(field: String, value: PacketValue) {
        if (!remoteEnabled) {
            Toast.makeText(this, "MQTT 连接已关闭，无法修改设置", Toast.LENGTH_SHORT).show(); return
        }
        if (device.sessionSecret.isBlank()) {
            Toast.makeText(this, "尚未完成配对，无法下发指令", Toast.LENGTH_SHORT).show(); return
        }
        lastCommandLabel = "修改设置"
        val ts = System.currentTimeMillis()
        val rid = UUID.randomUUID().toString()
        val (type, vStr) = when (value) {
            is PacketValue.BooleanValue -> "b" to value.b.toString()
            is PacketValue.IntValue -> "i" to value.i.toString()
            is PacketValue.StringValue -> "s" to value.s
        }
        val sign = MqttSigner.sign(device.sessionSecret, device.deviceId, ts, rid, field, type, vStr, "U")
        val packet = MqttPacket("U", field, value, rid, ts, sign)
        try {
            mqttClient?.publish("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/cmd",
                MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 })
            bumpQuota(1, 0)
        } catch (e: MqttException) {
            e.printStackTrace()
            Toast.makeText(this, "指令发送失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendTask(action: String, time: String, oldTime: String? = null) {
        if (!remoteEnabled) {
            Toast.makeText(this, "MQTT 连接已关闭，无法编辑任务", Toast.LENGTH_SHORT).show(); return
        }
        if (device.sessionSecret.isBlank()) {
            Toast.makeText(this, "尚未完成配对，无法下发指令", Toast.LENGTH_SHORT).show(); return
        }
        lastCommandLabel = if (action == "add") "新增任务" else "删除任务"
        val obj = com.google.gson.JsonObject()
        obj.addProperty("action", action)
        obj.addProperty("time", time)
        if (oldTime != null) obj.addProperty("oldTime", oldTime)
        val json = obj.toString()
        val ts = System.currentTimeMillis()
        val rid = UUID.randomUUID().toString()
        val sign = MqttSigner.sign(device.sessionSecret, device.deviceId, ts, rid, "task", "s", json, MqttPacket.CMD_TASK)
        val packet = MqttPacket(c = MqttPacket.CMD_TASK, f = "task", v = PacketValue.StringValue(json), rid = rid, ts = ts, sign = sign)
        try {
            mqttClient?.publish("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/cmd",
                MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 })
            bumpQuota(1, 0)
        } catch (e: MqttException) {
            e.printStackTrace()
            Toast.makeText(this, "指令发送失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ===================== 一次性动作指令 =====================
    private fun sendAction(action: String) {
        if (!remoteEnabled) {
            Toast.makeText(this, "MQTT 连接已关闭，无法下发动作", Toast.LENGTH_SHORT).show(); return
        }
        if (device.sessionSecret.isBlank()) {
            Toast.makeText(this, "尚未完成配对，无法下发指令", Toast.LENGTH_SHORT).show(); return
        }
        if (mqttClient?.isConnected != true) {
            Toast.makeText(this, "MQTT 未连接，无法下发指令", Toast.LENGTH_SHORT).show(); return
        }
        // D2：下发中置灰快捷按钮防重复点击；10s 兜底自动恢复（避免 ACK 丢失时永久置灰）
        overviewFragment.setActionsBusy(true)
        mainHandler.removeCallbacks(actionBusyResetRunnable)
        mainHandler.postDelayed(actionBusyResetRunnable, 10_000L)
        val ts = System.currentTimeMillis()
        val rid = UUID.randomUUID().toString()
        val sign = MqttSigner.sign(device.sessionSecret, device.deviceId, ts, rid, action, "", "", MqttPacket.CMD_ACTION)
        val packet = MqttPacket(MqttPacket.CMD_ACTION, action, null, rid, ts, sign)
        try {
            mqttClient?.publish("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/cmd",
                MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 })
            bumpQuota(1, 0)
            val label = when (action) {
                MqttPacket.ACTION_PUNCH -> "打卡"
                MqttPacket.ACTION_START -> "执行任务"
                MqttPacket.ACTION_STOP -> "终止任务"
                MqttPacket.ACTION_ATTENDANCE -> "考勤记录"
                MqttPacket.ACTION_SCREENSHOT -> "截屏"
                else -> action
            }
            lastCommandLabel = label
            Toast.makeText(this, "已发送动作：$label", Toast.LENGTH_SHORT).show()
        } catch (e: MqttException) {
            e.printStackTrace()
            Toast.makeText(this, "动作指令发送失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ===================== 周期刷新 =====================
    private fun doPeriodicRefresh() {
        // 事件驱动 + 增量推送：
        // 1. 尚未拿到过任何快照（无基线）时，周期拉全量做引导；
        // 2. 若增量推送主题被 broker ACL 拒绝，则回退到原来的每 15s 轮询全量，保证界面仍会自动刷新；
        // 3. 正常场景（pushAvailable=true 且已有基线）停止轮询，仅由 dt/{id}/push 增量刷新，避免一直交互全量数据。
        if (remoteEnabled && mqttClient?.isConnected == true && (currentSnapshot == null || !pushAvailable)) {
            sendQuery()
        }
        mainHandler.postDelayed(refreshRunnable, REFRESH_MS)
    }
    private fun startRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, REFRESH_MS)
    }

    // ===================== 解绑 =====================
    private fun confirmUnbind() {
        AlertDialog.Builder(this)
            .setTitle("解绑设备")
            .setMessage("确定从控制端移除该设备？被控端将收到解绑通知并清除绑定。本机 MQTT 配置不受影响。")
            .setPositiveButton("解绑") { _, _ -> doUnbind() }
            .setNegativeButton("取消", null).show()
    }

    private fun doUnbind() {
        val packet = MqttPacket(c = MqttPacket.CMD_UNBOUND, f = "", v = PacketValue.StringValue(""), rid = UUID.randomUUID().toString(), ts = System.currentTimeMillis(), sign = "")
        val client = mqttClient
        // 先在被控端收到解绑通知并清除其绑定态，再删除本机记录并退出。
        // 发布放到后台线程并 await 完成，确保 UB 命令真正送达 broker 后才 finish()
        // （finish 会触发 onDestroy 断开 MQTT 客户端，若先断开则 UB 可能丢失，导致被控端仍显示“已绑定”）。
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "控制端发起解绑 UB -> dt/${device.deviceId}/pair")
                client?.publish("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/pair",
                    MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 })
                bumpQuota(1, 0)
            } catch (_: MqttException) {
            }
            db.deviceDao().deleteById(device.deviceId)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@DeviceControlActivity, "已解绑", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    fun setConnStatus(text: String, online: Boolean) {
        overviewFragment.setConnStatusText(text, online)
        // B4：离线横幅（连接中过渡态不显示）+ 断连禁用控制按钮
        val showBanner = remoteEnabled && !online && !text.contains("连接中")
        binding.offlineBanner.visibility = if (showBanner) View.VISIBLE else View.GONE
        overviewFragment.setActionsEnabled(online)
    }

    fun setConnStatusDot(dot: View, tv: android.widget.TextView) {
        val online = tv.text.toString().contains("已连接") && !tv.text.toString().contains("断开")
        dot.setBackgroundResource(if (online) R.drawable.bg_dot_online else R.drawable.bg_dot_offline)
    }

    /** D2：动作回执 Snackbar（替代 Toast，置于页面底部更醒目） */
    private fun showActionSnackbar(result: String) {
        val friendly = ackFriendly(result)
        Snackbar.make(binding.root, "指令回执：$friendly", Snackbar.LENGTH_SHORT).show()
    }

    /** D5：重新发起配对（控制端「重新配对」入口）—— 已连接则重发 P，否则重连后再由流程发起 */
    fun retryPair() {
        if (mqttClient?.isConnected == true) {
            lifecycleScope.launch { publishPair() }
        } else {
            setConnStatus("连接中…", false)
            initMqtt()
        }
    }

    /** D6：连接失败时点击状态文案重试 —— 断开旧连接后重连 */
    fun retryConnection() {
        disconnectMqtt()
        setConnStatus("连接中…", false)
        initMqtt()
    }


    override fun onResume() {
        super.onResume()
        // 确保 MQTT 开关状态与持久化值一致（解决从设备列表返回 / 首次进入时 UI 开关显示默认关的问题）。
        // 注意：onCreate 里 initFragments 的 commitNow() 在 onCreate 阶段不会同步创建 fragment 视图，
        // 此时 OverviewFragment._binding 为 null，setRemoteEnabled 会提前 return，开关从未被初始化。
        // 因此在 onResume（视图已就绪）时无条件重新应用持久化状态，而不是仅在 saved != remoteEnabled 时同步。
        val saved = prefs.getBoolean("remote_enabled_${device.deviceId}", true)
        if (saved != remoteEnabled) {
            remoteEnabled = saved
            if (saved && mqttClient == null) {
                setConnStatus("连接中…", false)
                initMqtt()
            } else if (!saved) {
                setConnStatus("MQTT 已关闭", false)
                overviewFragment.setSnapshotHint(SnapshotHint.DISABLED)
            }
        }
        overviewFragment.setRemoteEnabled(remoteEnabled)
    }

    override fun onStop() {
        super.onStop()
        // B2：退后台后静默断连 —— 取消全部计时（15s 刷新/心跳/查询超时/配对超时），关闭 MQTT 长连接，
        // 实现「后台零耗电/零连接/零计时」。client 已 close 不会产生后台自动重连。
        disconnectMqtt()
    }

    override fun onStart() {
        super.onStart()
        // B2：从后台回到前台时，若远程开关开启且当前没有活跃连接，则重连并恢复刷新
        if (remoteEnabled && mqttClient == null) {
            setConnStatus("连接中…", false)
            initMqtt()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(PAIR_TIMEOUT_RUNNABLE)
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(queryTimeoutRunnable)
        try { mqttClient?.disconnect(); mqttClient?.close() } catch (_: Exception) {}
    }
}
