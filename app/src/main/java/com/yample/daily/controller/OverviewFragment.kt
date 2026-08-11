package com.yample.daily.controller

import android.animation.ArgbEvaluator
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
import com.yample.mqttprotocol.Protocol
import com.yample.daily.controller.databinding.FragmentOverviewBinding
import com.yample.daily.controller.databinding.RowInfoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.ArrayDeque

enum class SnapshotHint { NONE, WAITING, FAILED, DISABLED }

/** B5：最近指令回执（label=指令名，result=回执结果，ts=回执时间戳） */
data class RecentCommand(val label: String?, val result: String?, val ts: Long)

class OverviewFragment : Fragment(), SnapshotFragment {

    private var _binding: FragmentOverviewBinding? = null
    private val binding get() = _binding!!
    private var snapshot: DeviceSnapshot? = null
    var onRefreshClick: (() -> Unit)? = null
    var onRemoteToggle: ((Boolean) -> Unit)? = null
    var onAction: ((String) -> Unit)? = null
    /** 开启/关闭「任务每日循环」（下发 ar 设置：true=开启，false=关闭） */
    var onLoopToggle: ((Boolean) -> Unit)? = null
    var onRePairClick: (() -> Unit)? = null
    var onRetryClick: (() -> Unit)? = null
    /** 对称按钮：右侧「重新连接」——断开并重建 MQTT 连接 */
    var onReconnectClick: (() -> Unit)? = null
    /** 设备告警历史点击：弹出与首次收到时一致的告警弹窗 */
    var onAlertClick: ((AlertRecord) -> Unit)? = null
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
    /** 首帧淡入：首次 render 时内容从半透明淡入，配合探活进度条给出「数据到达」观感 */
    private var firstRender = true
    /** D2：快捷动作是否处于下发中（防重复点击 + 加载态） */
    private var _actionsBusy = false
    private var _actionsOnline = true
    /** B5：最近指令回执定长队列（最多 5 条），跨会话持久化 */
    private val recentCmds = ArrayDeque<RecentCommand>()
    private val MAX_RECENT = 5
    private var recentCmdsKey: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOverviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // v2：升级时丢弃旧格式缓存——旧包混淆字段名不一致会把 label/result 反序列化成 null，
        // 渲染「最近指令」时 NPE 闪退（且 R8 会按「字段仅经构造函数写入」优化掉空值保护，见 f7e4833 同类问题）
        recentCmdsKey = "recent_cmds_v2_${requireActivity().intent.getStringExtra("deviceId") ?: "unknown"}"
        restoreRecentCmds()
        // 设备告警历史：进入总览页加载一次
        val deviceId = requireActivity().intent.getStringExtra("deviceId") ?: "unknown"
        refreshAlerts(AlertHistory.load(requireContext(), deviceId))
        binding.btnAlertsClear.setOnClickListener {
            AlertHistory.clear(requireContext(), deviceId)
            refreshAlerts(emptyList())
        }
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
        binding.btnActLoopOn.setOnClickListener { onLoopToggle?.invoke(true) }
        binding.btnActLoopOff.setOnClickListener { onLoopToggle?.invoke(false) }

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

    /** 探活骨架屏：探活进行中显示顶部进度条，首帧到达淡出 */
    fun setProbing(probing: Boolean) {
        if (_binding == null) return
        val v = binding.progressProbe
        if (probing) {
            v.visibility = View.VISIBLE
            v.alpha = 1f
        } else {
            if (v.visibility != View.VISIBLE) return
            v.animate().alpha(0f).setDuration(220).withEndAction { v.visibility = View.GONE }.start()
        }
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

/** 状态灯：用球形渐变 + 呼吸脉冲表达连接状态，取代原「已连接（已配对）」式冗长文案 */
    private fun applyConnStatus() {
        if (!isAdded || _binding == null) return
        val ctx = requireContext()
        val key = "$lastConnText|$lastConnOnline"
        if (appliedStatusKey == key) return
        appliedStatusKey = key
        val (color, pulse) = resolveStatus(lastConnText, lastConnOnline, ctx)

        // 使用球面渐变圆形作为主状态点（内置高光，不参与 tint）
        binding.dotStatus.setBackgroundResource(sphereForStatus(lastConnText, lastConnOnline))
        // 光晕保持纯色平铺，用于动态 tint 光环
        binding.dotHalo.setBackgroundResource(R.drawable.bg_dot_offline)
        animateTint(binding.dotHalo, color)
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

    /** 状态文案 → 球面渐变 drawable（与 resolveStatus 状态逻辑一致） */
    private fun sphereForStatus(text: String, online: Boolean): Int = when {
        text == "连接失败" -> R.drawable.bg_sphere_error
        text.contains("已解绑") || text.contains("已被强制解绑") -> R.drawable.bg_sphere_offline
        text.contains("订阅被拒") || text.contains("配对发送失败") -> R.drawable.bg_sphere_error
        text.contains("无心跳响应") || text.contains("设备离线") -> R.drawable.bg_sphere_error
        text.contains("配对中") || text.contains("配对重试") || text.contains("未配对") -> R.drawable.bg_sphere_pairing
        text == "探活中…" -> R.drawable.bg_sphere_pairing
        text.contains("连接中") -> R.drawable.bg_sphere_offline
        text.contains("已配对") -> R.drawable.bg_sphere_online
        text.contains("已连接") -> if (online) R.drawable.bg_sphere_online else R.drawable.bg_sphere_offline
        else -> if (online) R.drawable.bg_sphere_online else R.drawable.bg_sphere_offline
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
            binding.btnActAttendance,
            binding.btnActLoopOn,
            binding.btnActLoopOff
        ).forEach { btn ->
            btn.isEnabled = enabled
            btn.alpha = alpha
        }
    }

    /** D3：缓存数据可能已过期的灰色细条提示 */
    fun setStaleBanner(show: Boolean) {
        if (_binding == null) return
        binding.tvStaleStrip.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** B5：记录一条最近指令回执并刷新列表（定长 5），同时持久化到 SharedPreferences */
    fun addRecentCommand(label: String, result: String) {
        recentCmds.addLast(RecentCommand(label, result, System.currentTimeMillis()))
        while (recentCmds.size > MAX_RECENT) recentCmds.removeFirst()
        drawRecentCmds()
        saveRecentCmds()
    }

    /** 设备告警历史：渲染到总览页「设备告警」区块，点击弹出告警弹窗 */
    fun refreshAlerts(alerts: List<AlertRecord>) {
        if (_binding == null) return
        binding.layoutAlerts.removeAllViews()
        binding.tvAlertsEmpty.visibility = if (alerts.isEmpty()) View.VISIBLE else View.GONE
        binding.btnAlertsClear.visibility = if (alerts.isEmpty()) View.GONE else View.VISIBLE
        alerts.forEach { alert ->
            val row = RowInfoBinding.inflate(LayoutInflater.from(requireContext()))
            row.tvRowLabel.text = alert.title
            val timeStr = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(alert.ts))
            row.tvRowValue.text = if (alert.type == "device_offline") {
                "$timeStr · 已离线"
            } else {
                "$timeStr · ${alert.battery}%"
            }
            row.tvRowValue.setTextColor(requireContext().getColor(
                when (alert.type) {
                    "battery_smart_alert", "low_battery", "device_offline" -> R.color.md_error
                    "battery_full" -> R.color.md_tertiary
                    else -> R.color.md_onSurface
                }
            ))
            row.root.setOnClickListener { onAlertClick?.invoke(alert) }
            binding.layoutAlerts.addView(row.root)
        }
    }

    private fun restoreRecentCmds() {
        val prefs = requireActivity().getSharedPreferences("remote_ctrl", android.content.Context.MODE_PRIVATE)
        val json = prefs.getString(recentCmdsKey, null) ?: return
        try {
            val type = object : com.google.gson.reflect.TypeToken<ArrayList<RecentCommand>>() {}.type
            val list = com.google.gson.Gson().fromJson<ArrayList<RecentCommand>>(json, type)
            recentCmds.clear()
            // 防御：旧包/混淆字段名不一致时反序列化出的 label/result 可能为 null，渲染会 NPE 闪退。
            // 过滤脏条目，若确实丢了数据则回写干净缓存（自愈）。
            val clean = list.filter { it.label != null && it.result != null }
            clean.forEach { recentCmds.addLast(it) }
            if (clean.size != list.size) saveRecentCmds()
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
        recentCmds.forEach { cmd ->
            val row = RowInfoBinding.inflate(LayoutInflater.from(requireContext()))
            row.tvRowLabel.text = cmd.label ?: "未知指令"
            val timeStr = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(cmd.ts))
            val result = cmd.result ?: ""
            row.tvRowValue.text = "$result · $timeStr"
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

    /** B5：绘制电池曲线 sparkline + 趋势摘要 + 耗尽预测 */
    private fun drawBatteryTrend(series: List<BatteryPoint>, currentRuntimeBattery: Int = -1) {
        if (_binding == null) return
        if (series.size < 2) {
            binding.batterySpark.setData(emptyList())
            binding.tvBatteryTrend.text = "数据不足：被控端需常驻运行以采样电量（近 12 小时）"
            binding.layoutBatteryPredict.visibility = View.GONE
            return
        }
        binding.batterySpark.setData(series.map { it.ts to it.level })
        val first = series.first().level
        val last = series.last().level
        val drop = (first - last).coerceAtLeast(0)
        binding.tvBatteryTrend.text = "近 12 小时：$first% → $last%" +
            if (drop > 0) "（掉电 ${drop}%）" else "（电量平稳）"

        // 电量预测：优先使用被控端上报的 BatteryPredictor 结果（与被控端智能预警同一算法，
        // 预测「降至低电量阈值的时间」），避免控制端本地另算导致与上报值偏差大。
        // 仅当被控端无预测数据（数据不足 / 充电中）时才回退到本地曲线推算。
        val level = if (currentRuntimeBattery >= 0) currentRuntimeBattery else last
        val devicePredictTime = snapshot?.runtime?.get("batteryPredictTime")
        val devicePredictThreshold = snapshot?.runtime?.get("batteryPredictThreshold")
        val devicePredictHas = snapshot?.runtime?.get("batteryPredictHas") == "true"
        val devicePredictCharging = snapshot?.runtime?.get("batteryPredictCharging") == "true"
        val predictText: String = when {
            devicePredictHas && devicePredictCharging -> "（当前在充电，无耗尽风险）"
            !devicePredictTime.isNullOrBlank() -> {
                val threshold = devicePredictThreshold ?: "30"
                "预计 $devicePredictTime 降至低电量阈值 ${threshold}%"
            }
            else -> batteryPredictText(series, level)
        }
        if (predictText.isBlank()) {
            binding.layoutBatteryPredict.visibility = View.GONE
        } else {
            binding.tvBatteryPredict.text = predictText
            binding.layoutBatteryPredict.visibility = View.VISIBLE
        }
    }

    /**
     * 计算电量耗尽预测文案。
     * 用最近的一段纯放电数据（精确排除充电段）拟合掉电速率（%/小时），
     * 再按当前电量推算预计耗尽时间。
     *
     * 算法策略（双窗口）：
     *   1. 主窗口：从序列末尾往回取「最近一段连续纯放电」数据——最能反映当前使用模式；
     *      若该段 ≥ 30 分钟则直接用于计算速率。
     *   2. 回退窗口：若主窗口不足 30 分钟，则扩大到「排除所有充电点后的最近 2 小时」短窗口
     *      （避免含早期低功耗时段拉低速率）。
     *   3. 用被控端上报的 [BatteryPoint.charging] 字段精确判定充电点（替代旧版 level≥99 启发式），
     *      解决"充到 92% 就拔掉"导致整段 12h 数据都被纳入、速率偏低的 bug。
     */
    private fun batteryPredictText(series: List<BatteryPoint>, currentLevel: Int): String {
        if (series.size < 2) return ""
        val sorted = series.sortedBy { it.ts }

        // 主窗口：最近一段连续纯放电（从末尾往前跳过充电点，再往前找到连续放电段的起点）
        val latestDischarge = extractLatestDischargeSegment(sorted)
        if (latestDischarge.size < 2) return ""

        val ratePerHour = {
            val f = latestDischarge.first()
            val l = latestDischarge.last()
            val elapsed = (l.ts - f.ts) / 3600_000.0
            if (elapsed >= 0.5) (f.level - l.level) / elapsed else fallbackRate(sorted)
        }()

        if (ratePerHour <= 0) return "（当前在充电或电量上升，无耗尽风险）"

        // 预测降至低电量阈值的时间（与被控端 BatteryPredictor 口径一致，而非耗尽到 0%）
        val threshold = snapshot?.settings?.firstOrNull { it.key == "lb" }?.value as? Int ?: 30
        val targetLevel = currentLevel.coerceAtLeast(threshold)
        val hoursToTarget = (targetLevel - threshold).toDouble() / ratePerHour
        // 用 Calendar 计算预计到达阈值时间，确保日期进位准确（SimpleDateFormat+Date 在某些 locale 下可能出问题）
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.MINUTE, (hoursToTarget * 60).toInt())
        }
        val timeText = String.format("%02d-%02d %02d:%02d",
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE))
        return when {
            currentLevel <= 20 -> "⚠️ 电量仅剩 $currentLevel%，预计约 ${"%.1f".format(hoursToTarget)} 小时后（$timeText）降至 ${threshold}%"
            else -> "预计 ${"%.1f".format(hoursToTarget)} 小时后（约 $timeText）降至低电量阈值 ${threshold}%"
        }
    }

    /** 从排序序列末尾提取最近一段连续纯放电（charging=false）的数据 */
    private fun extractLatestDischargeSegment(sorted: List<BatteryPoint>): List<BatteryPoint> {
        var endIdx = sorted.lastIndex
        // 跳过末尾可能的充电点
        while (endIdx >= 0 && sorted[endIdx].charging) {
            endIdx--
        }
        if (endIdx < 0) return emptyList()
        // 往前追溯到这段连续放电的起点（遇到充电点或序列头即停）
        var startIdx = endIdx
        while (startIdx > 0 && !sorted[startIdx - 1].charging) {
            startIdx--
        }
        return sorted.subList(startIdx, endIdx + 1)
    }

    /**
     * 回退窗口：排除所有充电点后，取最近 2 小时的纯放电数据计算速率。
     * 仅在主窗口连续放电段不足 30 分钟时调用。
     */
    private fun fallbackRate(sorted: List<BatteryPoint>): Double {
        val nowMs = System.currentTimeMillis()
        val windowMs = 2L * 3600_000L  // 2 小时短窗口
        // 过滤：只保留非充电 + 在 2 小时窗口内的点
        val recent = sorted.filter { !it.charging && (nowMs - it.ts <= windowMs) }
        if (recent.size < 2) return -1.0
        val f = recent.first()
        val l = recent.last()
        val elapsed = (l.ts - f.ts) / 3600_000.0
        if (elapsed < 0.3) return -1.0  // 至少需要 18 分钟
        return (f.level - l.level) / elapsed
    }

    private fun render(s: DeviceSnapshot) {
        val act = requireActivity() as? DeviceControlActivity ?: return
        val device = act.device

        binding.tvDeviceName.text = device.name
        val model = s.device["model"] ?: ""
        val devId = s.device["deviceId"] ?: device.deviceId
        binding.tvDeviceSub.text = if (model.isNotBlank()) "$model · $devId" else devId

        applyConnStatus()

        // D5：未配对时显示「重新配对」入口（hero 卡内重配按钮）
        binding.btnRePair.visibility = if (device.sessionSecret.isNotBlank()) View.GONE else View.VISIBLE

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
        animateTextColor(binding.tvBatteryPct, requireContext().getColor(batColor))
        binding.tvBatteryCharging.text = "充电：${s.runtime["charging"] ?: "--"} · ${s.runtime["temperature"] ?: ""}"

        // B5：电池曲线（跨 render 保留）
        drawBatteryTrend(s.batterySeries, battery)

        setChip(binding.chipForeground, "前台服务", s.runtime["foregroundRunning"] == "true")
        setChip(binding.chipScheduler, "任务调度", s.runtime["schedulerRunning"] == "true")
        setChip(binding.chipPowerSave, "节能模式", s.runtime["powerSaveMode"] == "true")
        setChip(binding.chipPseudo, "伪息屏", s.runtime["forcePseudoMask"] == "true")
        setChip(binding.chipWifi, "WiFi", s.runtime["wifi"] == "已连接")
        setChip(binding.chipBluetooth, "蓝牙", s.runtime["bluetooth"] == "已开启")
        // 每日循环状态来自被控端快照设置 ar（开启循环 / 关闭循环快捷操作可远程切换）
        val loopOn = s.settings.firstOrNull { it.key == Protocol.FIELD_TASK_AUTO_RECYCLE }?.value as? Boolean ?: true
        setChip(binding.chipLoop, "每日循环", loopOn)
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

        // 设备信息（已迁移至「设置」页）

        // 打卡概览
        binding.tvPunchPunched.text = s.calendar.punched
        binding.tvPunchScheduled.text = s.calendar.scheduled
        binding.tvPunchMissed.text = s.calendar.missed
        setRow(binding.rowNext, "下次打卡", s.runtime["nextPunch"] ?: "--")
        setRow(binding.rowRecent, "最近打卡", s.calendar.recentPunch)
        setRow(binding.rowToday, "今日状态", s.calendar.today)

        // 最近打卡记录（已迁移至「日历」页）

        // 首帧淡入：配合探活进度条给出「数据到达」的顺滑观感
        if (firstRender) {
            firstRender = false
            binding.contentRoot.alpha = 0.55f
            binding.contentRoot.animate().alpha(1f).setDuration(280).start()
        }
    }

    private fun setRow(row: RowInfoBinding, label: String, value: String) {
        row.tvRowLabel.text = label
        row.tvRowValue.text = value
    }

    private fun setChip(tv: android.widget.TextView, label: String, on: Boolean) {
        tv.text = label
        if (on) {
            animateTint(tv, requireContext().getColor(R.color.md_primaryContainer))
            animateTextColor(tv, requireContext().getColor(R.color.md_primary))
        } else {
            animateTint(tv, requireContext().getColor(R.color.md_surfaceVariant))
            animateTextColor(tv, requireContext().getColor(R.color.md_onSurfaceVariant))
        }
    }

    /** 状态/颜色渐变：避免瞬切，用 ArgbEvaluator 做 200ms 平滑过渡（Material 质感） */
    private val tintCache = mutableMapOf<View, Int>()
    private val textCache = mutableMapOf<View, Int>()

    private fun animateTint(view: View, toColor: Int, durationMs: Long = 200) {
        val from = tintCache[view] ?: toColor
        tintCache[view] = toColor
        if (from == toColor) {
            view.background?.setTint(toColor)
            return
        }
        ValueAnimator.ofObject(ArgbEvaluator(), from, toColor).apply {
            duration = durationMs
            addUpdateListener { view.background?.setTint(it.animatedValue as Int) }
            start()
        }
    }

    private fun animateTextColor(tv: TextView, toColor: Int, durationMs: Long = 200) {
        val from = textCache[tv] ?: toColor
        textCache[tv] = toColor
        if (from == toColor) {
            tv.setTextColor(toColor)
            return
        }
        ValueAnimator.ofObject(ArgbEvaluator(), from, toColor).apply {
            duration = durationMs
            addUpdateListener { tv.setTextColor(it.animatedValue as Int) }
            start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelSwipeRefresh()
        pulseAnim?.cancel()
        pulseAnim = null
        _binding = null
    }
}
