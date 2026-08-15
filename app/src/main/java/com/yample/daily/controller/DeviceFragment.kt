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
        binding.btnUnbindDevice.setOnClickListener { onUnbind?.invoke() }
        snapshot?.let { render(it) }
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
