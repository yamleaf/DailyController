package com.yample.daily.controller

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.yample.mqttprotocol.MqttPacket
import com.yample.mqttprotocol.Protocol
import androidx.core.content.ContextCompat
import com.yample.daily.controller.databinding.FragmentOverviewBinding
import com.yample.daily.controller.databinding.RowInfoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.ArrayDeque

enum class SnapshotHint { NONE, WAITING, FAILED, DISABLED }

/**
 * MQTT 连接状态（UI 层语义，取代原先靠字符串 contains 匹配的脆弱做法）。
 *
 * @property online     是否在线（决定状态灯绿/灰，及快捷动作是否可用）
 * @property transition 是否「连接中/探活中」过渡态（仅用于判断是否抑制离线横幅）
 */
enum class ConnState(val online: Boolean, val transition: Boolean) {
    ONLINE(true, false),            // 在线（已配对 / 心跳 HB / 已连接已配对）——绿、稳态
    CONNECTED_PAIRING(true, false), // 已连接（配对中…）——琥珀
    CONNECTED_UNPAIRED(true, false),// 已连接（未配对）——琥珀
    SUB_DENIED(true, false),        // 已连接（订阅被拒）——红
    PAIR_SEND_FAILED(true, false),  // 已连接（配对发送失败）——红
    PAIR_RETRYING(true, false),     // 已连接（配对重试 N/M）——琥珀
    CONNECTING(false, true),        // 连接中… ——灰、过渡态
    PROBING(false, true),           // 探活中… ——琥珀、过渡态
    MQTT_CLOSED(false, false),      // MQTT 已关闭——灰
    DISCONNECTED(false, false),     // 连接断开——灰
    NEED_SCAN(false, false),        // 未配对，请重新扫码——琥珀
    FAILED(false, false),           // 连接失败——红（状态灯可点击重试）
    OFFLINE(false, false),          // 设备离线 / 设备离线（无响应）——红
    UNBOUND(false, false),          // 已解绑 / 已被强制解绑——灰
}

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
    /** 视图就绪前暂存的连接状态（onCreate 同步调用可能早于 onCreateView） */
    private var pendingConnStatus: ConnState? = null
    /** 状态灯：最后连接状态，供 render 重绘复用 */
    private var lastConnState: ConnState = ConnState.MQTT_CLOSED
    private var appliedConnState: ConnState? = null
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
        binding.switchRemote.thumbTintList =
            ContextCompat.getColorStateList(requireContext(), R.color.switch_thumb_mqtt)
        binding.switchRemote.trackTintList =
            ContextCompat.getColorStateList(requireContext(), R.color.switch_track_mqtt)
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
        binding.switchRemote.setOnCheckedChangeListener { _, isChecked ->
            if (!remoteInitializing) onRemoteToggle?.invoke(isChecked)
        }
        binding.btnActPunch.setOnClickListener { onAction?.invoke(MqttPacket.ACTION_PUNCH) }
        binding.btnActStart.setOnClickListener { onAction?.invoke(MqttPacket.ACTION_START) }
        binding.btnActStop.setOnClickListener { onAction?.invoke(MqttPacket.ACTION_STOP) }
        binding.btnActAttendance.setOnClickListener { onAction?.invoke(MqttPacket.ACTION_ATTENDANCE) }
        binding.btnActLoopOn.setOnClickListener { onLoopToggle?.invoke(true) }
        binding.btnActLoopOff.setOnClickListener { onLoopToggle?.invoke(false) }

        // 概览页信息条：单行 ellipsize 会把文案截断（如「今日 1 次 · 最近 2026-…」），
        // 含义不清的指标统一支持点击看说明；说明内容延迟到点击时取最新快照，避免注册时数据未到。
        bindChipHelp(binding.chipDisconnect, "今日掉线次数") {
            val s = snapshot
            val count = s?.runtime?.get("mqttDisconnectCount")?.toIntOrNull() ?: 0
            val rawAt = s?.runtime?.get("mqttLastDisconnectAt") ?: "—"
            "今日掉线 $count 次。\n\n" +
                "统计口径：被控端（DT）上报，按 DT 本地日期每天 0 点清零。\n" +
                "最近断连：$rawAt（DT 设备时间）\n\n" +
                "短暂断连会自动重连，不一定影响打卡。若集中在夜间/息屏出现，通常是深睡时 MQTT 心跳被系统推迟、精确闹钟权限被关闭或网络不稳；可检查 DT 的精确闹钟权限。\n\n" +
                "每次掉线的「断开 → 恢复」区间和持续时长，见下方「最近离线」信息条。"
        }
        bindChipHelp(binding.chipLastOffline, "最近一次离线") {
            val s = snapshot
            // 优先用与本次区间成对写入的起点；旧版 DT 只有 mqttLastDisconnectAt 时回退
            val since = s?.runtime?.get("mqttLastOfflineSince")?.takeIf { it.isNotBlank() && it != "—" }
                ?: s?.runtime?.get("mqttLastDisconnectAt") ?: "—"
            val until = s?.runtime?.get("mqttLastReconnectAt") ?: "—"
            val seconds = s?.runtime?.get("mqttLastOfflineSeconds")?.toLongOrNull() ?: -1L
            val duration = if (seconds >= 0L) formatDurationShort(seconds) else "—"
            "最近一次已经恢复的 MQTT 断连区间（DT 上报）：\n\n" +
                "断开：$since\n恢复：$until\n持续：$duration\n\n" +
                "以上均为 DT 设备时间。DT 离线期间 MQTT 本身断开、无法推送，所以这段信息是在重连成功后随快照补报的，描述的是「最近一次已结束的离线」，不包含当前正在进行的断连。\n\n" +
                "持续几十秒多为网络抖动；持续数分钟以上且集中在夜间，通常是深睡时心跳被系统推迟。升级到支持该字段的 DT 版本后才会显示本条；旧版本 DT 会自动隐藏。"
        }
        bindChipHelp(binding.chipScheduleDesc, "任务调度描述") {
            "被控端任务调度器当前状态的一句话说明，通常包含下一次任务的时间窗口、随机延迟或跳过原因。\n\n" +
                "显示「暂无调度描述」多表示任务未启用、调度器未运行，或快照尚未刷新。"
        }
        bindChipHelp(binding.chipServiceRun, "服务运行时长") {
            "被控端前台保活服务自本次启动以来的持续运行时间，按量级压缩显示（分 / 时分 / 天时）。\n\n" +
                "数值突然归零，说明服务曾被系统回收或重启过。"
        }
        bindChipHelp(binding.chipDeviceTime, "设备时间") {
            "被控端设备上的当前时间，已压缩显示为「月-日 时:分」（原始格式 yyyy-MM-dd HH:mm:ss）。\n\n" +
                "用于和本机时间对照；两边相差过大可能导致任务窗口判断不准。"
        }
        bindChipHelp(binding.chipNextReset, "下次重置") {
            "被控端每日重置任务状态与当日计数（含掉线次数）的时间点。\n\n" +
                "重置后「今日」类统计会清零，不影响已保存的任务配置。"
        }
        bindChipHelp(binding.chipPseudo, "伪息屏") {
            "被控端用黑色蒙层模拟息屏，屏幕与 CPU 仍保持活跃，用于避免真息屏后系统冻结打卡任务与 MQTT 心跳。\n\n" +
                "开启期间会增加耗电；关闭后走真息屏，依赖保活策略维持心跳。"
        }
        bindChipHelp(binding.chipPowerSave, "节能模式") {
            "被控端系统省电模式的当前状态（亮色=已开启）。\n\n" +
                "开启后系统会限制后台活动，可能造成任务延迟或 MQTT 心跳被推迟。"
        }
        bindChipHelp(binding.tvBatteryTrend, "电池趋势") {
            "基于被控端上报的近 12 小时电量采样绘制的曲线与摘要。\n\n" +
                "「X% → Y%」是区间首尾电量，后面的变化量用于估算掉电快慢；采样点少于 2 个时显示「数据不足」，需要 DT 常驻运行一段时间才会累积。"
        }
        bindChipHelp(binding.tvBatteryPredict, "电量预测") {
            "按最近一段时间的掉电速率，估算预计耗尽时间以及达到预警阈值的时间。\n\n" +
                "充电中、采样不足或刚开机时不预测。预测仅供参考，实际耗电会随息屏策略、网络状况和任务执行波动。"
        }
        bindChipHelp(binding.tvStaleStrip, "数据可能不是最新") {
            "当前显示的是本机缓存的上次快照，不是被控端实时数据。通常出现在 DT 离线、MQTT 未连接，或刚进入页面还没拉到快照时。\n\n" +
                "连接恢复后会自动刷新，也可以下拉手动刷新。"
        }
        bindChipHelp(binding.chipTemperature, "电池温度") {
            "被控端系统上报的电池温度，单位 ℃。\n\n" +
                "仅作参考，不同机型传感器精度差异较大；持续偏高多与充电或高负载有关。"
        }
        bindChipHelp(binding.tvBatteryCharging, "充电与温度") {
            "左侧是 DT 当前是否在充电（true/false），右侧是电池温度（℃）。\n\n" +
                "数据来自快照，离线时显示的是缓存值；下拉刷新可更新。"
        }
        // 状态条靠背景色表达开关，色弱/明暗主题下不易分辨，统一在点击说明里写明判读方式
        val toggleHint = "亮色（高亮）= 已开启/已连接，灰底 = 已关闭/未连接。\n\n" +
            "这是 DT 快照上报的状态，不是本机状态；离线时显示的是上次缓存值。"
        bindChipHelp(binding.chipForeground, "前台服务") { "被控端常驻前台保活服务是否在运行。\n\n$toggleHint\n\n若长期为关闭，打卡任务与 MQTT 心跳可能被系统冻结。" }
        bindChipHelp(binding.chipScheduler, "任务调度") { "被控端任务调度器是否在运行（负责按时间窗口触发打卡）。\n\n$toggleHint" }
        bindChipHelp(binding.chipWifi, "WiFi 状态") { "被控端 WiFi 连接状态。\n\n$toggleHint" }
        bindChipHelp(binding.chipBluetooth, "蓝牙状态") { "被控端蓝牙开关状态。\n\n$toggleHint" }
        bindChipHelp(binding.chipLoop, "每日循环") {
            "任务是否在每天完成后自动重置、次日继续执行。\n\n$toggleHint\n\n关闭后任务执行完即停止，不会在次日自动重排。"
        }
        bindChipHelp(binding.tvSnapshotHint, "快照等待提示") {
            "进入页面时会向 DT 请求一份运行快照；这行文字表示请求仍在进行或准备重试。\n\n" +
                "长时间不消失通常是 DT 离线、MQTT 未连接或未配对；可下拉刷新重试。"
        }
        bindChipHelp(binding.tvPunchPunched, "已打卡天数") {
            "近两周日历中状态为「成功」或「超时」的天数合计（被控端按日期聚合）。\n\n超时也算已打卡：任务确实执行过，只是确认结果回来得晚。"
        }
        bindChipHelp(binding.tvPunchScheduled, "计划天数") { "被控端上报的区间内计划执行的打卡天数。\n\n休息日、跳过的任务不计入。" }
        bindChipHelp(binding.tvPunchMissed, "未打卡天数") { "被控端上报的、计划内但最终没有打卡记录的天数。\n\n与日历页「● 未打卡」同一口径。" }
        bindChipHelp(binding.rowNext.root, "下次打卡") { "被控端调度器计算出的下一次任务触发时间；「--」表示当前没有待执行任务。" }
        bindChipHelp(binding.rowRecent.root, "最近打卡") { "被控端上报的最近一次打卡结果（时间 + 结果文案），用于快速确认上一次是否成功。" }
        bindChipHelp(binding.rowToday.root, "今日状态") { "被控端上报的今日打卡状态原文，例如已打卡 / 待执行 / 休息 / 跳过。\n\n口径由 DT 决定，DC 不做转换。" }

        snapshot?.let { render(it) }
        pendingConnStatus?.let { state ->
            pendingConnStatus = null
            setConnStatusText(state)
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
        setSnapshotHint(hint, 0L, 0L)
    }

    fun setSnapshotHint(hint: SnapshotHint, lastActivityMs: Long, timeoutMs: Long = 0) {
        if (_binding == null) return
        if (hint == SnapshotHint.NONE || hint == SnapshotHint.DISABLED) {
            binding.tvSnapshotHint.visibility = if (hint == SnapshotHint.NONE) View.GONE else View.VISIBLE
            if (hint == SnapshotHint.DISABLED) {
                binding.tvSnapshotHint.text = "MQTT 连接已关闭：开启后可获取被控端实时状态"
                binding.tvSnapshotHint.setTextColor(requireContext().getColor(R.color.md_onSurfaceVariant))
            }
            return
        }
        binding.tvSnapshotHint.visibility = View.VISIBLE
        when (hint) {
            SnapshotHint.WAITING -> {
                val ago = if (lastActivityMs > 0) formatLastSeen(lastActivityMs) else null
                val retrySec = (timeoutMs / 1000).toInt()
                binding.tvSnapshotHint.text = if (ago != null) {
                    "正在等待被控端返回快照（上次活跃${ago}），约${retrySec}秒后自动重试…"
                } else {
                    "正在等待被控端返回快照…"
                }
                binding.tvSnapshotHint.setTextColor(requireContext().getColor(R.color.md_onSurfaceVariant))
            }
            SnapshotHint.FAILED -> {
                val ago = if (lastActivityMs > 0) formatLastSeen(lastActivityMs) else null
                binding.tvSnapshotHint.text = if (ago != null) {
                    "被控端未返回快照（上次活跃${ago}），可能是临时网络抖动，可下拉刷新立即重试"
                } else {
                    "未获取到快照：请确认被控端在线且已配对，或下拉刷新重试"
                }
                binding.tvSnapshotHint.setTextColor(requireContext().getColor(R.color.md_error))
            }
        }
    }

    override fun refresh(snapshot: DeviceSnapshot) {
        this.snapshot = snapshot
        if (isAdded && _binding != null) render(snapshot)
    }

    fun setConnStatusText(state: ConnState) {
        lastConnState = state
        if (!isAdded || _binding == null) {
            pendingConnStatus = state
            return
        }
        applyConnStatus()
    }

    /** 最后活跃时间格式化 */
    private fun formatLastSeen(ts: Long): String? {
        if (ts <= 0L) return null
        val diff = System.currentTimeMillis() - ts
        if (diff < 60_000) return "${diff / 1000} 秒前"
        if (diff < 3_600_000) return "${diff / 60_000} 分钟前"
        if (diff < 86_400_000) return "${diff / 3_600_000} 小时前"
        return "${diff / 86_400_000} 天前"
    }

    /** 状态灯：用球形渐变 + 呼吸脉冲表达连接状态，取代原「已连接（已配对）」式冗长文案 */
    private fun applyConnStatus() {
        if (!isAdded || _binding == null) return
        if (appliedConnState == lastConnState) return
        appliedConnState = lastConnState

        // 使用球面渐变圆形作为主状态点（内置高光，不参与 tint）
        binding.dotStatus.setBackgroundResource(sphereForStatus(lastConnState))
        // D6：连接失败时状态灯可点击重试
        val failed = lastConnState == ConnState.FAILED
        binding.dotStatus.isClickable = failed
        binding.dotStatus.setOnClickListener { if (failed) onRetryClick?.invoke() }
        // 解绑态重配对入口
        binding.btnRePair.setOnClickListener { onRePairClick?.invoke() }
        // 在线时状态灯呼吸脉冲，让「设备在线」更醒目
        if (lastConnState == ConnState.ONLINE) startOnlinePulse() else stopOnlinePulse()
    }

    /** 解绑态显示「重新配对」入口；配对成功后隐藏 */
    private var rePairBreathAnimator: ValueAnimator? = null

    /** 在线状态灯呼吸动画 */
    private var onlinePulseAnimator: ValueAnimator? = null

    /** 解绑态显示「重新配对」入口（置顶）；显示时启动呼吸描边动画，隐藏时停止 */
    fun setRePairVisible(visible: Boolean) {
        if (_binding == null) return
        binding.btnRePair.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) startRePairBreath() else stopRePairBreath()
    }

    /** 呼吸描边：浅杏橙描边透明度在 0.25↔0.95 间循环，营造引导关注效果 */
    private fun startRePairBreath() {
        rePairBreathAnimator?.cancel()
        val primary = ContextCompat.getColor(requireContext(), R.color.re_pair_accent)
        val baseRgb = primary and 0x00FFFFFF
        rePairBreathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                // 防御：视图销毁时可能仍有最后一帧回调在跑，此时 _binding 已置空
                if (_binding == null) return@addUpdateListener
                val f = anim.animatedValue as Float
                val alpha = ((0.25f + 0.70f * f) * 255).toInt()
                binding.btnRePair.strokeColor = android.content.res.ColorStateList.valueOf(baseRgb or (alpha shl 24))
            }
            start()
        }
    }

    private fun stopRePairBreath() {
        rePairBreathAnimator?.cancel()
        rePairBreathAnimator = null
        if (_binding != null) {
            binding.btnRePair.strokeColor =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.re_pair_accent)
                )
        }
    }

    /** 在线状态灯呼吸：alpha 在 0.45↔1.0 循环（不缩放，避免高光层错位） */
    private fun startOnlinePulse() {
        onlinePulseAnimator?.cancel()
        onlinePulseAnimator = ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = 1000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                if (_binding == null) return@addUpdateListener
                binding.dotStatus.alpha = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun stopOnlinePulse() {
        onlinePulseAnimator?.cancel()
        onlinePulseAnimator = null
        if (_binding != null) binding.dotStatus.alpha = 1f
    }

    /** 连接状态 → 球面渐变 drawable（球面渐变灯取代文字，仅凭颜色/形状表达状态） */
    private fun sphereForStatus(state: ConnState): Int = when (state) {
        // 红：失败类
        ConnState.FAILED,
        ConnState.SUB_DENIED,
        ConnState.PAIR_SEND_FAILED,
        ConnState.OFFLINE -> R.drawable.bg_sphere_error
        // 灰：断开/解绑/关闭类
        ConnState.MQTT_CLOSED,
        ConnState.DISCONNECTED,
        ConnState.UNBOUND,
        ConnState.CONNECTING -> R.drawable.bg_sphere_offline
        // 琥珀：配对/待扫类
        ConnState.CONNECTED_PAIRING,
        ConnState.CONNECTED_UNPAIRED,
        ConnState.PAIR_RETRYING,
        ConnState.NEED_SCAN,
        ConnState.PROBING -> R.drawable.bg_sphere_pairing
        // 绿：在线稳态
        ConnState.ONLINE -> R.drawable.bg_sphere_online
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
        // 局部变量外提：一次刷新只构造 1 个 formatter / inflater（原来每条告警各 1 个，
        // 最多 30 条 = 30 次 SimpleDateFormat 构造 + 30 次 LayoutInflater 查找）。
        // 刻意不提为字段：SimpleDateFormat 非线程安全，局部变量不跨调用/跨线程共享，零并发风险。
        val inflater = LayoutInflater.from(requireContext())
        val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        alerts.forEach { alert ->
            val row = RowInfoBinding.inflate(inflater)
            val timeStr = timeFmt.format(Date(alert.ts))
            // 时间统一放最左侧（与标题同行），右侧只留状态摘要
            row.tvRowLabel.text = "$timeStr · ${alert.title}"
            row.tvRowValue.text = when (alert.type) {
                "device_offline" -> "已离线"
                Protocol.ALERT_TYPE_ID_CONFLICT -> "有陌生设备抢用本设备ID"
                // 电量类告警展示百分比；操作留痕类无电量数据（-1）显示「操作记录」
                else -> if (alert.battery in 0..100) "${alert.battery}%" else "操作记录"
            }
            // 右侧摘要超宽时用跑马灯循环滚动，保证信息完整可见（仅告警行生效，不影响共用布局的其他页面）
            row.tvRowValue.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            row.tvRowValue.marqueeRepeatLimit = -1
            row.tvRowValue.isSelected = true
            row.tvRowValue.setTextColor(requireContext().getColor(
                when (alert.type) {
                    "battery_smart_alert", "low_battery", "device_offline", Protocol.ALERT_TYPE_ID_CONFLICT -> R.color.md_error
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
        val inflater = LayoutInflater.from(requireContext())
        val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
        recentCmds.forEach { cmd ->
            val row = RowInfoBinding.inflate(inflater)
            row.tvRowLabel.text = cmd.label ?: "未知指令"
            val timeStr = timeFmt.format(Date(cmd.ts))
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
            row.root.bindHelp(
                title = "指令回执",
                message = {
                    "指令：${cmd.label ?: "未知指令"}\n回执：$result\n时间：$timeStr（本机时间）\n\n" +
                        "这里只记录本机下发过的指令及其回执，不代表 DT 当前状态；指令是否真正执行完成，以打卡记录与 DT 快照为准。"
                },
                alive = { isAdded && _binding != null }
            )
            binding.layoutRecentCmds.addView(row.root)
        }
    }

    /** B5：绘制电池曲线 sparkline + 趋势摘要 + 耗尽预测 */
    private fun drawBatteryTrend(series: List<BatteryPoint>) {
        if (_binding == null) return
        if (series.size < 2) {
            binding.batterySpark.setData(emptyList())
            binding.tvBatteryTrend.text = "数据不足：被控端需常驻运行以采样电量（近 12 小时）"
            binding.tvBatteryPredict.text = "电量预测：数据不足，无法预测"
            binding.layoutBatteryPredict.visibility = View.VISIBLE
            return
        }
        binding.batterySpark.setData(series.map { it.ts to it.level })
        val first = series.first().level
        val last = series.last().level
        val drop = (first - last).coerceAtLeast(0)
        binding.tvBatteryTrend.text = "近 12 小时：$first% → $last%" +
            if (drop > 0) "（掉电 ${drop}%）" else "（电量平稳）"

        // 电量预测：只信被控端 BatteryPredictor 的有效结果（与智能预警同一算法）。
        // 已低于阈值时被控端改报「耗尽」预测，避免「0.0 小时后降至阈值」。
        val devicePredictTime = snapshot?.runtime?.get("batteryPredictTime")
        val devicePredictThreshold = snapshot?.runtime?.get("batteryPredictThreshold")
        val devicePredictHours = snapshot?.runtime?.get("batteryPredictHours")
        val devicePredictRate = snapshot?.runtime?.get("batteryPredictRate")
        val devicePredictHas = snapshot?.runtime?.get("batteryPredictHas") == "true"
        val devicePredictCharging = snapshot?.runtime?.get("batteryPredictCharging") == "true"
        val devicePredictExhaust = snapshot?.runtime?.get("batteryPredictExhaust") == "true"
        val batteryNow = snapshot?.runtime?.get("battery")?.toIntOrNull()
            ?: if (last in 0..100) last else -1
        val rateSuffix = devicePredictRate?.takeIf { it.isNotBlank() }?.let { "（耗电 ${it}%/h）" } ?: ""
        val predictText: String = when {
            devicePredictHas && devicePredictCharging -> "电量预测：当前在充电，无耗尽风险"
            devicePredictHas && devicePredictExhaust -> {
                val hours = devicePredictHours?.takeIf { it.isNotBlank() } ?: "—"
                val levelText = if (batteryNow >= 0) "$batteryNow%" else "低电量"
                "电量预测：电量仅剩 $levelText，预计约 ${hours} 小时后电量耗尽$rateSuffix"
            }
            devicePredictHas && !devicePredictTime.isNullOrBlank() -> {
                val threshold = devicePredictThreshold ?: "30"
                "电量预测：预计 $devicePredictTime 降至低电量阈值 ${threshold}%$rateSuffix"
            }
            else -> "电量预测：数据不足，无法预测"
        }
        binding.tvBatteryPredict.text = predictText
        binding.layoutBatteryPredict.visibility = View.VISIBLE
    }

    private fun render(s: DeviceSnapshot) {
        val act = requireActivity() as? DeviceControlActivity ?: return
        val device = act.device

        binding.tvDeviceName.text = device.name
        val model = s.device["model"] ?: ""
        val devId = s.device["deviceId"] ?: device.deviceId
        binding.tvDeviceSub.text = if (model.isNotBlank()) "$model · $devId" else devId

        applyConnStatus()

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
        drawBatteryTrend(s.batterySeries)

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
        val svcMin = s.runtime["serviceRunningMinutes"]?.toLongOrNull() ?: -1L
        setChip(binding.chipServiceRun, if (svcMin >= 0) "服务运行 ${formatUptime(svcMin)}" else "服务运行", false)

        // 任务调度描述 / 设备时间 / 电池温度 / 掉线统计（小长条样式，与上方状态条一致）
        setChip(binding.chipScheduleDesc, s.runtime["schedulerDesc"] ?: "暂无调度描述", false)
        setChip(binding.chipDeviceTime, "设备时间 ${formatDeviceTime(s.runtime["currentTime"])}", false)
        setChip(binding.chipTemperature, "电池温度 ${s.runtime["temperature"] ?: "--"}", false)
        // 掉线统计（被控端 runtime 上报：所有保活模式统一计数，跨天重置）
        val discCount = s.runtime["mqttDisconnectCount"]?.toIntOrNull() ?: 0
        val discAt = s.runtime["mqttLastDisconnectAt"] ?: "—"
        // 完整 yyyy-MM-dd HH:mm:ss 在单行 chip 里会被截断成「2026…」，压缩成 MM-dd HH:mm；
        // 同时补回「掉线」二字，避免「今日 1 次」不知所指。完整时间在点击说明里展示。
        val discAtShort = formatDeviceTime(discAt)
        val discText = if (discCount <= 0) "今日 0 次掉线" else "今日掉线 $discCount 次 · 最近 $discAtShort"
        setChip(binding.chipDisconnect, discText, false)
        // 最近一次「已恢复」的离线区间：新字段由 DT 重连后随快照补报；旧版 DT / 从未掉线时隐藏该行
        val offlineSeconds = s.runtime["mqttLastOfflineSeconds"]?.toLongOrNull() ?: -1L
        val recAt = s.runtime["mqttLastReconnectAt"] ?: "—"
        if (offlineSeconds >= 0L && recAt != "—") {
            val sinceRaw = s.runtime["mqttLastOfflineSince"]?.takeIf { it.isNotBlank() && it != "—" }
                ?: s.runtime["mqttLastDisconnectAt"]
            val sinceShort = formatDeviceTime(sinceRaw)
            val untilShort = formatDeviceTime(recAt)
            binding.chipLastOffline.visibility = View.VISIBLE
            setChip(
                binding.chipLastOffline,
                "最近离线 $sinceShort → $untilShort · ${formatDurationShort(offlineSeconds)}",
                false
            )
        } else {
            binding.chipLastOffline.visibility = View.GONE
        }

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

    /**
     * 运行时长格式化（chip 半行宽有限，按量级递减精度防截断）：
     * <1小时「Y分」；<1天「X时Y分」；>=1天「X天Y时」（分钟粒度在天级无意义）
     */
    private fun formatUptime(minutes: Long): String = when {
        minutes >= 1440 -> "${minutes / 1440}天${minutes % 1440 / 60}时"
        minutes >= 60 -> "${minutes / 60}时${minutes % 60}分"
        else -> "${minutes}分"
    }

    /** 设备时间压缩：委托同包公共实现，供设备页复用同一口径 */
    private fun formatDeviceTime(raw: String?): String = formatDeviceTimeShort(raw)

    /** 信息条点击说明：委托 [bindHelp] 公共实现；isAdded 守卫防止页面已销毁后弹窗抛异常。 */
    private fun bindChipHelp(view: View, title: String, message: () -> String) {
        view.bindHelp(title, message) { isAdded && _binding != null }
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
        // 取消呼吸动画，避免视图销毁后 addUpdateListener 访问 binding 触发 NPE/泄漏
        stopRePairBreath()
        stopOnlinePulse()
        _binding = null
    }
}
