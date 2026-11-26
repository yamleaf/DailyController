package com.yample.daily.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.yample.daily.controller.databinding.FragmentDeviceBinding
import com.yample.daily.controller.databinding.RowInfoBinding
import com.yample.mqttprotocol.MqttQuota
import com.yample.mqttprotocol.Protocol

/**
 * 设备页：设备相关的连接信息 + 硬件信息（由原「权限」页更名为「设备」，
 * 连接信息 / 设备信息折叠卡由设置页迁移至此，权限状态已合并回设置页）。
 */
class DeviceFragment : Fragment(), SnapshotFragment {

    private var _binding: FragmentDeviceBinding? = null
    private val binding get() = _binding!!
    private var snapshot: DeviceSnapshot? = null
    /** 连接质量(RTT) 文本，跨 render 保留 */
    private var connQuality: String = "—"

    /** 解绑设备：入口由设置页移至设备页 */
    var onUnbind: (() -> Unit)? = null

    /** 删除设备（仅未绑定态）：清除本地全部数据并返回列表 */
    var onDelete: (() -> Unit)? = null

    private var unboundMode = false

    /** 双态按钮：未绑定显示「删除设备」，已绑定显示「解绑设备」 */
    fun setUnboundState(unbound: Boolean) {
        unboundMode = unbound
        if (_binding == null) return
        binding.btnUnbindDevice.text = if (unbound) "删除设备" else "解绑设备"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDeviceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 折叠状态（默认收起），跨会话保留
        val prefs = requireActivity().getSharedPreferences("remote_ctrl", android.content.Context.MODE_PRIVATE)
        val connCollapsed = prefs.getBoolean("collapse_conn", true)
        val devCollapsed = prefs.getBoolean("collapse_deviceinfo", true)
        val remoteCollapsed = prefs.getBoolean("collapse_remote", true)
        applyCollapse(binding.bodyConn, binding.ivChevronConn, !connCollapsed)
        applyCollapse(binding.bodyDevice, binding.ivChevronDevice, !devCollapsed)
        applyCollapse(binding.bodyRemote, binding.ivChevronRemote, !remoteCollapsed)
        binding.btnToggleConn.setOnClickListener { toggleSection(binding.bodyConn, binding.ivChevronConn, "collapse_conn") }
        binding.btnToggleDevice.setOnClickListener { toggleSection(binding.bodyDevice, binding.ivChevronDevice, "collapse_deviceinfo") }
        binding.btnToggleRemote.setOnClickListener { toggleSection(binding.bodyRemote, binding.ivChevronRemote, "collapse_remote") }
        binding.btnUnbindDevice.setOnClickListener {
            if (unboundMode) onDelete?.invoke() else onUnbind?.invoke()
        }
        // 设备页信息行多为缩写/术语，统一支持点击看说明（不改任何既有按钮与折叠交互）
        bindChipHelp(binding.tvQuotaText, "消息配额") {
            "「已用」是本次统计周期内 DT 与 Broker 之间收发的消息总数。\n\n" +
                "该数值由控制端本地的 MqttQuota 统计（非 DT 上报），用于观察流量消耗；下方明细里的「本次连接」指本端与 Broker 建立当前连接后的时长。"
        }
        bindChipHelp(binding.tvQuotaHint, "收发消息数") {
            "已发送 = 控制端下发的指令/探活等消息条数；已接收 = 控制端收到的快照、回执、告警等条数。\n\n" +
                "两边差距过大通常表示 DT 离线或回执丢失。"
        }
        bindChipHelp(binding.layoutQuotaDetails, "连接与消息明细") {
            "已累计连接：本端历史上与 Broker 建立连接的总时长；本次连接：当前这次连接的持续时长。\n\n" +
                "消息类计数从本端统计，重启 App 后重新累计；它只反映控制端这一侧，不等于 DT 收发的全部消息。"
        }
        bindChipHelp(binding.rowSynced.root, "最近同步") {
            "当前这份快照的获取时间，显示为相对时间（刚刚 / x分钟前 / x小时前）。\n\n" +
                "时间偏大说明 DT 离线或快照长时间未刷新，可下拉手动刷新。"
        }
        bindChipHelp(binding.rowPaired.root, "配对状态") {
            "已配对 = 本端与 DT 已完成扫码绑定并持有会话密钥，才能下发指令；未配对只能查看基础状态。\n\n" +
                "与「在线」是两件事：可达但未配对时，设备列表会显示「在线·解绑」。"
        }
        bindChipHelp(binding.rowConnQuality.root, "连接质量") {
            "按一次 MQTT 探活的往返时延（RTT）分级：<300ms 优、<800ms 良、<2000ms 一般、其余弱。\n\n" +
                "只反映当前这一刻的网络往返，不代表 DT 是否在线；「—」表示尚未测到。"
        }
        bindChipHelp(binding.rowKeepaliveLevel.root, "保活级别") {
            "DT 在真息屏下维持 MQTT 心跳的方式：ALARM（系统闹钟驱动心跳，较省电）/ CPU（持锁保持 CPU 调度，最稳但耗电）。\n\n" +
                "AUTO 模式下若 30 分钟内掉线 ≥2 次会自动由 ALARM 升到 CPU，且不自动降级；每次 MQTT 服务启动会重置回 ALARM。"
        }
        bindChipHelp(binding.rowKeepaliveChanged.root, "最近切换") {
            "保活级别最近一次发生变化的方向与时间（DT 设备时间，已压缩为 月-日 时:分）。\n\n" +
                "「固定 ALARM / 固定 CPU」表示你指定了保活模式，不会发生自动切换；「启动重置 ALARM」表示服务重启后回到起步级别。"
        }
        bindChipHelp(binding.rowInstall.root, "安装ID") {
            "DT 每次安装生成的本地标识，用于区分同一设备的不同安装实例。\n\n" +
                "卸载重装后会变化；与设备ID（用于 MQTT 主题）不是同一个值。"
        }
        snapshot?.let { render(it) }
    }

    /** 信息条点击说明：委托 [bindHelp] 公共实现；isAdded 守卫防止页面已销毁后弹窗抛异常。 */
    private fun bindChipHelp(view: View, title: String, message: () -> String) {
        view.bindHelp(title, message) { isAdded && _binding != null }
    }

    override fun refresh(snapshot: DeviceSnapshot) {
        this.snapshot = snapshot
        if (isAdded && _binding != null) render(snapshot)
    }

    /** 消息统计回传（由活动端周期推送），渲染配额卡片 */
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

    /** 连接质量(RTT) —— 优/良/一般/弱，由活动端查询往返时延推导 */
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

    private fun render(s: DeviceSnapshot) {
        val act = requireActivity() as? DeviceControlActivity ?: return
        val device = act.device
        val devId = s.device["deviceId"] ?: device.deviceId
        setRow(binding.rowBroker, "Broker", device.broker)
        setRow(binding.rowDeviceId, "设备ID", devId)
        setRow(binding.rowClientId, "客户端ID", "ctl-$devId")
        setRow(binding.rowPaired, "配对状态", if (device.sessionSecret.isNotBlank()) "已配对" else "未配对")
        setRow(binding.rowSynced, "最近同步", relTime(s.syncedAt))
        setRow(binding.rowConnQuality, "连接质量", connQuality)

        setRow(binding.rowModel, "型号", s.device["model"] ?: "--")
        setRow(binding.rowScreenState, "手机状态", s.runtime["screenState"] ?: "--")
        // 息屏保活三级自适应：仅「伪息屏关闭 + 屏幕模式=息屏」时展示（此时才走 MQTT 保活策略）
        val pseudoOn = s.settings.firstOrNull { it.key == Protocol.FIELD_FORCE_PSEUDO_MASK }?.value as? Boolean ?: false
        val screenOff = (s.settings.firstOrNull { it.key == Protocol.FIELD_SCREEN_MODE }?.value as? Int) == 1
        val showKeepalive = !pseudoOn && screenOff
        val kv = if (showKeepalive) View.VISIBLE else View.GONE
        binding.rowKeepaliveLevel.root.visibility = kv
        binding.rowKeepaliveChanged.root.visibility = kv
        if (showKeepalive) {
            setRow(binding.rowKeepaliveLevel, "保活级别", s.runtime["keepaliveLevel"] ?: "--")
            val desc = s.runtime["keepaliveLevelChangedDesc"] ?: "--"
            // 与概览页统一口径：DT 上报的完整 yyyy-MM-dd HH:mm:ss 压缩为 MM-dd HH:mm，避免长串撑破行宽
            val at = formatDeviceTimeShort(s.runtime["keepaliveLevelChangedAt"])
            setRow(binding.rowKeepaliveChanged, "最近切换", if (desc != "—") "$desc · $at" else at)
        }
        setRow(binding.rowProtoVer, "协议版本", s.device["protoVer"] ?: "—")
        setRow(binding.rowBrand, "品牌/厂商", "${s.device["brand"] ?: "--"} ${s.device["manufacturer"] ?: ""}".trim())
        setRow(binding.rowAndroid, "系统版本", "Android ${s.device["androidVersion"] ?: "?"} (API ${s.device["sdk"] ?: "?"})")
        setRow(binding.rowApp, "应用版本", s.device["appVersion"] ?: "--")
        setRow(binding.rowScreen, "屏幕分辨率", s.device["screen"] ?: "--")
        setRow(binding.rowInstall, "安装ID", s.device["installId"] ?: "--")
    }

    private fun applyCollapse(body: View, chevron: ImageView, expanded: Boolean) {
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.rotation = if (expanded) 0f else 90f
    }

    /** 切换折叠并持久化（true=收起） */
    private fun toggleSection(body: View, chevron: ImageView, key: String) {
        val expanded = body.visibility != View.VISIBLE
        applyCollapse(body, chevron, expanded)
        requireActivity().getSharedPreferences("remote_ctrl", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(key, !expanded).apply()
    }

    private fun setRow(row: RowInfoBinding, label: String, value: String) {
        row.tvRowLabel.text = label
        row.tvRowValue.text = value
    }

    /** 同步时间转相对文本（刚刚 / x分钟前 / x小时前 / x天前） */
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
