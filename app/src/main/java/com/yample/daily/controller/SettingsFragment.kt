package com.yample.daily.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yample.daily.controller.databinding.DialogMsgChannelBinding
import com.yample.daily.controller.databinding.DialogSliderBinding
import com.yample.daily.controller.databinding.FragmentSettingsBinding
import com.yample.daily.controller.databinding.RowInfoBinding

class SettingsFragment : Fragment(), SnapshotFragment {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var snapshot: DeviceSnapshot? = null
    private val settingItems = mutableListOf<SettingItem>()
    private lateinit var settingAdapter: SettingAdapter

    var onToggle: ((SettingItem, Boolean) -> Unit)? = null
    var onIntChange: ((SettingItem, Int) -> Unit)? = null

    /** 需求 1：批量下发消息渠道配置（JSON 字符串，走 mcfg 字段） */
    var onMsgConfigSave: ((String) -> Unit)? = null

    /** 需求 1：消息渠道枚举（mc：0-邮件，1-企业微信） */
    var onChannelChange: ((Int) -> Unit)? = null

    companion object {
        /** 由「消息渠道」卡片单独承载的字段，不在通用设置列表里重复渲染 */
        private val MSG_KEYS = setOf("mc", "mt", "em", "ei", "wk", "ea")
        /** 「任务每日循环」已移至概览页快捷操作（开启循环 / 关闭循环），不再在设置列表渲染 */
        private val HIDDEN_KEYS = setOf("ar")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingAdapter = SettingAdapter(settingItems,
            onToggle = { item, on -> handleToggle(item, on) },
            onEditValue = { item -> showSliderIfNeeded(item) }
        )
        binding.rvSettings.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSettings.adapter = settingAdapter
        binding.btnEditMsgChannel.setOnClickListener { showMsgChannelDialog() }
        snapshot?.let { render(it) }
    }

    override fun refresh(snapshot: DeviceSnapshot) {
        this.snapshot = snapshot
        if (isAdded && _binding != null) render(snapshot)
    }

    private fun render(s: DeviceSnapshot) {
        settingItems.clear()
        settingItems.addAll(s.settings.filter { it.key !in MSG_KEYS && it.key !in HIDDEN_KEYS })
        settingAdapter.notifyDataSetChanged()
        renderMsgChannelCard(s)
        renderStatuses(s)
    }

    // ===================== 需求 1：消息渠道镜像卡片 =====================

    private fun settingOf(key: String): SettingItem? = snapshot?.settings?.firstOrNull { it.key == key }

    private fun strOf(key: String): String = settingOf(key)?.value?.toString().orEmpty()

    private fun renderMsgChannelCard(s: DeviceSnapshot) {
        val hasChannel = s.settings.any { it.key in MSG_KEYS }
        binding.cardMsgChannel.visibility = if (hasChannel) View.VISIBLE else View.GONE
        if (!hasChannel) return

        val channel = (settingOf("mc")?.value as? Int) ?: 0
        val rows = listOf(
            "发送渠道" to if (channel == 1) "企业微信" else "邮件",
            "消息标题" to strOf("mt").ifBlank { "打卡结果通知" },
            "企业微信Key" to strOf("wk").ifBlank { "未设置" },
            "发件箱" to strOf("em").ifBlank { "未设置" },
            "授权码" to strOf("ea").ifBlank { "未设置" },
            "收件箱" to strOf("ei").ifBlank { "未设置" }
        )
        binding.layoutMsgChannelRows.removeAllViews()
        rows.forEach { (label, value) ->
            val row = RowInfoBinding.inflate(LayoutInflater.from(requireContext()))
            row.tvRowLabel.text = label
            row.tvRowValue.text = value
            binding.layoutMsgChannelRows.addView(row.root)
        }
    }

    /**
     * 消息渠道配置弹窗：
     * - 授权码 / Webhook Key 使用密码输入框（默认掩码，可点眼睛查看本次录入的明文）；
     * - 被控端只回传掩码，留空即代表沿用被控端已保存的值，不会覆盖。
     */
    private fun showMsgChannelDialog() {
        val dlgBinding = DialogMsgChannelBinding.inflate(LayoutInflater.from(requireContext()))
        val channel = (settingOf("mc")?.value as? Int) ?: 0
        if (channel == 1) dlgBinding.rbWx.isChecked = true else dlgBinding.rbEmail.isChecked = true
        dlgBinding.etTitle.setText(strOf("mt").ifBlank { "打卡结果通知" })
        dlgBinding.etOutbox.setText(strOf("em"))
        dlgBinding.etInbox.setText(strOf("ei"))
        // 敏感字段：不回填掩码内容，仅通过 helper 提示当前是否已设置
        dlgBinding.tilWxKey.helperText =
            if (strOf("wk").isBlank()) "当前未设置 · 留空不修改" else "已设置 · 留空不修改"
        dlgBinding.tilAuth.helperText =
            if (strOf("ea").isBlank()) "当前未设置 · 留空不修改" else "已设置 · 留空不修改"

        // 需求：邮件 / 企业微信 分框显示，按当前选中渠道切换可见分组
        fun applyChannelGroups(isWx: Boolean) {
            dlgBinding.tvGroupWx.visibility = if (isWx) View.VISIBLE else View.GONE
            dlgBinding.tilWxKey.visibility = if (isWx) View.VISIBLE else View.GONE
            dlgBinding.tvGroupEmail.visibility = if (isWx) View.GONE else View.VISIBLE
            dlgBinding.tilOutbox.visibility = if (isWx) View.GONE else View.VISIBLE
            dlgBinding.tilAuth.visibility = if (isWx) View.GONE else View.VISIBLE
            dlgBinding.tilInbox.visibility = if (isWx) View.GONE else View.VISIBLE
        }
        applyChannelGroups(channel == 1)
        dlgBinding.rgChannel.setOnCheckedChangeListener { _, _ ->
            applyChannelGroups(dlgBinding.rbWx.isChecked)
        }

        UnifiedDialogKit.showForm(
            ctx = requireContext(),
            contentView = dlgBinding.root,
            title = "配置消息渠道",
            positiveText = "保存",
            negativeText = "取消",
            onConfirm = {
                val newChannel = if (dlgBinding.rbWx.isChecked) 1 else 0
                val title = dlgBinding.etTitle.text?.toString()?.trim().orEmpty()
                val wxKey = dlgBinding.etWxKey.text?.toString()?.trim().orEmpty()
                val outbox = dlgBinding.etOutbox.text?.toString()?.trim().orEmpty()
                val auth = dlgBinding.etAuth.text?.toString()?.trim().orEmpty()
                val inbox = dlgBinding.etInbox.text?.toString()?.trim().orEmpty()

                val obj = com.google.gson.JsonObject()
                if (title.isNotBlank()) obj.addProperty("messageTitle", title)
                if (wxKey.isNotBlank()) obj.addProperty("wxKey", wxKey)
                if (outbox.isNotBlank()) obj.addProperty("emailOutbox", outbox)
                if (auth.isNotBlank()) obj.addProperty("emailAuth", auth)
                if (inbox.isNotBlank()) obj.addProperty("emailInbox", inbox)

                if (obj.size() > 0) onMsgConfigSave?.invoke(obj.toString())
                if (newChannel != channel) onChannelChange?.invoke(newChannel)
                if (obj.size() == 0 && newChannel == channel) {
                    android.widget.Toast
                        .makeText(requireContext(), "没有需要下发的修改", android.widget.Toast.LENGTH_SHORT).show()
                }
                true
            }
        )
    }

    // ===================== 通用设置项 =====================

    /** 需求 1：远程控制开关关掉后控制端会失联，二次确认，避免误触 */
    private fun handleToggle(item: SettingItem, on: Boolean) {
        if (item.key == "re" && !on) {
            UnifiedDialogKit.showWarning(
                requireContext(),
                "关闭远程控制服务？",
                "关闭后被控端会断开 MQTT，本控制端将无法再远程操作该设备。\n\n可在被控端本机重新开启，或通过通知指令「DT#开启远程」远程恢复。",
                confirmText = "关闭",
                onConfirm = { onToggle?.invoke(item, false) },
                onCancel = {
                    item.value = true
                    settingAdapter.notifyDataSetChanged()
                }
            )
            return
        }
        onToggle?.invoke(item, on)
    }

    private fun showSliderIfNeeded(item: SettingItem) {
        val min = item.min ?: 0
        val max = item.max ?: 100
        val step = item.step ?: 1
        val dlgBinding = DialogSliderBinding.inflate(LayoutInflater.from(requireContext()))
        val refreshValue = { dlgBinding.tvSliderValue.text = "${item.value as? Int ?: min} ${unitFor(item.key)}" }
        dlgBinding.tvSliderValue.text = "${item.value as? Int ?: min} ${unitFor(item.key)}"
        dlgBinding.slider.apply {
            valueFrom = min.toFloat()
            valueTo = max.toFloat()
            stepSize = step.toFloat()
            value = ((item.value as? Int) ?: min).toFloat().coerceIn(min.toFloat(), max.toFloat())
            addOnChangeListener { _, value, _ ->
                item.value = value.toInt()
                refreshValue()
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.label)
            .setView(dlgBinding.root)
            .setPositiveButton("保存") { _, _ ->
                val v = item.value as? Int ?: min
                onIntChange?.invoke(item, v)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun unitFor(key: String): String = when (key) {
        "tm" -> "秒"
        "ot" -> "秒"
        "tr" -> "分"
        "rh" -> "时"
        "lb" -> "%"
        else -> ""
    }

    // ===================== 系统权限状态（由「权限」页合并） =====================

    /** 渲染被控端系统权限状态（悬浮窗 / 通知监听 / 截屏 / 无障碍等，只读） */
    private fun renderStatuses(s: DeviceSnapshot) {
        binding.layoutStatuses.removeAllViews()
        if (s.statuses.isEmpty()) {
            binding.cardStatuses.visibility = View.GONE
            return
        }
        binding.cardStatuses.visibility = View.VISIBLE
        s.statuses.forEach { st ->
            val row = RowInfoBinding.inflate(LayoutInflater.from(requireContext()))
            row.tvRowLabel.text = st.label
            row.tvRowValue.text = st.value
            row.tvRowValue.setTextColor(statusColor(st.value))
            binding.layoutStatuses.addView(row.root)
        }
    }

    private fun statusColor(value: String): Int {
        return requireContext().getColor(when {
            value.contains("已获取") || value.contains("已开启") || value == "正常" || value.contains("截屏") || value.contains("通知") -> R.color.md_tertiary
            value.contains("未") || value == "已授权但断开" -> R.color.md_error
            else -> R.color.md_onSurface
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
