package com.yample.daily.controller

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.yample.mqttprotocol.MqttPacket
import com.yample.mqttprotocol.MqttQuota
import com.yample.daily.controller.databinding.FragmentOverviewBinding
import com.yample.daily.controller.databinding.RowInfoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.ArrayDeque

enum class SnapshotHint { NONE, WAITING, FAILED, DISABLED }

class OverviewFragment : Fragment(), SnapshotFragment {

    private var _binding: FragmentOverviewBinding? = null
    private val binding get() = _binding!!
    private var snapshot: DeviceSnapshot? = null
    var onRefreshClick: (() -> Unit)? = null
    var onRemoteToggle: ((Boolean) -> Unit)? = null
    var onAction: ((String) -> Unit)? = null
    var onRePairClick: (() -> Unit)? = null
    var onRetryClick: (() -> Unit)? = null
    /** 对称按钮：右侧「重新连接」——断开并重建 MQTT 连接 */
    var onReconnectClick: (() -> Unit)? = null
    /** 下拉刷新 3 秒冷却：避免频繁下拉导致的重复刷新 */
    private var lastRefreshMs = 0L
    /** 下拉刷新延迟 3 秒触发：防止下拉误触，保持下拉 3 秒才真正发起刷新 */
    private val swipeRefreshRunnable = Runnable { triggerRefresh() }
    private val swipeRefreshDelayMs = 3000L
    private var remoteInitializing = false
    /** 视图就绪前暂存的连接状态文案（onCreate 同步调用可能早于 onCreateView） */
    private var pendingConnStatus: Pair<String, Boolean>? = null
    /** 状态灯：最后状态文案与 online 标记，供 render 重绘时复用 */
    private var lastConnText = ""
    private var lastConnOnline = false
    private var appliedStatusKey: String? = null
    private var pulseAnim: ValueAnimator? = null
    /** D2：快捷动作是否处于下发中（防重复点击 + 加载态） */
    private var _actionsBusy = false
    private var _actionsOnline = true
    /** B5：连接质量(RTT) 文本，跨 render 保留 */
    private var connQuality: String = "—"
    /** B5：最近指令回执定长队列（最多 5 条），跨会话持久化 */
    private val recentCmds = ArrayDeque<Pair<String, String>>()
    private val MAX_RECENT = 5
    private var recentCmdsKey: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOverviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recentCmdsKey = "recent_cmds_${requireActivity().intent.getStringExtra("deviceId") ?: "unknown"}"
        restoreRecentCmds()
        binding.swipeRefresh.setOnRefreshListener { onSwipeRefresh() }
        binding.btnRefresh.setOnClickListener { triggerRefresh() }
        binding.btnReconnect.setOnClickListener { onReconnectClick?.invoke() }
        binding.switchRemote.setOnCheckedChangeListener { _, isChecked ->
            if (!remoteInitializing) onRemoteToggle?.invoke(isChecked)
        }
        binding.btnActPunch.setOnClickListener { onAction?.invoke(MqttPacket.ACTION_PUNCH) }
        binding.btnActStart.setOnClickListener { onAction?.invoke(MqttPacket.ACTION_START) }
        binding.btnActStop.setOnClickListener { onAction?.invoke(MqttPacket.ACTION_STOP) }
        binding.btnActAttendance.setOnClickListener { onAction?.invoke(MqttPacket.ACTION_ATTENDANCE) }
        // C1：折叠卡（连接信息默认折叠 / 设备信息默认折叠），状态跨会话保留
        // 需求 4：原「MQTT 状态」卡片已并入「连接信息」，消息统计随连接信息一起展开
        val prefs = requireActivity().getSharedPreferences("remote_ctrl", android.content.Context.MODE_PRIVATE)
        val connCollapsed = prefs.getBoolean("collapse_conn", true)
        val devCollapsed = prefs.getBoolean("collapse_deviceinfo", true)
        applyCollapse(binding.bodyConn, binding.ivChevronConn, !connCollapsed)
        applyCollapse(binding.bodyDevice, binding.ivChevronDevice, !devCollapsed)
        binding.btnToggleConn.setOnClickListener { toggleSection(binding.bodyConn, binding.ivChevronConn, "collapse_conn") }
        binding.btnToggleDevice.setOnClickListener { toggleSection(binding.bodyDevice, binding.ivChevronDevice, "collapse_deviceinfo") }
        // D5：未配对时显示「重新配对」入口
        binding.btnRePair.setOnClickListener { onRePairClick?.invoke() }
        snapshot?.let { render(it) }
        pendingConnStatus?.let { (text, online) ->
            pendingConnStatus = null
            setConnStatusText(text, online)
        }
    }

    fun setRemoteEnabled(on: Boolean) {
        if (_binding == null) return
        remoteInitializing = true
        binding.switchRemote.isChecked = on
        remoteInitializing = false
    }

    fun showQuota(stats: MqttQuota.Stats) {
        if (_binding == null) return
        binding.tvQuotaText.text = "已用 ${stats.total}"
        binding.progressQuota.visibility = View.GONE
        binding.tvQuotaHint.text = "已发送 ${stats.sent} / 已接收 ${stats.received}"

        binding.layoutQuotaDetails.removeAllViews()
        val rows = listOf(
            "已累计连接" to MqttQuota.formatDuration(stats.totalConnectedMs),
            "本次连接" to MqttQuota.formatDuration(stats.sessionConnectedMs),
            "已发送消息" to "${stats.sent} 条",
            "已接收消息" to "${stats.received} 条",
            "消息总计" to "${stats.total} 条"
        )
        rows.forEach { (label, value) ->
            val row = RowInfoBinding.inflate(LayoutInflater.from(requireContext()))
            row.tvRowLabel.text = label
            row.tvRowValue.text = value
            binding.layoutQuotaDetails.addView(row.root)
        }
    }

    fun setRefreshing(refreshing: Boolean) {
        if (_binding == null) return
        binding.swipeRefresh.isRefreshing = refreshing
    }

    /** 下拉刷新：延迟 3 秒（swipeRefreshDelayMs）才真正触发，防止误触/误拉 */
    private fun onSwipeRefresh() {
        binding.swipeRefresh.postDelayed(swipeRefreshRunnable, swipeRefreshDelayMs)
    }

    /** 取消待执行的延迟下拉刷新（视图销毁时调用，避免泄漏/误触发） */
    private fun cancelSwipeRefresh() {
        binding.swipeRefresh.removeCallbacks(swipeRefreshRunnable)
    }

    /** 下拉/点击刷新：3 秒内重复触发会被忽略（冷却），避免频繁刷新把被控端打爆 */
    private fun triggerRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshMs < 3000) {
            binding.swipeRefresh.isRefreshing = false
            return
        }
        lastRefreshMs = now
        onRefreshClick?.invoke()
    }

    fun setSnapshotHint(hint: SnapshotHint) {
        if (_binding == null) return
        when (hint) {
            SnapshotHint.NONE -> binding.tvSnapshotHint.visibility = View.GONE
            SnapshotHint.WAITING -> {
                binding.tvSnapshotHint.visibility = View.VISIBLE
                binding.tvSnapshotHint.text = "正在等待被控端返回快照…"
                binding.tvSnapshotHint.setTextColor(requireContext().getColor(R.color.md_onSurfaceVariant))
            }
            SnapshotHint.FAILED -> {
                binding.tvSnapshotHint.visibility = View.VISIBLE
                binding.tvSnapshotHint.text = "未获取到快照：请确认被控端在线且已配对，或点击「刷新实时数据」重试"
                binding.tvSnapshotHint.setTextColor(requireContext().getColor(R.color.md_error))
            }
            SnapshotHint.DISABLED -> {
                binding.tvSnapshotHint.visibility = View.VISIBLE
                binding.tvSnapshotHint.text = "MQTT 连接已关闭：开启后可获取被控端实时状态"
                binding.tvSnapshotHint.setTextColor(requireContext().getColor(R.color.md_onSurfaceVariant))
            }
        }
    }

    override fun refresh(snapshot: DeviceSnapshot) {
        this.snapshot = snapshot
        if (isAdded && _binding != null) render(snapshot)
    }

    fun setConnStatusText(text: String, online: Boolean) {
        lastConnText = text
        lastConnOnline = online
        if (!isAdded || _binding == null) {
            pendingConnStatus = text to online
            return
        }
        applyConnStatus()
    }

    /** 状态灯：用颜色 + 呼吸脉冲表达连接状态，取代原「已连接（已配对）」式冗长文案 */
    private fun applyConnStatus() {
        if (!isAdded || _binding == null) return
        val ctx = requireContext()
        val key = "$lastConnText|$lastConnOnline"
        if (appliedStatusKey == key) return   // 避免 render 频繁重绘时反复重启脉冲动画
        appliedStatusKey = key
        val (color, pulse) = resolveStatus(lastConnText, lastConnOnline, ctx)
        binding.dotStatus.setBackgroundResource(R.drawable.bg_dot_offline)
        binding.dotStatus.background.setTint(color)
        binding.dotHalo.setBackgroundResource(R.drawable.bg_dot_offline)
        binding.dotHalo.background.setTint(color)
        // D6：连接失败时状态灯可点击重试
        val failed = lastConnText == "连接失败"
        binding.dotStatus.isClickable = failed
        binding.dotStatus.setOnClickListener { if (failed) onRetryClick?.invoke() }
        binding.dotHalo.isClickable = failed
        binding.dotHalo.setOnClickListener { if (failed) onRetryClick?.invoke() }
        pulseAnim?.cancel()
        pulseAnim = null
        if (pulse) {
            val anim = ValueAnimator.ofFloat(0.14f, 0.5f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { binding.dotHalo.alpha = it.animatedValue as Float }
            }
            anim.start()
            pulseAnim = anim
        } else {
            binding.dotHalo.alpha = 0.3f
        }
    }

    /** 状态 → (颜色, 是否脉冲)：绿=在线已配对 / 琥珀=配对中·未配对·连接中 / 红=失败·错误·无心跳 / 灰=已解绑·离线·连接中 */
    private fun resolveStatus(text: String, online: Boolean, ctx: android.content.Context): Pair<Int, Boolean> {
        val green = Color.parseColor("#16A34A")
        val amber = Color.parseColor("#F59E0B")
        val red = ctx.getColor(R.color.md_error)
        val gray = ctx.getColor(R.color.md_outline)
        return when {
            text == "连接失败" -> red to false
            text.contains("已解绑") || text.contains("已被强制解绑") -> gray to false
            text.contains("订阅被拒") || text.contains("配对发送失败") -> red to false
            // 心跳探活检测到的被控端离线：红灯 + 不脉冲（不像「连接中」那样的灰色脉冲）
            text.contains("无心跳响应") || text.contains("设备离线") -> red to false
            text.contains("配对中") || text.contains("配对重试") || text.contains("未配对") -> amber to true
            text == "探活中…" -> amber to true
            text.contains("连接中") -> gray to true
            text.contains("已配对") -> green to false
            text.contains("已连接") -> (if (online) green else gray) to false
            else -> (if (online) green else gray) to false
        }
    }

    /** B5：连接质量(RTT) —— 优/良/一般/弱，由活动端查询往返时延推导 */
    fun setConnQuality(rttMs: Long) {
        connQuality = when {
            rttMs < 0 -> "—"
            rttMs < 300 -> "优 · ${rttMs}ms"
            rttMs < 800 -> "良 · ${rttMs}ms"
            rttMs < 2000 -> "一般 · ${rttMs}ms"
            else -> "弱 · ${rttMs}ms"
        }
        if (_binding == null) return
        setRow(binding.rowConnQuality, "连接质量", connQuality)
    }

    /** B4：断连/未连接时禁用并置灰动作按钮，避免下发无效指令 */
    fun setActionsEnabled(enabled: Boolean) {
        _actionsOnline = enabled
        applyActionState()
    }

    /**
     * 解绑态：禁用刷新、MQTT 开关、下拉刷新、快捷动作，仅保留「重新配对」入口可点。
     * 缓存快照仍可见，但所有远程操作不可用，直到重新配对成功。
     */
    fun setControlsEnabled(enabled: Boolean) {
        if (_binding == null) return
        binding.btnRefresh.isEnabled = enabled
        binding.btnRefresh.alpha = if (enabled) 1f else 0.4f
        binding.switchRemote.isEnabled = enabled
        binding.switchRemote.alpha = if (enabled) 1f else 0.4f
        binding.swipeRefresh.isEnabled = enabled
        // 快捷动作一并受控（解绑时禁用，配对成功后随在线态恢复）
        _actionsOnline = enabled
        applyActionState()
    }

    /** D2：快捷动作下发中置灰防重复点击；与在线态取交集 */
    fun setActionsBusy(busy: Boolean) {
        _actionsBusy = busy
        applyActionState()
    }

    private fun applyActionState() {
        if (_binding == null) return
        val enabled = _actionsOnline && !_actionsBusy
        val alpha = if (enabled) 1f else 0.4f
        listOf(
            binding.btnActPunch,
            binding.btnActStart,
            binding.btnActStop,
            binding.btnActAttendance
        ).forEach { btn ->
            btn.isEnabled = enabled
            btn.alpha = alpha
        }
    }

    /** C1：应用折叠状态（expanded=true 时展开，chevron 指向右；false 时收起，chevron 指向下） */
    private fun applyCollapse(body: android.view.View, chevron: android.widget.ImageView, expanded: Boolean) {
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.rotation = if (expanded) 0f else 90f
    }

    /** C1：切换折叠并持久化（true=收起） */
    private fun toggleSection(body: android.view.View, chevron: android.widget.ImageView, key: String) {
        val expanded = body.visibility != View.VISIBLE
        applyCollapse(body, chevron, expanded)
        requireActivity().getSharedPreferences("remote_ctrl", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(key, !expanded).apply()
    }

    /** D3：缓存数据可能已过期的灰色细条提示 */
    fun setStaleBanner(show: Boolean) {
        if (_binding == null) return
        binding.tvStaleStrip.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** B5：记录一条最近指令回执并刷新列表（定长 5），同时持久化到 SharedPreferences */
    fun addRecentCommand(label: String, result: String) {
        recentCmds.addLast(label to result)
        while (recentCmds.size > MAX_RECENT) recentCmds.removeFirst()
        drawRecentCmds()
        saveRecentCmds()
    }

    private fun restoreRecentCmds() {
        val prefs = requireActivity().getSharedPreferences("remote_ctrl", android.content.Context.MODE_PRIVATE)
        val json = prefs.getString(recentCmdsKey, null) ?: return
        try {
            val type = object : com.google.gson.reflect.TypeToken<ArrayList<Pair<String, String>>>() {}.type
            val list = com.google.gson.Gson().fromJson<ArrayList<Pair<String, String>>>(json, type)
            recentCmds.clear()
            list.forEach { recentCmds.addLast(it) }
            drawRecentCmds()
        } catch (_: Exception) { }
    }

    private fun saveRecentCmds() {
        val prefs = requireActivity().getSharedPreferences("remote_ctrl", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(recentCmdsKey, com.google.gson.Gson().toJson(recentCmds.toList())).apply()
    }

    private fun drawRecentCmds() {
        if (_binding == null) return
        binding.layoutRecentCmds.removeAllViews()
        if (recentCmds.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = "暂无下发指令，操作上方快捷按钮后回执将显示在此"
                textSize = 13f
                setTextColor(requireContext().getColor(R.color.md_onSurfaceVariant))
                setPadding(0, 12, 0, 4)
            }
            binding.layoutRecentCmds.addView(empty)
            return
        }
        recentCmds.forEach { (label, result) ->
            val row = RowInfoBinding.inflate(LayoutInflater.from(requireContext()))
            row.tvRowLabel.text = label
            row.tvRowValue.text = result
            row.tvRowValue.setTextColor(requireContext().getColor(
                when {
                    result.contains("成功") -> R.color.md_tertiary
                    result.contains("失败") || result.contains("未") || result.contains("过期")
                        || result.contains("重复") || result.contains("不一致") -> R.color.md_error
                    else -> R.color.md_onSurface
                }
            ))
            binding.layoutRecentCmds.addView(row.root)
        }
    }

    /** B5：绘制电池曲线 sparkline + 趋势摘要 */
    private fun drawBatteryTrend(series: List<BatteryPoint>) {
        if (_binding == null) return
        if (series.size < 2) {
            binding.batterySpark.setData(emptyList())
            binding.tvBatteryTrend.text = "数据不足：被控端需常驻运行以采样电量（近 12 小时）"
            return
        }
        binding.batterySpark.setData(series.map { it.ts to it.level })
        val first = series.first().level
        val last = series.last().level
        val drop = (first - last).coerceAtLeast(0)
        binding.tvBatteryTrend.text = "近 12 小时：$first% → $last%" +
            if (drop > 0) "（掉电 ${drop}%）" else "（电量平稳）"
    }

    private fun render(s: DeviceSnapshot) {
        val act = requireActivity() as? DeviceControlActivity ?: return
        val device = act.device

        binding.tvDeviceName.text = device.name
        val model = s.device["model"] ?: ""
        val devId = s.device["deviceId"] ?: device.deviceId
        binding.tvDeviceSub.text = if (model.isNotBlank()) "$model · $devId" else devId
        applyConnStatus()

        // 连接信息
        setRow(binding.rowBroker, "Broker", device.broker)
        setRow(binding.rowDeviceId, "设备ID", devId)
        setRow(binding.rowClientId, "客户端ID", "ctl-$devId")
        setRow(binding.rowPaired, "配对状态", if (device.sessionSecret.isNotBlank()) "已配对" else "未配对")
        // D5：未配对时显示「重新配对」入口
        binding.btnRePair.visibility = if (device.sessionSecret.isNotBlank()) View.GONE else View.VISIBLE
        val synced = relTime(s.syncedAt)
        setRow(binding.rowSynced, "最近同步", synced)

        // 运行概览
        val battery = s.runtime["battery"]?.toIntOrNull() ?: -1
        binding.tvBatteryPct.text = if (battery >= 0) "$battery%" else "--%"
        binding.progressBattery.progress = battery.coerceAtLeast(0)
        val batColor = when {
            battery < 0 -> R.color.md_outline
            battery < 30 -> R.color.md_error
            battery < 60 -> R.color.md_tertiary
            else -> R.color.md_primary
        }
        binding.tvBatteryPct.setTextColor(requireContext().getColor(batColor))
        binding.tvBatteryCharging.text = "充电：${s.runtime["charging"] ?: "--"} · ${s.runtime["temperature"] ?: ""}"

        // B5：连接质量 + 电池曲线（跨 render 保留）
        setRow(binding.rowConnQuality, "连接质量", connQuality)
        drawBatteryTrend(s.batterySeries)

        setChip(binding.chipForeground, "前台服务", s.runtime["foregroundRunning"] == "true")
        setChip(binding.chipScheduler, "任务调度", s.runtime["schedulerRunning"] == "true")
        setChip(binding.chipPowerSave, "省电模式", s.runtime["powerSaveMode"] == "true")
        setChip(binding.chipPseudo, "伪息屏", s.runtime["forcePseudoMask"] == "true")
        setChip(binding.chipWifi, "WiFi", s.runtime["wifi"] == "已连接")
        setChip(binding.chipBluetooth, "蓝牙", s.runtime["bluetooth"] == "已开启")
        setChip(binding.chipNextReset, "下次重置 ${s.runtime["nextReset"] ?: "--"}", false)
        val runMin = s.runtime["serviceRunningMinutes"]?.toLongOrNull() ?: -1L
        setChip(binding.chipServiceRun, if (runMin >= 0) "运行 ${runMin}分" else "运行时长", false)

        // 任务调度描述 / 设备时间 / 电池温度（快照补齐字段）
        setRow(binding.rowSchedulerDesc, "任务调度", s.runtime["schedulerDesc"] ?: "--")
        // #6 调度描述文字可能很长 → 跑马灯
        binding.rowSchedulerDesc.tvRowValue.apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1 // 无限循环
            isFocusable = true
            isFocusableInTouchMode = true
            setSelected(true)
        }
        setRow(binding.rowDeviceTime, "设备时间", s.runtime["currentTime"] ?: "--")
        setRow(binding.rowTemperature, "电池温度", s.runtime["temperature"] ?: "--")

        // 设备信息
        setRow(binding.rowModel, "型号", s.device["model"] ?: "--")
        setRow(binding.rowBrand, "品牌/厂商", "${s.device["brand"] ?: "--"} ${s.device["manufacturer"] ?: ""}".trim())
        setRow(binding.rowAndroid, "系统版本", "Android ${s.device["androidVersion"] ?: "?"} (API ${s.device["sdk"] ?: "?"})")
        setRow(binding.rowApp, "应用版本", s.device["appVersion"] ?: "--")
        setRow(binding.rowScreen, "屏幕分辨率", s.device["screen"] ?: "--")
        setRow(binding.rowInstall, "安装ID", s.device["installId"] ?: "--")

        // 打卡概览
        binding.tvPunchPunched.text = s.calendar.punched
        binding.tvPunchScheduled.text = s.calendar.scheduled
        binding.tvPunchMissed.text = s.calendar.missed
        setRow(binding.rowNext, "下次打卡", s.runtime["nextPunch"] ?: "--")
        setRow(binding.rowRecent, "最近打卡", s.calendar.recentPunch)
        setRow(binding.rowToday, "今日状态", s.calendar.today)

        // 最近历史
        binding.layoutHistory.removeAllViews()
        if (s.history.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = "近 14 天无打卡记录"
                textSize = 13f
                setTextColor(requireContext().getColor(R.color.md_onSurfaceVariant))
                setPadding(0, 12, 0, 4)
            }
            binding.layoutHistory.addView(empty)
        } else {
            s.history.forEach { h ->
                val row = RowInfoBinding.inflate(LayoutInflater.from(requireContext()))
                row.tvRowLabel.text = h.time
                row.tvRowValue.text = h.result
                row.tvRowValue.setTextColor(historyColor(h.result))
                binding.layoutHistory.addView(row.root)
            }
        }
    }

    private fun setRow(row: RowInfoBinding, label: String, value: String) {
        row.tvRowLabel.text = label
        row.tvRowValue.text = value
    }

    /** D3：同步时间转相对文本（刚刚 / x分钟前 / x小时前 / x天前） */
    private fun relTime(ts: Long): String {
        if (ts <= 0L) return "—"
        val sec = (System.currentTimeMillis() - ts) / 1000
        return when {
            sec < 0 -> "刚刚"
            sec < 60 -> "刚刚"
            sec < 3600 -> "${sec / 60} 分钟前"
            sec < 86400 -> "${sec / 3600} 小时前"
            else -> "${sec / 86400} 天前"
        }
    }

    private fun setChip(tv: android.widget.TextView, label: String, on: Boolean) {
        tv.text = label
        if (on) {
            tv.background.setTint(requireContext().getColor(R.color.md_primaryContainer))
            tv.setTextColor(requireContext().getColor(R.color.md_primary))
        } else {
            tv.background.setTint(requireContext().getColor(R.color.md_surfaceVariant))
            tv.setTextColor(requireContext().getColor(R.color.md_onSurfaceVariant))
        }
    }

    private fun historyColor(result: String): Int {
        return requireContext().getColor(when {
            result.contains("成功") -> R.color.md_tertiary
            result.contains("超时") -> R.color.md_error
            else -> R.color.md_onSurface
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelSwipeRefresh()
        pulseAnim?.cancel()
        pulseAnim = null
        _binding = null
    }
}
