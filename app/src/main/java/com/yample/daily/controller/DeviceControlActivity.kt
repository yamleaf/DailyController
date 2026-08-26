package com.yample.daily.controller

import android.os.Bundle
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.util.Log
import androidx.transition.TransitionManager
import android.graphics.Outline
import android.view.ViewOutlineProvider
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialFadeThrough
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.yample.daily.controller.databinding.ActivityDeviceControlBinding
import com.yample.daily.controller.databinding.BottomSheetAddDeviceBinding
import com.yample.mqttprotocol.BindingPayload
import com.yample.mqttprotocol.BrokerUtils
import com.yample.mqttprotocol.Hkdf
import com.yample.mqttprotocol.MqttPacket
import com.yample.mqttprotocol.MqttQuota
import com.yample.mqttprotocol.MqttSigner
import com.yample.mqttprotocol.PacketValue
import com.yample.mqttprotocol.PacketValueAdapter
import com.yample.mqttprotocol.Protocol
import com.yample.mqttprotocol.SecretBox
import com.yample.mqttprotocol.dialog.UnifiedDialogKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.text.SimpleDateFormat
import java.util.ArrayDeque
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

    /** 返回时统一应用反向转场动画（与进入时对称） */
    override fun finish() {
        super.finish()
    }

    private var pairRetries = 0
    private val PAIR_MAX_RETRIES = 3
    private val PAIR_TIMEOUT_MS = 12_000L
    private val PAIR_TIMEOUT_RUNNABLE = Runnable { onPairTimeout() }
    /** 最近一次配对请求 rid，用于校验 PA 签名绑定 */
    private var pendingPairRid: String? = null
    /** 入站 rid 去重（resp/push/alert/ack/status），防公共 Broker 重放 */
    private val recentInboundRids = ArrayDeque<String>(64)
    // 首次进入详情页的探活：单条 snapshot 查询超时 3s；首次探活额外多一次重试（共 3×3=9s），
    // 避免公共 broker/被控端冷启动时误判离线、随后 resp 迟到又报「恢复响应」。
    private val QUERY_TIMEOUT_MS = 3_000L
    private val queryTimeoutRunnable = Runnable { onQueryTimeout() }
    /** D2：动作下发兜底恢复（ACK 丢失时 10s 后自动解灰） */
    private val actionBusyResetRunnable = Runnable {
        // 兜底恢复时同步清掉挂起 rid，否则迟到的 ACK 仍会被当成「当前动作」误弹 Snackbar
        pendingActionRid = null
        overviewFragment.setActionsBusy(false)
    }
    private var queryPendingRid: String? = null
    private var queryRetryCount = 0
    /** 普通查询允许的重试次数：1 → 共 2 次尝试 */
    private val QUERY_MAX_RETRIES = 1
    /** 首次进入探活允许的重试次数：2 → 共 3 次尝试，全部超时才判定离线（抗慢链路误判） */
    private val QUERY_PROBE_MAX_RETRIES = 2

    /** 查询节流：窗口内重复触发合并为一次；force=true 豁免（仅探活语义使用） */
    private val QUERY_MIN_INTERVAL_MS = 5_000L
    private var lastQueryAttemptTs = 0L

    /** 被控端最后活跃时间戳（收到其任意消息时更新），用于超时提示区分「真离线」vs「临时抖动」 */
    private var lastActivityMs = 0L

    // ===================== AQ 告警回放 =====================
    /** 首次进入详情页拉一次被控端近期告警回放：补齐本端离线期间漏收的告警（rid=aid 幂等去重） */
    private var alertQueryPendingRid: String? = null
    /** 回放查询超时（静默失败：不打扰用户，下次进入页面再试） */
    private val ALERT_QUERY_TIMEOUT_MS = 4_000L
    private val alertQueryTimeoutRunnable = Runnable { alertQueryPendingRid = null }

    // ===================== 首次进入探活 =====================
    /**
     * 控制端「首次进入设备详情页」且设备已配对/在线时才主动拉快照：
     * 发一条 snapshot 查询作为 5s 探活，被控端在 5s 内回响应即证明在线且带回快照；
     * 超时则重试一次，两次都失败 → 判定离线（色灯变红）。
     * 该标志位确保整个 Activity 生命周期只主动拉一次全量，其余刷新只来自手动刷新/被控端推送。
     */
    private var firstEntryProbeStarted = false
    /** 当前是否处于首次探活窗口（决定查询超时时是否升级为「离线」而非仅提示失败） */
    private var firstEntryProbeActive = false
    /** 是否已经判定被控端离线（防止重复刷屏/重复动作） */
    private var recentlyMarkedOffline = false

    private var currentSnapshot: DeviceSnapshot? = null
    /** 原始快照 JSON（按区块合并增量推送用），持久化到本地缓存 */
    private var snapshotJson: JsonObject? = null
    private var currentFragmentTag = TAG_OVERVIEW

    private val prefs by lazy { getSharedPreferences("remote_ctrl", MODE_PRIVATE) }
    private var remoteEnabled = true
    /** 解绑原因：true=被控端强制解绑，false=控制端主动解绑/从未绑定 */
    private var forceUnbound = false
    /**
     * 设备在线态（用于离线转跳守卫）：null=未知（建基线，不记录），
     * true→false 的真实转跳才写入「上次离线时间」，避免 retained 离线消息重复到达时反复刷新时间戳。
     */
    private var deviceWasOnline: Boolean? = null
    /** 增量推送主题 dt/{id}/push 是否订阅成功（仅影响提示文案；SUCCESS 一律单次拉快照） */
    private var pushAvailable = true
    /** B3：Toast 去重状态 —— 相同 key 在窗口内只弹一次，避免失败/离线态循环轰炸 */
    private var lastToastKey: String? = null
    private var lastToastAt = 0L
    /** B5：最近一次查询发送时间戳，用于推导连接质量(RTT) */
    private var lastQuerySentTs = 0L
    /** B5：最近一次下发指令的展示名，用于「最近指令」回执关联（无 rid 匹配时的兜底） */
    private var lastCommandLabel = ""

    /**
     * rid → 指令展示名。单变量 lastCommandLabel 在「连续下发多条指令」时会被后一条覆盖，
     * 导致先回来的 ACK 关联到错误的指令名（「最近指令」错乱）。按 rid 精确关联可根治。
     * 用 LinkedHashMap 并限长，避免 ACK 丢失时无限增长。
     */
    private val pendingCommands = mutableMapOf<String, PendingCommand>()

    private data class PendingCommand(
        val field: String,
        val value: PacketValue,
        val label: String
    )

    /** 当前处于「下发中」的一次性动作 rid：只有它的回执才应解除快捷按钮置灰 */
    private var pendingActionRid: String? = null

    /** 登记 rid→指令名；ACK 丢失时淘汰最早记录，避免映射无限增长 */
    private fun rememberCommandLabel(rid: String, label: String, field: String? = null, value: PacketValue? = null) {
        if (field != null && value != null) {
            pendingCommands[rid] = PendingCommand(field, value, label)
        } else if (label.isNotBlank()) {
            // 兼容旧调用：仅存 label
            pendingCommands[rid] = PendingCommand("", PacketValue.StringValue(""), label)
        }
        if (pendingCommands.size > 32) {
            pendingCommands.keys.firstOrNull()?.let { pendingCommands.remove(it) }
        }
    }

    private lateinit var overviewFragment: OverviewFragment
    private lateinit var tasksFragment: TasksFragment
    private lateinit var calendarFragment: CalendarFragment
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var deviceFragment: DeviceFragment

    companion object {
        private const val TAG_OVERVIEW = "overview"
        private const val TAG_TASKS = "tasks"
        private const val TAG_CALENDAR = "calendar"
        private const val TAG_SETTINGS = "settings"
        private const val TAG_DEVICE = "device"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
binding = ActivityDeviceControlBinding.inflate(layoutInflater)
          setContentView(binding.root)
          UiInsets.applyStatusBarPadding(this, binding.appBar)

          // 悬浮导航栏按圆角裁剪（由 bg_nav_floating shape + clipToOutline 处理）

        val deviceId = intent.getStringExtra("deviceId")
        if (deviceId.isNullOrBlank()) { finish(); return }
        db = Room.databaseBuilder(this, AppDatabase::class.java, "daily-db")
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .fallbackToDestructiveMigration(dropAllTables = true).build()
        val loaded = runBlocking(Dispatchers.IO) { db.deviceDao().getById(deviceId) }
        if (loaded == null) {
            Toast.makeText(this, "设备记录不存在", Toast.LENGTH_SHORT).show(); finish(); return
        }
        device = loaded
        remoteEnabled = prefs.getBoolean("remote_enabled_${device.deviceId}", true)
        // 重新配对载荷落地（扫码/剪贴板/分享导入）：默认自动重开 MQTT 连接开关。
        // 解绑时会持久化关闭开关，若不在此重开，配对流程永远不会发起（死等配对）；
        // onPairAccepted 成功后仍会持久化确认一次。
        if (!device.bound && device.pairingToken.isNotBlank()) {
            remoteEnabled = true
            prefs.edit().putBoolean("remote_enabled_${device.deviceId}", true).apply()
        }

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
          setupControllerNav()
          switchTab(TAG_OVERVIEW)

          // 磨砂玻璃悬浮导航：模糊其下方的全部内容，clipToOutline 裁剪圆角
          binding.navBarDc.blurView.post {
              try {
                  binding.navBarDc.blurView.setupWith(binding.root)
                      .setBlurRadius(24f)
                      .setOverlayColor(android.graphics.Color.TRANSPARENT)
              } catch (_: Exception) { }
          }

        if (remoteEnabled) {
            if (device.sessionSecret.isBlank() && device.pairingToken.isBlank()) {
                // 已解绑/从未配对：不建立 MQTT 连接（不打扰被控端），保留缓存数据展示。
                // 仅「重新配对」可点（其余控件由 onResume 对 !device.bound 统一禁用）；
                // 扫码/剪贴板导入（applyRePair）获得令牌后才 initMqtt 开始重新配对。
                setConnStatus("未配对，请重新扫码", false)
            } else {
                setConnStatus("连接中…", false)
                initMqtt()
            }
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
            onRemoteToggle = { on -> this@DeviceControlActivity.setRemoteEnabled(on) }
            onAction = { action -> sendAction(action) }
            onLoopToggle = { on ->
                sendUpdate(Protocol.FIELD_TASK_AUTO_RECYCLE, PacketValue.BooleanValue(on))
                Toast.makeText(this@DeviceControlActivity, if (on) "已下发开启循环" else "已下发关闭循环", Toast.LENGTH_SHORT).show()
            }
            onRePairClick = { showRePairSheet() }
            onRetryClick = { retryConnection() }
            // 「重新连接」按钮：原"重新连接"语义升级为重配对入口（页内扫码/剪贴板），针对当前设备重新配对
            onReconnectClick = { showRePairSheet() }
            onAlertClick = { record -> showAlertDialog(record) }
            setRemoteEnabled(remoteEnabled)
        }
        tasksFragment = TasksFragment().apply {
            onAddTask = { time, name -> sendTask("add", time, null, name) }
            onEditTask = { item, newTime, name -> sendTask("update", newTime, item.time, name) }
            onDeleteTask = { item -> sendTask("delete", item.time, item.time) }
        }
        calendarFragment = CalendarFragment()
        settingsFragment = SettingsFragment().apply {
            onToggle = { item, on -> sendUpdate(item.key, PacketValue.BooleanValue(on)) }
            onIntChange = { item, v -> sendUpdate(item.key, PacketValue.IntValue(v)) }
            onStringChange = { item, s -> sendUpdate(item.key, PacketValue.StringValue(s)) }
            // 需求 1 + 8：消息渠道批量配置含 Webhook Key / 邮箱授权码等机密，
            // 用配对派生的会话密钥做 AES-GCM 信封加密后再下发，Broker 侧只能看到密文
            onMsgConfigSave = { json ->
                sendUpdate(
                    Protocol.FIELD_MSG_CONFIG,
                    PacketValue.StringValue(SecretBox.seal(device.sessionSecret, json))
                )
            }
            onChannelChange = { v -> sendUpdate(Protocol.FIELD_MSG_CHANNEL, PacketValue.IntValue(v)) }
        }
        deviceFragment = DeviceFragment().apply {
            // 已绑定：解绑设备（下发 UB）；未绑定：删除设备（清本地记录回列表）
            onUnbind = { confirmUnbind() }
            onDelete = { confirmDeleteDevice() }
        }

        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, overviewFragment, TAG_OVERVIEW)
            .add(R.id.fragmentContainer, tasksFragment, TAG_TASKS)
            .add(R.id.fragmentContainer, calendarFragment, TAG_CALENDAR)
            .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
            .add(R.id.fragmentContainer, deviceFragment, TAG_DEVICE)
            .hide(tasksFragment)
            .hide(calendarFragment)
            .hide(settingsFragment)
            .hide(deviceFragment)
            .commitNow()
        overviewFragment.setRemoteEnabled(remoteEnabled)
    }

    private fun switchTab(tag: String) {
        if (tag == currentFragmentTag) return
        currentFragmentTag = tag
        updateNavSelection(tag)
        val target = when (tag) {
            TAG_OVERVIEW -> overviewFragment
            TAG_TASKS -> tasksFragment
            TAG_CALENDAR -> calendarFragment
            TAG_SETTINGS -> settingsFragment
            TAG_DEVICE -> deviceFragment
            else -> overviewFragment
        }
        val reduceMotion = android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f
        ) == 0f
        val ft = supportFragmentManager.beginTransaction()
        listOf(overviewFragment, tasksFragment, calendarFragment, settingsFragment, deviceFragment)
            .forEach { ft.hide(it) }
        ft.show(target)
        if (reduceMotion) {
            ft.commitNow()
        } else {
            // Material Motion「淡入穿透」：对容器内 fragment 视图的显隐变化做交叉淡入淡出（~250ms）
            TransitionManager.beginDelayedTransition(
                findViewById<ViewGroup>(R.id.fragmentContainer),
                MaterialFadeThrough()
            )
            ft.commitNow()
        }
        currentSnapshot?.let { target.refresh(it) }
    }

    /** ═══════ 自定义悬浮导航（5 项均等分布，总览为凸出枢纽圆） ═══════ */
private fun setupControllerNav() {
          // 绑定 5 个导航项的点击事件
          binding.navBarDc.navCalendar.setOnClickListener { switchTab(TAG_CALENDAR) }
          binding.navBarDc.navTasks.setOnClickListener { switchTab(TAG_TASKS) }
          binding.navBarDc.navOverview.setOnClickListener { switchTab(TAG_OVERVIEW) }
          binding.navBarDc.navDevice.setOnClickListener { switchTab(TAG_DEVICE) }
          binding.navBarDc.navSettings.setOnClickListener { switchTab(TAG_SETTINGS) }

          // 初始选中态
          updateNavSelection(TAG_OVERVIEW)
      }

      /** 更新导航选中态：图标/文字颜色（同 DT 方式，仅变色无胶囊指示器，§3.12）*/
      private fun updateNavSelection(activeTag: String) {
          val activeColor = ContextCompat.getColor(this, R.color.md_primary)
          val inactiveColor = ContextCompat.getColor(this, R.color.md_onSurfaceVariant)

          fun setItemState(icon: ImageView, label: TextView, isActive: Boolean) {
              icon.imageTintList = if (isActive)
                  ColorStateList.valueOf(activeColor) else ColorStateList.valueOf(inactiveColor)
              label.setTextColor(if (isActive) activeColor else inactiveColor)
          }

          setItemState(binding.navBarDc.iconCalendar, binding.navBarDc.labelCalendar, activeTag == TAG_CALENDAR)
          setItemState(binding.navBarDc.iconTasks, binding.navBarDc.labelTasks, activeTag == TAG_TASKS)
          setItemState(binding.navBarDc.iconDevice, binding.navBarDc.labelDevice, activeTag == TAG_DEVICE)
          setItemState(binding.navBarDc.iconSettings, binding.navBarDc.labelSettings, activeTag == TAG_SETTINGS)

        // 总览凸起按钮：两态共用同一白盘（同材质同投影，避免悬浮感割裂），选中仅以图标颜色区分
        val overviewActive = activeTag == TAG_OVERVIEW
        binding.navBarDc.iconOverview.imageTintList =
            ColorStateList.valueOf(if (overviewActive) activeColor else inactiveColor)
      }

    private fun refreshCurrentFragment() {
        val snapshot = currentSnapshot ?: return
        val target = when (currentFragmentTag) {
            TAG_OVERVIEW -> overviewFragment
            TAG_TASKS -> tasksFragment
            TAG_CALENDAR -> calendarFragment
            TAG_SETTINGS -> settingsFragment
            TAG_DEVICE -> deviceFragment
            else -> overviewFragment
        }
        target.refresh(snapshot)
    }

    // ===================== MQTT =====================
    private fun initMqtt() {
        // 已解绑/未配对（无会话密钥且无配对令牌）不建连接，避免打扰被控端；
        // applyRePair 先写入新 pairingToken 再调本方法，不受拦截
        if (device.sessionSecret.isBlank() && device.pairingToken.isBlank()) {
            Log.d(TAG, "initMqtt 跳过：设备未配对 deviceId=${device.deviceId}")
            return
        }
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
        // 若只在此处处理，会出现"已连接但从未订阅/发配对、永远配对中"的回归。
        mqttClient?.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                MqttQuota.onConnect(this@DeviceControlActivity)
                // broker 连接成功 ≠ 被控端在线：先中性「连接中」(灰)，避免误亮绿灯；
                // 真实在线态由后续「探活中→快照确认」或配对分支决定。
                runOnUiThread { setConnStatus("连接中…", false) }
                // 重连成功时复位离线标记：若此前首次探活判定离线，本次重连会重新走首次探活逻辑。
                recentlyMarkedOffline = false
                if (device.sessionSecret.isBlank()) {
                    if (device.pairingToken.isNotBlank()) {
                        // 有配对意图（扫码得到令牌）：
                        if (reconnect) {
                            // 重连：先订配对必需主题并立即发 P，全量主题并行补订（不再固定干等 500ms）
                            Log.d(TAG, "重连成功，立即发起配对 deviceId=${device.deviceId}")
                            lifecycleScope.launch(Dispatchers.IO) {
                                subscribePairEssential()
                                publishPair(resubscribe = false)
                                subscribeTopics()
                            }
                        }
                        // 初始连接不在此发配对：connect() 之后的初始流程负责，避免重复发起。
                    } else {
                        // 已解绑/从未扫码（无配对令牌）：不再向被控端发配对请求打扰，
                        // 仅订阅读状态；由用户重新扫码发起配对。
                        if (reconnect) {
                            lifecycleScope.launch(Dispatchers.IO) { subscribeTopics() }
                        }
                    }
                } else {
                    // 每次连接/重连都重新订阅全部主题；订阅是幂等的。
                    // 关键顺序：先订阅（subscribeTopics 同步等 SUBACK 返回）→ 完成后再首次探活。
                    // 若并行发 QUERY，被控端 resp 到达时订阅未建立会被 broker 丢弃，
                    // 首次探活超时 → 误判离线（表现为「先进设备页报离线、随后又报恢复响应」）。
                    lifecycleScope.launch(Dispatchers.IO) {
                        subscribeTopics()
                        // 仅真重连时复位探活标志：首次连接由 connect() 返回分支负责，
                        // 无条件复位会二次触发探活并打乱在途的重试计数
                        if (reconnect) firstEntryProbeStarted = false
                        // 首次进入详情页：仅此时主动拉一次快照；琥珀「探活中」统一由
                        // startFirstEntryProbe 在真正发出探活时设置，避免后到者把绿灯覆盖成黄
                        startFirstEntryProbe()
                    }
                }
            }

            override fun connectionLost(cause: Throwable?) {
                MqttQuota.onDisconnect(this@DeviceControlActivity)
                runOnUiThread { setConnStatus("连接断开", false) }
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                message?.let {
                    bumpQuota(0, 1)
                    // 传入 retained 标志：broker 保留的历史状态不能证明设备当前在线，
                    // 只有被控端主动发布的非 retained 消息才可信。
                    handleIncoming(topic ?: "", String(it.payload), it.isRetained)
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
                // 与 connectComplete 分支保持一致：复位离线标记，首次探活在下方配对分支触发。
                recentlyMarkedOffline = false
                if (device.sessionSecret.isBlank()) {
                    if (device.pairingToken.isNotBlank()) {
                        // 有配对意图（扫码得到令牌）：先订 pair/accept(+ack) 就发 P，
                        // 避免等齐 6 路 SUBACK 卡数秒；其余主题后台补订。
                        pairRetries = 0
                        subscribePairEssential()
                        publishPair(resubscribe = false)
                        subscribeTopics()
                    } else {
                        // 已解绑/从未扫码：不发起配对打扰被控端，仅订阅读状态，提示用户重新扫码
                        subscribeTopics()
                        runOnUiThread {
                            setConnStatus("未配对，请重新扫码", false)
                        }
                    }
                } else {
                    subscribeTopics()
                    // 首次进入详情页：仅此时主动拉一次快照；
                    // 琥珀「探活中」由 startFirstEntryProbe 统一设置（理由见 connectComplete 分支注释）
                    startFirstEntryProbe()
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

    /**
     * 配对握手必需主题：PA 回执 + ACK（NO_PAIRING / TOKEN_MISMATCH）。
     * 批量一次 SUBACK，避免进页后串行等 6 路订阅才开始配对。
     */
    private suspend fun subscribePairEssential() {
        val topics = arrayOf(
            "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/pair/accept",
            "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/ack"
        )
        val qos = IntArray(topics.size) { 1 }
        try {
            withTimeout(4_000) { mqttClient?.subscribeWithResponse(topics, qos) }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "配对必要主题订阅超时(4s)")
        } catch (e: MqttException) {
            Log.w(TAG, "配对必要主题订阅失败: ${e.message}")
        }
    }

    /** 全量主题：批量订阅（一次往返），替代逐主题串行等待 SUBACK */
    private suspend fun subscribeTopics() {
        val pushTopic = "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/push"
        val alertTopic = "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/alert"
        val topics = arrayOf(
            "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/status",
            "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/ack",
            "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/pair/accept",
            "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/resp",
            pushTopic,
            alertTopic,
            "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/presence"
        )
        val qos = IntArray(topics.size) { 1 }
        var anyDenied = false
        var pushDenied = false
        try {
            // 批量订阅：broker 一次 SUBACK；超时 5s，避免串行 6×5s
            val subAck = withTimeout(5_000) { mqttClient?.subscribeWithResponse(topics, qos) }
            val granted = subAck?.grantedQos
            if (granted != null) {
                for (i in granted.indices) {
                    if (granted[i] == 128) {
                        anyDenied = true
                        if (topics[i] == pushTopic) pushDenied = true
                        Log.w(TAG, "订阅被 broker 拒绝(ACL): ${topics[i]} 账户=${device.ctlUser}")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "批量订阅超时(5s)")
            anyDenied = true
        } catch (e: MqttException) {
            e.printStackTrace()
            anyDenied = true
        }
        if (pushDenied) {
            pushAvailable = false
            Log.w(TAG, "增量推送主题订阅失败：仍不轮询；设置 SUCCESS 后会单次拉快照")
        }
        if (anyDenied) {
            runOnUiThread {
                setConnStatus("已连接（订阅被拒）", true)
                val msg = if (pushDenied) {
                    "增量推送主题 ${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/push 被 broker 拒绝（EMQX ACL 未授权）。不会回退轮询；改设置成功后仍会拉一次快照。请给账户 ${device.ctlUser} 增加该主题的订阅权限以恢复增量推送。"
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
        mainHandler.removeCallbacks(queryTimeoutRunnable)
        mainHandler.removeCallbacks(PAIR_TIMEOUT_RUNNABLE)
        // 断连时清空探活/离线状态，下次连接（含重连/回前台）重新进行首次探活
        firstEntryProbeStarted = false
        firstEntryProbeActive = false
        recentlyMarkedOffline = false
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
        runOnUiThread { deviceFragment.showQuota(stats) }
    }

    /** B3：Toast 去重 —— 相同 key 在 dedupeWindowMs 内只弹一次，重复事件只更新状态 UI 不再轰炸 */
    private fun toastOnce(key: String, msg: String, dedupeWindowMs: Long = 4000) {
        val now = System.currentTimeMillis()
        if (key == lastToastKey && now - lastToastAt < dedupeWindowMs) return
        lastToastKey = key
        lastToastAt = now
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /**
     * @param resubscribe 为 true 时仅补订配对必需主题（重试/重连用），不再全量串行订阅。
     */
    private suspend fun publishPair(resubscribe: Boolean = true) {
        if (mqttClient?.isConnected != true) {
            Log.w(TAG, "publishPair 跳过：MQTT 未连接")
            return
        }
        mainHandler.removeCallbacks(PAIR_TIMEOUT_RUNNABLE)
        // 只保证能收到 PA/ACK；全量主题由 init/connectComplete 另订，避免每次发 P 再等一轮订阅。
        if (resubscribe) subscribePairEssential()
        val ts = System.currentTimeMillis()
        val rid = UUID.randomUUID().toString()
        pendingPairRid = rid
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

    private fun handleIncoming(topic: String, payload: String, retained: Boolean = false) {
        lastActivityMs = System.currentTimeMillis()
        if (recentlyMarkedOffline) {
            runOnUiThread { recoverFromFalseOffline() }
        }
        when {
            topic.endsWith("/status") -> handleStatus(payload.trim(), retained)
            topic.endsWith("/pair/accept") -> onPairAccepted(payload)
            topic.endsWith("/resp") -> onSnapshot(payload)
            topic.endsWith("/push") -> onPush(payload)
            topic.endsWith("/alert") -> onAlert(payload)
            topic.endsWith("/ack") -> handleAck(payload)
            // presence HB：记录时间戳供首进探活快速路径；lastActivityMs 已在方法首部刷新
            topic.endsWith("/presence") -> OnlineStateCache.noteHb(device.deviceId, payload)
        }
    }

    /**
     * 处理被控端状态主题 dt/{id}/status。
     * 关键：broker 保留的「online」是历史值，不能证明设备当前在线（可能早已关机/断网），
     * 因此不能据此点亮绿灯 —— 真实在线态只由「首次探活的快照查询成功 / 被控端推送 / 非 retained 的在线发布」确认。
     * 只有显式「offline」或解绑类消息才可信地判离线/解绑。
     */
    private fun handleStatus(raw: String, retained: Boolean) {
        // v3：解绑状态优先走签名信封；已配对时拒绝无签名 plain unbound，防公共 Broker 伪造
        val state = resolveStatusState(raw) ?: return
        val text = when (state) {
            "online" -> "在线（已配对）"
            "unbound" -> "已解绑"
            "force_unbound" -> "已被强制解绑"
            else -> "设备离线"
        }
        Log.d(TAG, "收到被控端状态消息 raw=$raw state=$state retained=$retained -> 显示「$text」(deviceId=${device.deviceId})")
        if (state == "force_unbound" || state == "unbound") {
            if (device.pairingToken.isNotBlank()) {
                Log.d(TAG, "忽略残留解绑状态 state=$state：当前正处于配对中(pairingToken 有效)，等待被控端接受配对后回 online")
                return
            }
            runOnUiThread { handleRemoteUnbound(force = state == "force_unbound") }
            return
        }
        if (state == "offline") {
            // 设备显式发布离线（含 lastWill）：可信，直接判离线（红灯）
            // 仅在「在线→离线」真实转跳时广播，避免 retained 离线重复到达反复通知
            if (deviceWasOnline == true) {
                sendOfflineBroadcast(device.name, device.deviceId, System.currentTimeMillis())
            }
            deviceWasOnline = false
            runOnUiThread { setConnStatus("设备离线", false) }
            return
        }
        // state == "online"：
        if (retained) {
            // broker 保留的历史 online，不点亮绿灯；维持「探活中」或当前离线态，
            // 真实在线由首次探活的快照查询 / 推送确认。
            Log.d(TAG, "忽略 retained online：不点亮绿灯，等首次探活/推送确认真实在线")
            return
        }
        // 非 retained 的在线消息：被控端刚刚主动发布 online，确证在线 → 点亮绿灯
        deviceWasOnline = true
        runOnUiThread { setConnStatus(text, true) }
    }

    /**
     * 解析 status 载荷：
     * - 签名 JSON（CMD_STATUS）→ 验签通过后返回 state
     * - plain unbound 且本地已有 session → 拒绝（防伪造）
     * - 其它 plain → 原样返回
     */
    private fun resolveStatusState(raw: String): String? {
        if (raw.startsWith("{")) {
            return try {
                val packet = gson.fromJson(raw, MqttPacket::class.java) ?: return null
                if (packet.c != Protocol.CMD_STATUS && packet.c != MqttPacket.CMD_STATUS) return null
                val state = packet.v?.toStringValue() ?: return null
                val session = device.sessionSecret
                if (session.isBlank()) {
                    // 本地已无会话：仍接受签名信封中的解绑文案（重连后清残留）
                    if (state == "unbound" || state == "force_unbound") state else null
                } else {
                    val expected = MqttSigner.sign(
                        session, device.deviceId, packet.ts, packet.rid, "", "s", state, Protocol.CMD_STATUS
                    )
                    if (expected != packet.sign) {
                        Log.w(TAG, "status 签名信封验签失败，已忽略")
                        return null
                    }
                    if (!acceptInboundRid(packet.rid, packet.ts)) return null
                    state
                }
            } catch (_: Exception) {
                null
            }
        }
        if ((raw == "unbound" || raw == "force_unbound") && device.sessionSecret.isNotBlank()) {
            Log.w(TAG, "忽略无签名 plain 解绑状态（已配对）：$raw")
            return null
        }
        return raw
    }

    /** 广播设备离线事件，交由 OfflineMonitorService 统一弹通知 + 记录上次离线时间 */
    private fun sendOfflineBroadcast(deviceName: String, deviceId: String, ts: Long) {
        sendBroadcast(Intent(OfflineMonitorService.ACTION_DEVICE_OFFLINE).apply {
            putExtra("deviceName", deviceName)
            putExtra("deviceId", deviceId)
            putExtra("ts", ts)
        })
    }

    /**
     * 收到被控端解绑通知（force_unbound / unbound）后的处理：
     * 1) 断开本机 MQTT 连接（不再收发任何消息）
     * 2) 清除本地配对态（sessionSecret / bound），持久化到 DB
     * 3) 禁用刷新、MQTT 开关、快捷动作等所有远程操作控件，仅保留缓存快照可见
     * 4) 提示用户该设备已解绑，需使用被控端重新配对
     * 重新配对成功后（[onPairAccepted]）恢复正常。
     */
    /** 解绑态 UI：总览控件禁用 + 显示重配对入口；任务/设置页置灰禁用；设备页切「删除设备」 */
    private fun applyUnboundUiState() {
        overviewFragment.setControlsEnabled(false)
        overviewFragment.setRePairVisible(true)
        tasksFragment.setCommandsEnabled(false)
        settingsFragment.setCommandsEnabled(false)
        deviceFragment.setUnboundState(true)
    }

    /** 配对成功/已绑定态：恢复全部控件，隐藏重配对入口 */
    private fun applyBoundUiState() {
        overviewFragment.setControlsEnabled(true)
        overviewFragment.setRePairVisible(false)
        tasksFragment.setCommandsEnabled(true)
        settingsFragment.setCommandsEnabled(true)
        deviceFragment.setUnboundState(false)
    }

    /** 删除设备（仅未绑定态）：清本地记录后返回列表 */
    private fun confirmDeleteDevice() {
        UnifiedDialogKit.showConfirm(
            this,
            "删除设备",
            "将从控制端删除该设备的全部本地数据（配对信息/缓存/历史），确定删除？",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                lifecycleScope.launch(Dispatchers.IO) {
                    db.deviceDao().deleteById(device.deviceId)
                    OfflineMonitorService.requestRefresh(this@DeviceControlActivity)
                    runOnUiThread {
                        Toast.makeText(this@DeviceControlActivity, "设备已删除", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        )
    }

    private fun handleRemoteUnbound(force: Boolean) {
        Log.d(TAG, "收到被控端解绑通知 force=$force，断开 MQTT 并清除本地配对态")
        forceUnbound = force
        disconnectMqtt()
        // 解绑：MQTT 连接开关持久化关闭且不可手动开启（setControlsEnabled(false) 已禁用开关），
        // 仅当用户重新扫码配对（onPairAccepted 成功）时才默认重新持久化开启。
        remoteEnabled = false
        prefs.edit().putBoolean("remote_enabled_${device.deviceId}", false).apply()
        // 清除配对态：连接信息显示「未配对」，btnRePair 自动显示
        device = device.copy(sessionSecret = "", pairingToken = "", bound = false)
        lifecycleScope.launch(Dispatchers.IO) { db.deviceDao().update(device) }
        // 禁用所有远程操作控件（刷新 / MQTT 开关 / 下拉刷新 / 快捷动作），仅留「重新配对」可点；
        // 任务/设置页同步置灰禁用，设备页切换为「删除设备」
        applyUnboundUiState()
        overviewFragment.setRemoteEnabled(false)
        // 状态文案 + 解绑横幅（区别于普通离线提示）
        setConnStatus(
            if (force) "已被强制解绑" else "已解绑",
            false,
            if (force) "设备已被强制解绑，请重新配对绑定" else "设备已解绑，请重新配对绑定"
        )
        overviewFragment.setStaleBanner(true)
        Toast.makeText(
            this,
            if (force) "该设备已被强制解绑，请使用被控端重新配对" else "该设备已解绑，请使用被控端重新配对",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun onPairAccepted(payload: String) {
        mainHandler.removeCallbacks(PAIR_TIMEOUT_RUNNABLE)
        if (device.pairingToken.isBlank()) {
            Log.w(TAG, "收到 PA 但本地无 pairingToken，忽略")
            return
        }
        val packet = try {
            gson.fromJson(payload, MqttPacket::class.java)
        } catch (_: Exception) {
            null
        }
        if (packet == null || packet.c != Protocol.CMD_PAIR_ACCEPT) {
            Log.w(TAG, "PA 载荷非法，忽略")
            return
        }
        val ok = packet.v?.toStringValue() ?: ""
        val expected = MqttSigner.sign(
            device.pairingToken, device.deviceId, packet.ts, packet.rid, "", "s", ok, Protocol.CMD_PAIR_ACCEPT
        )
        if (expected != packet.sign) {
            Log.w(TAG, "PA 验签失败，忽略伪造配对确认")
            runOnUiThread {
                setConnStatus("已连接（未配对）", true)
                Toast.makeText(this, "配对确认验签失败，请重新扫码", Toast.LENGTH_LONG).show()
            }
            return
        }
        val expectRid = pendingPairRid
        if (expectRid != null && packet.rid != expectRid) {
            Log.w(TAG, "PA rid 与配对请求不一致 expect=$expectRid got=${packet.rid}")
            return
        }
        Log.d(TAG, "收到配对确认 PA 验签通过，开始派生会话密钥 deviceId=${device.deviceId}")
        val session = Hkdf.deriveHex(device.pairingToken, device.deviceId, MqttPacket.PAIRING_INFO, MqttPacket.SESSION_KEY_LEN)
        pendingPairRid = null
        device = device.copy(sessionSecret = session, pairingToken = "", bound = true)
        lifecycleScope.launch(Dispatchers.IO) {
            db.deviceDao().update(device)
            OfflineMonitorService.requestRefresh(this@DeviceControlActivity)
        }
        runOnUiThread {
            // 方案2保险：配对成功即强制开启远程开关（避免升级安装残留的 false 导致 UI 显示关）
            prefs.edit().putBoolean("remote_enabled_${device.deviceId}", true).apply()
            remoteEnabled = true
            overviewFragment.setRemoteEnabled(true)
            // 恢复所有远程操作控件（解绑时被禁用的刷新 / MQTT 开关 / 快捷动作 / 任务设置页 / 重配对入口隐藏）
            applyBoundUiState()
            setConnStatus("已连接（已配对）", true)
            Toast.makeText(this, "已与控制端完成配对", Toast.LENGTH_SHORT).show()
        }
        // 配对刚完成即视为「首次在线」：发起一次探活 + 快照拉取（含重试与离线判定）
        startFirstEntryProbe()
    }

    private fun handleAck(payload: String) {
        Log.d(TAG, "收到 ack: $payload")
        try {
            val packet = gson.fromJson(payload, MqttPacket::class.java) ?: return
            // 已配对时强制验签；配对握手阶段（NO_PAIRING 等）允许无会话签名
            // 被控端 ACK 签名约定：f="" t="" v=result（见 MqttAgentService.doPublishAck）
            if (device.sessionSecret.isNotBlank()) {
                val resultWire = packet.v?.toStringValue() ?: return
                val expected = MqttSigner.sign(
                    device.sessionSecret, device.deviceId, packet.ts, packet.rid,
                    "", "", resultWire, Protocol.CMD_ACK
                )
                if (packet.sign != expected) {
                    // 空签名 UNBOUND = 被控端会话已清空（被解绑）；rid 须命中本端在途请求，防公共 Broker 伪造
                    if (packet.sign.isBlank() && resultWire.startsWith("UNBOUND") &&
                        packet.rid.isNotBlank() &&
                        (packet.rid == queryPendingRid || packet.rid == pendingPairRid ||
                            pendingCommands.containsKey(packet.rid))
                    ) {
                        Log.w(TAG, "收到空签名 UNBOUND 回执（被控端已解绑）rid=${packet.rid} -> 触发本地解绑")
                        runOnUiThread { handleRemoteUnbound(force = false) }
                        return
                    }
                    // 换绑后被控端用新会话签 SIGN_FAIL，旧会话验签必失败；
                    // rid 命中本端在途请求（仅真实被控端可回）即判定解绑/换绑
                    if (resultWire.startsWith("SIGN_FAIL") &&
                        packet.rid.isNotBlank() &&
                        (packet.rid == queryPendingRid || pendingCommands.containsKey(packet.rid))
                    ) {
                        Log.w(TAG, "收到 SIGN_FAIL（会话失效/被换绑）rid=${packet.rid} -> 触发本地解绑")
                        runOnUiThread { handleRemoteUnbound(force = false) }
                        return
                    }
                    Log.w(TAG, "ACK 验签失败 rid=${packet.rid}")
                    runOnUiThread { toastOnce("ack_sign_fail", "回执验签失败，已忽略") }
                    return
                }
                if (!acceptInboundRid(packet.rid, packet.ts)) return
            }
            val result = packet.v?.toStringValue() ?: "-"
            // 被控端有两类回执是「码:详情」形式（TASK_FAIL:该时间点已存在 / ACTION_FAIL:xxx，
            // 见 MqttAgentService 400/464/492/503/515/520 行）。若直接对整串做精确匹配，
            // 这些分支永远命中不了、只会落到 else 弹出原始码，且不会触发快照刷新。
            // 因此统一按冒号前的「码」做分支，冒号后的部分作为详情展示。
            val code = result.substringBefore(':')
            val detail = result.substringAfter(':', "")
            runOnUiThread {
                when (code) {
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
                    // 成功路径：以被控端快照为准刷新 UI（防增量 push 漏发漏刷）；不做本地乐观写。
                    // 延迟拉全量见 forceRefreshSnapshot：ACK 可能早于被控端状态落地。
                    "SUCCESS" -> {
                        // 以被控端为准：不做本地乐观写。SUCCESS 即执行成功，主动拉一次快照，
                        // 避免被控端未发增量 push 时漏刷（不恢复 15s 轮询）。
                        // pendingCommands 由下方统一 remove 取 label。
                        if (packet.rid.isNotBlank() && pendingCommands.containsKey(packet.rid)) {
                            val cmd = pendingCommands[packet.rid]
                            // 更新节假日：命令下发成功且被控端回 SUCCESS 回执后才提示「已下发成功」
                            if (cmd?.field == Protocol.FIELD_UPDATE_HOLIDAY) {
                                Toast.makeText(this, "节假日更新已下发成功", Toast.LENGTH_SHORT).show()
                            }
                            // 乐观更新本地缓存：下发字段直接写入 settings 区块对应项并刷新 UI，
                            // 立即反映下发结果（不等 QUERY 往返）；被控端增量推送/拉全量随后校准
                            val snapshot = currentSnapshot
                            if (snapshot != null && cmd?.field != null && cmd.field.isNotBlank()) {
                                val newVal: Any? = when (val v = cmd.value) {
                                    is PacketValue.BooleanValue -> v.b
                                    is PacketValue.IntValue -> v.i
                                    is PacketValue.StringValue -> v.s
                                }
                                if (newVal != null) {
                                    snapshot.settings.firstOrNull { it.key == cmd.field }?.value = newVal
                                    overviewFragment.refresh(snapshot)
                                }
                            }
                            // 关闭远程控制：被控端 ack 后约 1.5s 停 MQTT 服务，此刻拉快照
                            // 的 QUERY 必然到不了被控端 → 误报「被控端未返回快照」。跳过刷新，
                            // 改提示「已关闭」即可（连接断开后总览自然显示离线）。
                            if (cmd?.field == Protocol.FIELD_REMOTE_ENABLED &&
                                (cmd.value as? PacketValue.BooleanValue)?.b == false
                            ) {
                                Toast.makeText(this, "远程控制已关闭", Toast.LENGTH_SHORT).show()
                            } else {
                                forceRefreshSnapshot()
                            }
                        }
                    }
                    "TASK_OK" -> Toast.makeText(this, "任务已更新", Toast.LENGTH_SHORT).show()
                    // 以下失败回执一律补拉一次快照：本地 UI（Switch/任务列表）在下发时已乐观翻转，
                    // 若被控端拒绝而不回滚，本地显示会与被控端真实状态长期不一致。
                    "TASK_FAIL" -> {
                        Toast.makeText(this, "任务操作失败：$detail", Toast.LENGTH_LONG).show()
                        forceRefreshSnapshot()
                    }
                    "ACTION_FAIL" -> {
                        Toast.makeText(this, "动作执行失败：$detail", Toast.LENGTH_LONG).show()
                        forceRefreshSnapshot()
                    }
                    "SNAPSHOT_FAIL" -> Toast.makeText(this, "快照生成失败", Toast.LENGTH_SHORT).show()
                    "NEED_MANUAL" -> {
                        Toast.makeText(this, "该设置需在系统/被控端手动开启（如无障碍、截屏权限）", Toast.LENGTH_LONG).show()
                        forceRefreshSnapshot()
                    }
                    "SERVICE_UNAVAILABLE" -> {
                        Toast.makeText(this, "被控端服务未就绪（通知监听未运行），动作未执行", Toast.LENGTH_LONG).show()
                        forceRefreshSnapshot()
                    }
                    "UNKNOWN_ACTION" -> Toast.makeText(this, "未知动作：$result", Toast.LENGTH_SHORT).show()
                    "DUP_OR_STALE" -> {
                        Toast.makeText(this, "被控端：请求已过期或重复（请检查两端时间是否一致）", Toast.LENGTH_LONG).show()
                        forceRefreshSnapshot()
                    }
                    // CMD_SYNC 的探活回执，无需打扰用户
                    "ONLINE" -> Unit
                    else -> Toast.makeText(this, "设备回执：$result", Toast.LENGTH_SHORT).show()
                }
                // 按 rid 精确关联指令名，避免连续下发时「最近指令」张冠李戴；取不到再退回单变量兜底
                val ackRid = packet.rid
                val label = ackRid.takeIf { it.isNotBlank() }?.let { pendingCommands.remove(it)?.label }
                    ?: lastCommandLabel
                addRecentCommand(label, ackFriendly(result))
                // D2：只有「当前下发中的一次性动作」自己的回执才解除置灰并弹 Snackbar。
                // 原实现无条件解锁，设置/任务类回执会把动作按钮提前解锁、Snackbar 也会错弹。
                if (ackRid.isNotBlank() && ackRid == pendingActionRid) {
                    pendingActionRid = null
                    overviewFragment.setActionsBusy(false)
                    mainHandler.removeCallbacks(actionBusyResetRunnable)
                    showActionSnackbar(result)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** B5：把回执结果转成简短展示文案（用于「最近指令」列表）。与 handleAck 一致按冒号前的码分支 */
    private fun ackFriendly(result: String): String = when (result.substringBefore(':')) {
        "NO_PAIRING" -> "尚未生成配对码"
        "TOKEN_MISMATCH" -> "配对码不一致或已过期"
        "UNBOUND" -> "当前未绑定"
        "SIGN_FAIL" -> "签名校验失败"
        "SUCCESS" -> "成功"
        "ONLINE" -> "在线"
        "TASK_OK" -> "任务已更新"
        "TASK_FAIL" -> "任务失败：${result.substringAfter(':', "")}"
        "ACTION_FAIL" -> "动作失败：${result.substringAfter(':', "")}"
        "SNAPSHOT_FAIL" -> "快照生成失败"
        "NEED_MANUAL" -> "需手动开启"
        "SERVICE_UNAVAILABLE" -> "服务未就绪"
        "UNKNOWN_ACTION" -> "未知动作"
        "DUP_OR_STALE" -> "请求过期或重复"
        else -> "回执：$result"
    }

    /**
     * 回执后强制刷新一次快照。
     *
     * 仅用于「被控端不回推送增量」的失败/异常回执（TASK_FAIL / ACTION_FAIL / NEED_MANUAL /
     * SERVICE_UNAVAILABLE / DUP_OR_STALE）：下发动作时本地 UI 已乐观翻转，若被控端拒绝而本地不回滚，
     * 显示会长期不一致，因此需拉一次全量校正。
     *
     * 失败回执 / SUCCESS 补拉快照：本地 UI 可能已乐观翻转，或以被控端落地态为准防 push 漏刷。
     *
     * 直接调 sendQuery() 会被它内部的并发守护（queryPendingRid != null 即 return）吞掉：
     * 下发动作时若刚好有查询在途，这次「真正需要的刷新」就丢了。这里先清掉挂起 rid 与超时回调，
     * 保证一定能发出去。延迟一小段时间是因为被控端 START/STOP 是异步启动调度器，
     * ACK 先于状态落地返回，立刻查会拿到执行前的旧状态。
     */
    private fun forceRefreshSnapshot(delayMs: Long = 400L) {
        mainHandler.postDelayed({
            queryPendingRid = null
            mainHandler.removeCallbacks(queryTimeoutRunnable)
            sendQuery()
        }, delayMs)
    }

    /** B5：转发到总览页「最近指令」列表（fragment 自身维护定长队列与渲染） */
    private fun addRecentCommand(label: String, result: String) {
        overviewFragment.addRecentCommand(label, result)
    }

    // ===================== 查询快照 =====================
    /** 发送全量快照查询：默认节流合并高频触发；force=true 豁免（探活语义） */
    fun sendQuery(force: Boolean = false) {
        if (!remoteEnabled) return
        if (device.sessionSecret.isBlank()) {
            // 尚未配对：把“刷新”当作重新发起配对，自愈（被控端令牌在 TTL 内可重复扫码重试）。
            Log.d(TAG, "sendQuery：尚未配对，自动重新发起配对")
            if (mqttClient?.isConnected == true) lifecycleScope.launch { publishPair() }
            return
        }
        if (mqttClient?.isConnected != true) return
        if (queryPendingRid != null) return // 已有待返回查询，避免并发
        val now = System.currentTimeMillis()
        if (!force && now - lastQueryAttemptTs < QUERY_MIN_INTERVAL_MS) {
            Log.d(TAG, "sendQuery 节流跳过（${now - lastQueryAttemptTs}ms < ${QUERY_MIN_INTERVAL_MS}ms）")
            return
        }
        lastQueryAttemptTs = now
        val ts = now
        val rid = UUID.randomUUID().toString()
        queryPendingRid = rid
        lastQuerySentTs = ts
        mainHandler.postDelayed(queryTimeoutRunnable, QUERY_TIMEOUT_MS)
        val sign = MqttSigner.sign(device.sessionSecret, device.deviceId, ts, rid, "snapshot", "", "", MqttPacket.CMD_QUERY)
        val packet = MqttPacket(c = MqttPacket.CMD_QUERY, f = "snapshot", v = null, rid = rid, ts = ts, sign = sign)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                mqttClient?.publish("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/cmd",
                    MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 })
                bumpQuota(1, 0)
                Log.d(TAG, "sendQuery 发布CMD_QUERY rid=$rid deviceId=${device.deviceId}")
                runOnUiThread {
                    if (currentSnapshot == null) overviewFragment.setSnapshotHint(SnapshotHint.WAITING)
                }
            } catch (e: MqttException) {
                e.printStackTrace()
                // 发布失败：不清空 queryPendingRid（交由已排的超时兜底重试），否则会静默卡死。
                Log.d(TAG, "sendQuery 发布失败: ${e.message}")
                runOnUiThread { toastOnce("query_send_fail", "查询发送失败：${e.message}") }
            }
        }
    }

    private fun onQueryTimeout() {
        if (queryPendingRid == null) return
        Log.d(TAG, "onQueryTimeout rid=$queryPendingRid retry=$queryRetryCount")
        // 首次进入探活窗口内允许更多次重试（3s×3），避免公共 broker/被控端冷启动时误判离线
        val maxRetries = if (firstEntryProbeActive) QUERY_PROBE_MAX_RETRIES else QUERY_MAX_RETRIES
        if (queryRetryCount < maxRetries) {
            queryRetryCount++
            queryPendingRid = null
            overviewFragment.setSnapshotHint(SnapshotHint.WAITING, effectiveLastSeenMs(), QUERY_TIMEOUT_MS)
            sendQuery(force = true)
        } else {
            queryPendingRid = null
            val wasFirstEntry = firstEntryProbeActive
            queryRetryCount = 0
            firstEntryProbeActive = false
            overviewFragment.setSnapshotHint(SnapshotHint.FAILED, effectiveLastSeenMs())
            if (wasFirstEntry) {
                Log.w(TAG, "首次探活两次失败 → 判定被控端离线")
                markControlledOffline()
            } else {
                runOnUiThread {
                    val lastSeenMs = effectiveLastSeenMs()
                    val msg = if (lastSeenMs > 0) {
                        val ago = formatLastSeen(lastSeenMs)
                        "被控端未返回快照（最近活跃$ago），请确认被控端在线且已配对，可下拉刷新立即重试"
                    } else {
                        "被控端未返回快照，请确认被控端在线且已配对"
                    }
                    toastOnce("no_snapshot", msg)
                }
            }
        }
    }

    /** 考勤数据回传（resp f=attendance）：验签解密后原位刷新等待弹窗内容为考勤列表 */
    private fun handleAttendanceResp(packet: MqttPacket) {
        if (!verifyResp(packet)) {
            runOnUiThread { toastOnce("attendance_sign_fail", "考勤数据验签失败，已忽略") }
            return
        }
        if (!acceptInboundRid(packet.rid, packet.ts)) return
        val wire = packet.v?.toStringValue() ?: return
        val json = SecretBox.open(device.sessionSecret, wire)
        attendanceResultPending = false
        attendanceTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        attendanceTimeoutRunnable = null
        runOnUiThread {
            // 数据已交付：立即恢复快捷按钮（不依赖 ACK 时序，避免关弹窗后仍置灰）
            overviewFragment.setActionsBusy(false)
            mainHandler.removeCallbacks(actionBusyResetRunnable)
            pendingActionRid = null
            // 原位刷新：等待弹窗内容直接变为考勤列表（弹窗保持，用户手动关闭）
            val sb = StringBuilder()
            runCatching {
                val root = JsonParser.parseString(json).asJsonObject
                val records = root.getAsJsonArray("records")
                records?.forEach { e ->
                    runCatching {
                        val o = e.asJsonObject
                        sb.append("· ").append(o.get("msg")?.asString ?: "")
                            .append("（").append(o.get("time")?.asString ?: "").append("）\n")
                    }
                }
            }
            attendanceWaitTextView?.apply {
                text = sb.toString().trim().ifBlank { "今日暂无考勤记录" }
                textSize = 13f
                gravity = android.view.Gravity.START
                setTextColor(0xFF3A3A3A.toInt())
            }
            attendanceWaitDialog?.setTitle("考勤记录（今日）")
        }
    }

    private fun onSnapshot(payload: String) {
        try {
            val packet = gson.fromJson(payload, MqttPacket::class.java) ?: return
            // AQ 告警回放与快照共用 /resp 主题：按 f 字段分流，互不占用对方的 pending/探活状态
            if (packet.f == "alerts") {
                handleAlertReplay(packet)
                return
            }
            // 考勤记录回传（快捷操作「考勤记录」的弹窗反馈数据）
            if (packet.f == "attendance") {
                handleAttendanceResp(packet)
                return
            }
            Log.d(TAG, "onSnapshot 收到 resp rid=${packet.rid} pending=$queryPendingRid")
            if (packet.rid == queryPendingRid) {
                queryPendingRid = null
                queryRetryCount = 0
                // 探活成功 → 退出首次探活窗口，后续查询超时只提示失败、不再判定离线
                firstEntryProbeActive = false
                mainHandler.removeCallbacks(queryTimeoutRunnable)
            }
            val ok = verifyResp(packet)
            Log.d(TAG, "onSnapshot verifyResp=$ok rid=${packet.rid}")
            if (!ok) {
                runOnUiThread { toastOnce("snapshot_sign_fail", "快照验签失败，已忽略") }
                return
            }
            if (!acceptInboundRid(packet.rid, packet.ts)) return
            val wire = packet.v?.toStringValue() ?: return
            val json = SecretBox.open(device.sessionSecret, wire)
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
                overviewFragment.setProbing(false)
                if (rtt >= 0) deviceFragment.setConnQuality(rtt)
                // 探活/快照拉取成功 = 被控端确实在线 → 点亮绿灯（不再依赖 broker 连接态）
                setConnStatus("在线（已配对）", true)
                refreshCurrentFragment()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread { Toast.makeText(this, "快照解析失败：${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    /** 发送 AQ 告警回放查询（首进页面随探活一起发出）；静默失败，下次进入自然重试 */
    private fun sendAlertQuery() {
        if (!remoteEnabled || device.sessionSecret.isBlank()) return
        if (mqttClient?.isConnected != true) return
        if (alertQueryPendingRid != null) return
        val ts = System.currentTimeMillis()
        val rid = UUID.randomUUID().toString()
        alertQueryPendingRid = rid
        mainHandler.postDelayed(alertQueryTimeoutRunnable, ALERT_QUERY_TIMEOUT_MS)
        val sign = MqttSigner.sign(device.sessionSecret, device.deviceId, ts, rid, "alerts", "", "", Protocol.CMD_ALERT_QUERY)
        val packet = MqttPacket(
            c = Protocol.CMD_ALERT_QUERY, f = "alerts", v = null, rid = rid, ts = ts, sign = sign
        )
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                mqttClient?.publish("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/cmd",
                    MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 })
                bumpQuota(1, 0)
                Log.d(TAG, "sendAlertQuery 发布AQ rid=$rid deviceId=${device.deviceId}")
            } catch (e: MqttException) {
                e.printStackTrace()
                alertQueryPendingRid = null
            }
        }
    }

    /** AQ 回放处理：验签 → 解密 → 逐条入库（aid 幂等去重）；静默合并，不弹窗 */
    private fun handleAlertReplay(packet: MqttPacket) {
        try {
            Log.d(TAG, "handleAlertReplay 收到回放 rid=${packet.rid} pending=$alertQueryPendingRid")
            if (packet.rid == alertQueryPendingRid) {
                alertQueryPendingRid = null
                mainHandler.removeCallbacks(alertQueryTimeoutRunnable)
            }
            if (!verifyResp(packet)) return // verifyResp 按 packet.f 动态参与签名，alerts 字段天然兼容
            if (!acceptInboundRid(packet.rid, packet.ts)) return
            val wire = packet.v?.toStringValue() ?: return
            val json = SecretBox.open(device.sessionSecret, wire)
            val arr = JsonParser.parseString(json).asJsonArray
            var added = 0
            for (e in arr) {
                if (!e.isJsonObject) continue
                if (DeviceAlertInbox.acceptReplayed(this, device, e.asJsonObject) != null) added++
            }
            Log.d(TAG, "AQ 告警回放入库 $added/${arr.size()} 条")
            if (added > 0) {
                runOnUiThread { overviewFragment.refreshAlerts(AlertHistory.load(this, device.deviceId)) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            if (!acceptInboundRid(packet.rid, packet.ts)) return
            val wire = packet.v?.toStringValue() ?: return
            val json = SecretBox.open(device.sessionSecret, wire)
            val delta = JsonParser.parseString(json).asJsonObject
            // 无全量基线时，绝不能拿空 JsonObject 当 base 去合并：那样只会得到一个缺 device/runtime/
            // settings 等根键的「残缺快照」，既会渲染出空白/错误界面，还会被 persistSnapshot 写进本地
            // 缓存污染持久化数据。正确做法是只触发一次全量查询后返回，等 resp 回来再渲染。
            val base = snapshotJson
            if (base == null) {
                Log.d(TAG, "onPush：本地无全量基线，先拉全量快照，本次增量丢弃")
                runOnUiThread { sendQuery() }
                return
            }
            delta.entrySet().forEach { (k, v) -> base.add(k, v) }
            val merged = base.toString()
            currentSnapshot = parseSnapshot(merged)
            persistSnapshot(merged)
            // messageArrived 跑在 Paho 后台线程，setSnapshotHint 直接碰 binding，必须回主线程
            runOnUiThread {
                overviewFragment.setSnapshotHint(SnapshotHint.NONE)
                overviewFragment.setProbing(false)
                // 被控端主动推送增量 = 确证在线 → 点亮绿灯
                setConnStatus("在线（已配对）", true)
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

    /**
     * 处理被控端一次性事件告警（dt/{id}/alert）。
     * 入库与后台监测服务共用 [DeviceAlertInbox]（rid 去重）；本页打开时额外弹窗。
     */
    private fun onAlert(payload: String) {
        try {
            val packet = gson.fromJson(payload, MqttPacket::class.java) ?: return
            if (!verifyAlert(packet)) {
                runOnUiThread { toastOnce("alert_sign_fail", "告警消息验签失败，已忽略") }
                return
            }
            if (!acceptInboundRid(packet.rid, packet.ts)) return
            // 若后台监测已先入库，仍用同一解析结果弹窗；否则由 Inbox 写入历史
            val record = DeviceAlertInbox.accept(this, device, payload)
                ?: AlertHistory.load(this, device.deviceId).firstOrNull { it.rid == packet.rid }
                ?: return
            Log.d(TAG, "收到被控端告警 type=${record.type} battery=${record.battery} -> ${record.msg}")
            runOnUiThread {
                // 手动打卡结果：等待弹窗仍开着 → 内容原位刷新为结果（两步合一步）；
                // 已被用户手动关闭 → 不再打扰，静默入告警历史
                if (record.type == Protocol.ALERT_TYPE_PUNCH_RESULT) {
                    if (punchWaitDialog?.isShowing == true) {
                        finishPunchWaitInPlace(record.msg, record.msg.contains("超时"))
                    }
                    overviewFragment.refreshAlerts(AlertHistory.load(this, device.deviceId))
                    return@runOnUiThread
                }
                showAlertDialog(record)
                overviewFragment.refreshAlerts(AlertHistory.load(this, device.deviceId))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 告警弹窗：用户点击「知道了」关闭；供实时告警与历史记录点击共用 */
    private fun showAlertDialog(record: AlertRecord) {
        UnifiedDialogKit.showConfirm(
            ctx = this,
            title = record.title,
            message = record.msg,
            confirmText = "知道了",
            cancelText = null,
            icon = UnifiedDialogKit.IconType.WARNING
        )
    }

    private fun verifyAlert(packet: MqttPacket): Boolean {
        val session = device.sessionSecret
        if (session.isBlank()) return false
        val json = packet.v?.toStringValue() ?: return false
        val expected = MqttSigner.sign(session, device.deviceId, packet.ts, packet.rid, packet.f, "s", json, Protocol.CMD_ALERT)
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
                // 需求 1：新增 string 类型（消息渠道文本/掩码字段），不能再无脑 asInt，否则解析直接抛异常
                value = when (type) {
                    "bool" -> o.get("value").asBoolean
                    "string" -> o.get("value").asString
                    // time / int 等数值型：当日分钟数或其它 int
                    else -> runCatching { o.get("value").asInt }.getOrDefault(0)
                },
                writable = o.get("writable")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                min = o.get("min")?.takeIf { it.isJsonPrimitive }?.asInt,
                max = o.get("max")?.takeIf { it.isJsonPrimitive }?.asInt,
                step = o.get("step")?.takeIf { it.isJsonPrimitive }?.asInt,
                options = o.getAsJsonArray("options")?.mapNotNull { if (it.isJsonPrimitive) it.asInt else null }
                    ?.takeIf { it.isNotEmpty() }
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
                name = o.get("name")?.asString ?: "",
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
        // 已打卡天数 = 成功 + 超时（被控端按日期聚合，status 为 success/timeout）
        val punchedDays = days.count { it.status == "success" || it.status == "timeout" }
val calendar = CalendarSnapshot(
            punched = punchedDays.toString(),
            scheduled = calendarObj?.get("scheduled")?.getAsString() ?: "0",
            missed = calendarObj?.get("missed")?.getAsString() ?: "0",
            recentPunch = calendarObj?.get("recentPunch")?.getAsString() ?: "—",
            today = calendarObj?.get("today")?.getAsString() ?: "—",
            days = days
        )
        // B5：解析电池采样序列（runtime.batterySeries 为数组，已在上文 mapOf 中被安全跳过）
        val batterySeries = mutableListOf<BatteryPoint>()
        root.getAsJsonObject("runtime")?.getAsJsonArray("batterySeries")?.forEach {
            val o = it.asJsonObject
            val ts = o.get("ts")?.takeIf { e -> e.isJsonPrimitive }?.asLong ?: 0L
            val level = o.get("level")?.takeIf { e -> e.isJsonPrimitive }?.asInt ?: -1
            val charging = o.get("charging")?.takeIf { e -> e.isJsonPrimitive }?.asBoolean ?: false
            if (level >= 0) batterySeries.add(BatteryPoint(ts, level, charging))
        }
        val history = mutableListOf<HistoryItem>()
        root.getAsJsonArray("history")?.forEach {
            val o = it.asJsonObject
            val raw = o.get("result").asString
            // 成功/超时统一显示「打卡·xx」，兼容被控端已/未加前缀两种格式
            val result = when {
                raw.contains("成功") -> "打卡·成功"
                raw.contains("超时") -> "打卡·超时"
                else -> raw
            }
            history.add(HistoryItem(o.get("time").asString, result))
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
        if (mqttClient?.isConnected != true) {
            Toast.makeText(this, "MQTT 未连接，设置未下发", Toast.LENGTH_SHORT).show()
            refreshCurrentFragment()
            return
        }
        lastCommandLabel = when (field) {
            Protocol.FIELD_UPDATE_HOLIDAY -> "更新节假日"
            else -> "修改设置"
        }
        val ts = System.currentTimeMillis()
        val rid = UUID.randomUUID().toString()
        rememberCommandLabel(rid, lastCommandLabel, field, value)
        val (type, vStr) = when (value) {
            is PacketValue.BooleanValue -> "b" to value.b.toString()
            is PacketValue.IntValue -> "i" to value.i.toString()
            is PacketValue.StringValue -> "s" to value.s
        }
        val sign = MqttSigner.sign(device.sessionSecret, device.deviceId, ts, rid, field, type, vStr, "U")
        val packet = MqttPacket("U", field, value, rid, ts, sign)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                mqttClient?.publish("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/cmd",
                    MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 })
                bumpQuota(1, 0)
            } catch (e: MqttException) {
                e.printStackTrace()
                pendingCommands.remove(rid)
                runOnUiThread {
                    Toast.makeText(this@DeviceControlActivity, "指令发送失败", Toast.LENGTH_SHORT).show()
                    refreshCurrentFragment()
                }
            }
        }
    }

    private fun sendTask(action: String, time: String, oldTime: String? = null, name: String? = null) {
        if (!remoteEnabled) {
            Toast.makeText(this, "MQTT 连接已关闭，无法编辑任务", Toast.LENGTH_SHORT).show(); return
        }
        if (device.sessionSecret.isBlank()) {
            Toast.makeText(this, "尚未完成配对，无法下发指令", Toast.LENGTH_SHORT).show(); return
        }
        // 同 sendUpdate：断线时 publish 会静默 no-op，用户以为任务已增删改，实际未下发
        if (mqttClient?.isConnected != true) {
            Toast.makeText(this, "MQTT 未连接，任务操作未下发", Toast.LENGTH_SHORT).show()
            refreshCurrentFragment()
            return
        }
        lastCommandLabel = when (action) {
            "add" -> "新增任务"
            "update" -> "修改任务"
            else -> "删除任务"
        }
        val obj = com.google.gson.JsonObject()
        obj.addProperty("action", action)
        obj.addProperty("time", time)
        if (name != null) obj.addProperty("name", name)
        if (oldTime != null) obj.addProperty("oldTime", oldTime)
        val json = obj.toString()
        val ts = System.currentTimeMillis()
        val rid = UUID.randomUUID().toString()
        rememberCommandLabel(rid, lastCommandLabel)
        val sign = MqttSigner.sign(device.sessionSecret, device.deviceId, ts, rid, "task", "s", json, MqttPacket.CMD_TASK)
        val packet = MqttPacket(c = MqttPacket.CMD_TASK, f = "task", v = PacketValue.StringValue(json), rid = rid, ts = ts, sign = sign)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                mqttClient?.publish("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/cmd",
                    MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 })
                bumpQuota(1, 0)
            } catch (e: MqttException) {
                e.printStackTrace()
                pendingCommands.remove(rid)
                runOnUiThread {
                    Toast.makeText(this@DeviceControlActivity, "指令发送失败", Toast.LENGTH_SHORT).show()
                    refreshCurrentFragment()
                }
            }
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
        val label = when (action) {
            MqttPacket.ACTION_PUNCH -> "打卡"
            MqttPacket.ACTION_START -> "执行任务"
            MqttPacket.ACTION_STOP -> "终止任务"
            MqttPacket.ACTION_ATTENDANCE -> "考勤记录"
            MqttPacket.ACTION_SCREENSHOT -> "截屏"
            else -> action
        }
        // 必须在 publish 之前登记：ACK 可能在 publish 返回前就到达
        lastCommandLabel = label
        rememberCommandLabel(rid, label)
        pendingActionRid = rid
        val sign = MqttSigner.sign(device.sessionSecret, device.deviceId, ts, rid, action, "", "", MqttPacket.CMD_ACTION)
        val packet = MqttPacket(MqttPacket.CMD_ACTION, action, null, rid, ts, sign)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                mqttClient?.publish("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/cmd",
                    MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 })
                bumpQuota(1, 0)
                // 注意：这里刻意不调 sendQuery()。
                // 早前版本在此直接查询，反而制造了竞态：它会占用 sendQuery 的并发槽位
                // (queryPendingRid)，使随后 ACK 里那次「真正有效」的刷新被并发守护吞掉；
                // 且这次查询的快照是被控端收到 query 那一刻生成的，早于 startTask 落地 = 旧状态。
                // 正确路径只有一条：被控端 START/STOP 本就会 publishPush(statuses/runtime/tasks)
                // 主动推增量，控制端 onPush 按 delta 覆盖合并本地缓存并刷新 UI，不再额外查全量。
                runOnUiThread {
                    Toast.makeText(this@DeviceControlActivity, "已发送动作：$label", Toast.LENGTH_SHORT).show()
                    // 结果型操作弹等待反馈窗（结果回传时关闭等待窗并弹结果）
                    when (action) {
                        MqttPacket.ACTION_PUNCH -> showPunchWaitingDialog()
                        MqttPacket.ACTION_ATTENDANCE -> showAttendanceWaitingDialog()
                    }
                }
            } catch (e: MqttException) {
                e.printStackTrace()
                pendingCommands.remove(rid)
                runOnUiThread {
                    if (pendingActionRid == rid) pendingActionRid = null
                    overviewFragment.setActionsBusy(false)
                    mainHandler.removeCallbacks(actionBusyResetRunnable)
                    Toast.makeText(this@DeviceControlActivity, "动作指令发送失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ===================== 快捷操作弹窗反馈（手动打卡/考勤记录） =====================
    private var punchWaitDialog: androidx.appcompat.app.AlertDialog? = null
    private var punchWaitTextView: android.widget.TextView? = null
    private var punchWaitRemaining = 0
    private var punchResultPending = false

    private val punchWaitTick = object : Runnable {
        override fun run() {
            if (punchWaitRemaining <= 0) {
                // 倒计时归零：原位转为「等待超时」，弹窗保持展示——结果晚到仍可原位刷新；
                // 同时恢复快捷按钮（结果已超时，允许用户重试）
                overviewFragment.setActionsBusy(false)
                mainHandler.removeCallbacks(actionBusyResetRunnable)
                punchWaitTextView?.text = "等待超时，未收到被控端打卡结果"
                punchWaitTextView?.setTextColor(0xFFB45309.toInt())
                return
            }
            punchWaitRemaining--
            punchWaitTextView?.text = "剩余 ${punchWaitRemaining}s"
            mainHandler.postDelayed(this, 1000L)
        }
    }

    /** 用户手动关闭等待弹窗：清除等待态并恢复快捷按钮（关闭弹窗 = 不再等待，按钮立即可操作） */
    private fun punchWaitManualClosed() {
        punchResultPending = false
        mainHandler.removeCallbacks(punchWaitTick)
        overviewFragment.setActionsBusy(false)
        mainHandler.removeCallbacks(actionBusyResetRunnable)
        pendingActionRid = null
    }

    /** 用户手动关闭考勤等待弹窗：清除等待态并恢复快捷按钮 */
    private fun attendanceWaitClosed() {
        attendanceResultPending = false
        attendanceTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        attendanceTimeoutRunnable = null
        overviewFragment.setActionsBusy(false)
        mainHandler.removeCallbacks(actionBusyResetRunnable)
        pendingActionRid = null
    }

    /** 手动打卡等待弹窗：展示按任务超时时间计算的倒计时，可手动关闭（关闭不影响结果回传弹窗） */
    private fun showPunchWaitingDialog() {
        // 超时基准 = 被控端打卡超时时长（快照设置 ot）+ 执行开销余量
        val ot = currentSnapshot?.settings?.firstOrNull { it.key == "ot" }?.value?.toString()?.toIntOrNull() ?: 30
        punchWaitRemaining = (ot + 15).coerceIn(30, 180)
        punchResultPending = true
        val tv = android.widget.TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, (resources.displayMetrics.density * 12).toInt(), 0,
                (resources.displayMetrics.density * 12).toInt())
            textSize = 15f
            text = "剩余 ${punchWaitRemaining}s"
        }
        punchWaitTextView = tv
        punchWaitDialog = UnifiedDialogKit.showForm(
            ctx = this,
            contentView = tv,
            title = "手动打卡",
            message = "指令已下发，等待被控端执行…",
            positiveText = "关闭",
            negativeText = null,
            cancelable = true,
            onCancel = { punchWaitManualClosed(); true },
            onConfirm = { punchWaitManualClosed(); true }
        )
        mainHandler.removeCallbacks(punchWaitTick)
        mainHandler.postDelayed(punchWaitTick, 1000L)
    }

    /** 打卡结果原位刷新：等待弹窗内容直接变为结果（标题同步改「打卡结果」），两步合一步 */
    private fun finishPunchWaitInPlace(msg: String, isTimeout: Boolean) {
        mainHandler.removeCallbacks(punchWaitTick)
        punchResultPending = false
        // 结果已交付（或超时），无论 ACK 是否及时命中都立即恢复快捷按钮
        overviewFragment.setActionsBusy(false)
        mainHandler.removeCallbacks(actionBusyResetRunnable)
        pendingActionRid = null
        punchWaitTextView?.apply {
            text = msg
            setTextColor(if (isTimeout) 0xFFB45309.toInt() else 0xFF1B7A43.toInt())
        }
        punchWaitDialog?.setTitle("打卡结果")
    }

    private var attendanceWaitDialog: androidx.appcompat.app.AlertDialog? = null
    private var attendanceWaitTextView: android.widget.TextView? = null
    private var attendanceResultPending = false
    private var attendanceTimeoutRunnable: Runnable? = null

    /** 考勤记录等待弹窗：resp 数据通常秒级返回，15s 超时兜底；可手动关闭（关闭后数据不再弹窗） */
    private fun showAttendanceWaitingDialog() {
        attendanceResultPending = true
        val tv = android.widget.TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, (resources.displayMetrics.density * 12).toInt(), 0,
                (resources.displayMetrics.density * 12).toInt())
            text = "指令已下发，等待考勤数据…"
            textSize = 15f
        }
        attendanceWaitTextView = tv
        attendanceWaitDialog = UnifiedDialogKit.showForm(
            ctx = this,
            contentView = tv,
            title = "考勤记录",
            positiveText = "关闭",
            negativeText = null,
            cancelable = true,
            onCancel = { attendanceWaitClosed(); true },
            onConfirm = { attendanceWaitClosed(); true }
        )
        attendanceTimeoutRunnable = Runnable {
            attendanceResultPending = false
            // 超时：恢复快捷按钮（允许重试），弹窗原位转超时提示
            overviewFragment.setActionsBusy(false)
            mainHandler.removeCallbacks(actionBusyResetRunnable)
            pendingActionRid = null
            attendanceWaitTextView?.text = "等待超时，未收到考勤数据"
            attendanceWaitTextView?.setTextColor(0xFFB45309.toInt())
        }
        mainHandler.postDelayed(attendanceTimeoutRunnable!!, 15_000L)
    }

    /**
     * 首次进入详情页且已配对时主动拉一次快照：以 snapshot 查询作探活，
     * 超时按重试上限多次重试，全部失败才判定离线。整个生命周期只主动拉这一次全量。
     */
    private fun startFirstEntryProbe() {
        if (!remoteEnabled || device.sessionSecret.isBlank() || !device.bound) return
        if (firstEntryProbeStarted) return // 整个生命周期只主动拉一次
        firstEntryProbeStarted = true
        // HB 快速路径：心跳新鲜直接判在线，跳过 3s×3 探活离线判定；
        // 会话有效性由下方快照 QUERY 验签兜底（SIGN_FAIL → 解绑）。HB 过期/缺失走常规探活。
        val hbFresh = OnlineStateCache.hbAgeMs(device.deviceId)?.let { it < OnlineStateCache.HB_FRESH_MS } == true
        if (hbFresh) {
            firstEntryProbeActive = false // 走常规查询重试，不触发「探活两次失败离线判定」
            Log.d(TAG, "首次进入：HB 新鲜，直接判在线（deviceId=${device.deviceId}），快照拉取中")
            runOnUiThread {
                setConnStatus("在线（HB）", true)
                overviewFragment.setProbing(false)
            }
        } else {
            firstEntryProbeActive = true
            queryRetryCount = 0
            Log.d(TAG, "首次进入：HB 缺失/过期，常规探活 + 快照拉取（3s×3，deviceId=${device.deviceId}）")
            // 只有本次真正发出探活才置「探活中」(琥珀)。connectComplete 与 connect() 返回分支
            // 都会调到这，若在上层无条件置琥珀，后到者会把已确认成功的绿灯覆盖成黄、且此处因
            // firstEntryProbeStarted=true 直接 return 不再发探活 → 色灯卡黄。集中在探活真正发起点设置。
            runOnUiThread { setConnStatus("探活中…", false) }
            runOnUiThread { overviewFragment.setProbing(true) }
        }
        // 首次进入探活是「必须真正发出」的查询，豁免节流（force）
        sendQuery(force = true)
        // 同时拉一次近期告警回放（AQ）：补齐本端离线期间漏收的告警。
        // 静默进行：失败不提示，回放条目按 aid 幂等去重，重复进入页面重复拉取也无副作用。
        sendAlertQuery()
    }

    /**
     * 判定被控端离线后的处理（首次探活两次失败触发）：
     * 1) 状态切到「设备离线（无响应）」 — 色灯变红；
     * 2) 禁用快捷动作按钮（避免给一台失联的设备继续下指令）；
     * 3) 弹 Snackbar 提示用户该设备可能已关机/网络断开 → 点击重试 / 重新连接。
     */
    /** 最近活跃时间：取「本连接收到消息」与「presence HB 心跳」中较新者（HB 由列表/后台保温，跨页面也准确） */
    private fun effectiveLastSeenMs(): Long = maxOf(
        lastActivityMs,
        OnlineStateCache.hbAgeMs(device.deviceId)?.let { System.currentTimeMillis() - it } ?: 0L
    )

    private fun markControlledOffline() {
        if (recentlyMarkedOffline) return
        recentlyMarkedOffline = true
        val lastSeenMs = effectiveLastSeenMs()
        val lastSeenText = if (lastSeenMs > 0) formatLastSeen(lastSeenMs) else "未知"
        val snackbarMsg = if (lastSeenMs > 0) {
            "被控端无响应（最近活跃$lastSeenText，可能临时离线），已重试 ${QUERY_PROBE_MAX_RETRIES + 1} 次均未成功，点击「重新连接」手动重试"
        } else {
            "被控端无响应（疑似离线），点击「重新连接」重新连接"
        }
        runOnUiThread {
            setConnStatus(
                "设备离线（无响应）", false,
                "被控端未响应，可能已经离线/解绑。\n可尝试给被控端发送通知指令（如「DT#状态查询」）"
            )
            overviewFragment.setActionsEnabled(false)
            Snackbar.make(
                binding.root,
                snackbarMsg,
                Snackbar.LENGTH_LONG
            ).setAction("重新连接") {
                overviewFragment.setRefreshing(false)
                retryConnection()
            }.show()
        }
    }

    /** 被控端复活：从误判的离线态恢复（收到被控端任意消息时由 handleIncoming 触发） */
    private fun recoverFromFalseOffline() {
        if (!recentlyMarkedOffline) return
        recentlyMarkedOffline = false
        runOnUiThread {
            setConnStatus("已连接（已配对）", true)
            overviewFragment.setActionsEnabled(true)
            overviewFragment.setStaleBanner(false)
            toastOnce("recover_offline", "被控端恢复响应")
        }
    }

    // ===================== 解绑 =====================
    private fun confirmUnbind() {
        showDestructiveConfirm(
            this,
            title = "解绑设备",
            message = "确定从控制端移除该设备？被控端将收到解绑通知并清除绑定。本机 MQTT 配置不受影响。",
            confirmText = "解绑"
        ) { doUnbind() }
    }

    private fun doUnbind() {
        val ts = System.currentTimeMillis()
        val rid = UUID.randomUUID().toString()
        val session = device.sessionSecret
        val sign = if (session.isNotBlank()) {
            MqttSigner.sign(session, device.deviceId, ts, rid, "", "s", "", MqttPacket.CMD_UNBOUND)
        } else ""
        val packet = MqttPacket(
            c = MqttPacket.CMD_UNBOUND,
            f = "",
            v = PacketValue.StringValue(""),
            rid = rid,
            ts = ts,
            sign = sign
        )
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

    /** 入站 rid 去重 + ±120s 时钟窗 */
    private fun acceptInboundRid(rid: String, ts: Long): Boolean {
        if (rid.isBlank()) return true
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - ts) > 120_000L) {
            Log.w(TAG, "入站消息超时钟窗 rid=$rid")
            return false
        }
        if (recentInboundRids.contains(rid)) {
            Log.w(TAG, "入站消息重放 rid=$rid")
            return false
        }
        recentInboundRids.addLast(rid)
        while (recentInboundRids.size > 200) recentInboundRids.removeFirst()
        return true
    }

    // ===================== 连接状态 =====================
    fun setConnStatus(text: String, online: Boolean) {
        setConnStatus(text, online, null)
    }

    fun setConnStatus(text: String, online: Boolean, bannerText: String?) {
        overviewFragment.setConnStatusText(text, online)
        // B4：离线横幅（连接中/探活中过渡态不显示）+ 断连禁用控制按钮
        val showBanner = remoteEnabled && !online && !text.contains("连接中") && !text.contains("探活中")
        binding.offlineBanner.visibility = if (showBanner) View.VISIBLE else View.GONE
        if (bannerText != null) {
            binding.tvOfflineBanner.text = bannerText
        } else if (showBanner) {
            binding.tvOfflineBanner.text = "设备离线，数据可能不是最新"
        }
        overviewFragment.setActionsEnabled(online)
    }

    /** D2：动作回执 Snackbar（替代 Toast，置于页面底部更醒目） */
    private fun showActionSnackbar(result: String) {
        val friendly = ackFriendly(result)
        Snackbar.make(binding.root, "指令回执：$friendly", Snackbar.LENGTH_SHORT).show()
    }

    /**
     * D5：重新发起配对（控制端「重新配对」入口）。
     * - 已配对但连接异常：已连接则重发 P，否则重连后再由流程发起。
     * - 解绑后（device.bound=false）：密钥已失效，MQTT 自动配对不可用，
     *   跳回 MainActivity 的扫码/剪贴板导入流程获取新 pairingToken。
     */
    fun retryPair() {
        if (!device.bound) {
            // 解绑后：跳回设备列表页，由用户扫码或剪贴板导入重新配对
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("trigger_add_device", true)
            startActivity(intent)
            finish()
            return
        }
        if (mqttClient?.isConnected == true) {
            lifecycleScope.launch { publishPair() }
        } else {
            setConnStatus("连接中…", false)
            initMqtt()
        }
    }

    // ===================== 重新配对（原「重新连接」按钮） =====================

    /** 「重新配对」按钮：弹添加设备底部弹窗（扫码 / 剪贴板），复用被控端生成的绑定二维码重新配对当前设备 */
    private fun showRePairSheet() {
        val sheet = BottomSheetDialog(this)
        val sheetBinding = BottomSheetAddDeviceBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)
        sheetBinding.sheetScan.setOnClickListener {
            sheet.dismiss()
            rePairLauncher.launch(
                ScanOptions()
                    .setCaptureActivity(ScanActivity::class.java)
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            )
        }
        sheetBinding.sheetClipboard.setOnClickListener {
            sheet.dismiss()
            rePairFromClipboard()
        }
        sheetBinding.sheetCancel.setOnClickListener { sheet.dismiss() }
        sheet.show()
    }

    /** 重新配对扫码结果：解析绑定二维码 → 更新当前设备绑定信息并重新配对 */
    private val rePairLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        try {
            val payload = Gson().fromJson(contents, BindingPayload::class.java)
            if (payload.broker.isNotBlank() && payload.deviceId.isNotBlank()) {
                applyRePair(payload)
            } else {
                Toast.makeText(this, "二维码缺少必要字段（broker/deviceId）", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "无效的二维码格式", Toast.LENGTH_SHORT).show()
        }
    }

    /** 重新配对剪贴板导入：读取被控端复制到剪贴板的绑定 JSON，解析后重新配对当前设备 */
    private fun rePairFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "剪贴板为空，请先在被控端「远程控制」页点「生成绑定二维码」（会自动复制到剪贴板）", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val payload = Gson().fromJson(text, BindingPayload::class.java)
            if (payload.broker.isNotBlank() && payload.deviceId.isNotBlank()) {
                applyRePair(payload)
            } else {
                Toast.makeText(this, "剪贴板内容缺少必要字段（broker/deviceId）", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "剪贴板内容不是有效的配对信息", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 应用重新配对：用新绑定二维码更新当前设备（清空会话密钥与绑定态 → 走未配对流程重新握手），
     * 然后重建 MQTT 连接自动发起配对。
     */
    private fun applyRePair(payload: BindingPayload) {
        lifecycleScope.launch(Dispatchers.IO) {
            val updated = device.copy(
                broker = payload.broker,
                ctlUser = payload.ctlUser,
                ctlPass = payload.ctlPass,
                sessionSecret = "",
                pairingToken = payload.pairingToken,
                bound = false
            )
            db.deviceDao().update(updated)
            withContext(Dispatchers.Main) {
                device = updated
                // 重新配对载荷落地：默认自动重开 MQTT 连接开关（解绑时被持久化关闭，
                // 不重开则 initMqtt 兜底守卫会拦截、配对永远无法发起）
                remoteEnabled = true
                prefs.edit().putBoolean("remote_enabled_${device.deviceId}", true).apply()
                Toast.makeText(this@DeviceControlActivity, "已更新配对信息，正在重新配对…", Toast.LENGTH_SHORT).show()
                setConnStatus("连接中…", false)
                disconnectMqtt()
                initMqtt()
            }
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
            if (saved && mqttClient == null && device.bound) {
                setConnStatus("连接中…", false)
                initMqtt()
            } else if (!saved) {
                setConnStatus("MQTT 已关闭", false)
                overviewFragment.setSnapshotHint(SnapshotHint.DISABLED)
            }
        }
        // 解绑态：确保控件保持禁用（避免 onResume 误恢复）；任务/设置页同步置灰，设备页切「删除设备」
        if (!device.bound) {
            applyUnboundUiState()
            setConnStatus(
                if (forceUnbound) "已被强制解绑" else "已解绑",
                false,
                if (forceUnbound) "设备已被强制解绑，请重新配对绑定" else "设备已解绑，请重新配对绑定"
            )
        } else {
            applyBoundUiState()
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
        // 解绑后 device.bound=false，不自动重连，需用户主动点「重新配对」
        if (remoteEnabled && mqttClient == null && device.bound) {
            setConnStatus("连接中…", false)
            initMqtt()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(PAIR_TIMEOUT_RUNNABLE)
        mainHandler.removeCallbacks(queryTimeoutRunnable)
        // 清理快捷操作反馈弹窗与计时（防泄漏/防残留回调改写 UI）
        punchResultPending = false
        attendanceResultPending = false
        punchWaitDialog?.dismiss(); punchWaitDialog = null
        attendanceWaitDialog?.dismiss(); attendanceWaitDialog = null
        mainHandler.removeCallbacks(punchWaitTick)
        attendanceTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        try { mqttClient?.disconnect(); mqttClient?.close() } catch (_: Exception) {}
    }

    private fun formatLastSeen(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        if (diff < 60_000) return "${diff / 1000} 秒前"
        if (diff < 3_600_000) return "${diff / 60_000} 分钟前"
        if (diff < 86_400_000) return "${diff / 3_600_000} 小时前"
        return "${diff / 86_400_000} 天前"
    }
}
