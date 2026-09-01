package com.yample.daily.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.yample.daily.controller.databinding.DialogMsgChannelBinding
import com.yample.daily.controller.databinding.DialogSliderBinding
import com.yample.daily.controller.databinding.FragmentSettingsBinding
import com.yample.daily.controller.databinding.RowInfoBinding
import com.yample.mqttprotocol.dialog.UnifiedDialogKit

class SettingsFragment : Fragment(), SnapshotFragment {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var snapshot: DeviceSnapshot? = null
    private val settingItems = mutableListOf<SettingListItem>()
    private lateinit var settingAdapter: SettingAdapter

    /** 解绑态命令禁用标志：持有在 Fragment 上，适配器重建（懒创建视图）后依然生效 */
    private var commandsEnabled = true

    var onToggle: ((SettingItem, Boolean) -> Unit)? = null
    var onIntChange: ((SettingItem, Int) -> Unit)? = null

    /** 自定义工作日等字符串字段下发（走 cw 字段） */
    var onStringChange: ((SettingItem, String) -> Unit)? = null

    /** 需求 1：批量下发消息渠道配置（JSON 字符串，走 mcfg 字段） */
    var onMsgConfigSave: ((String) -> Unit)? = null

    /** 需求 1：消息渠道枚举（mc：0-邮件，1-企业微信） */
    var onChannelChange: ((Int) -> Unit)? = null

    /** feat_shiziku：点击「Shizuku 高级设置」入口（由宿主打开镜像配置页） */
    var onShizukuClick: (() -> Unit)? = null

    companion object {
/** 由「消息渠道」卡片单独承载的字段，不在通用设置列表里重复渲染 */
        private val MSG_KEYS = setOf("mc", "mt", "em", "ei", "wk", "ea")
        /** 「任务每日循环」已移至概览页快捷操作（开启循环/关闭循环），不再在设置列表渲染 */
        private val HIDDEN_KEYS = setOf("ar")
        /** feat_shiziku：Sz 高级设置字段由「高级设置」子页承载，不在通用设置列表渲染 */
        private val SHIZUKU_KEYS = setOf(
            "sz_status", "sz_granted", "sz_enabled", "sz_method",
            "sz_pwdSteps", "sz_verifySteps", "sz_authStepsCount",
            "sz_hasPassword", "sz_verifyWait", "sz_authWait"
        )

        /** 设置项按功能分组：每组一个 section header + 属于该组的 setting keys */
        private val SETTING_GROUPS = linkedMapOf(
            "远程控制" to listOf("re"),
            "省电模式" to listOf("ps"),
            "伪息屏" to listOf("pm", "sm", "tm", "nc", "ga"),
            "通知转移" to listOf("nt"),
            "反馈方式" to listOf("fd"),
            "任务" to listOf("sh", "rt", "rh", "tr", "ot", "bo", "cw"),
            "节假日" to listOf("uh"),
            "界面" to listOf("bh", "dp"),
            "低电量告警" to listOf("lb", "ba", "bw", "bs", "br", "bd"),
            "诊断" to listOf("lg")
        )

        /** 星期多选弹窗用的标签（1=周一 ... 7=周日） */
        val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingAdapter = SettingAdapter(settingItems,
            onToggle = { item, on -> handleToggle(item, on) },
            onEditValue = { item ->
                if (item.type == "time" || item.key == "bw") showTimePicker(item)
                else if (item.key == "sm") showScreenModeDialog(item)
                else if (item.key == "cw") showWorkdayDialog(item)
                else showSliderIfNeeded(item)
            }
        )
        binding.rvSettings.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSettings.adapter = settingAdapter
        // 适配器可能在禁用标志设置之后才创建（视图懒加载），创建后立即应用
        settingAdapter.commandsEnabled = commandsEnabled
        binding.btnEditMsgChannel.setOnClickListener { if (commandsEnabled) showMsgChannelDialog() }
        binding.btnOpenShizuku.setOnClickListener { if (commandsEnabled) onShizukuClick?.invoke() }
        snapshot?.let { render(it) }
    }

    override fun refresh(snapshot: DeviceSnapshot) {
        this.snapshot = snapshot
        if (isAdded && _binding != null) render(snapshot)
    }

    private fun render(s: DeviceSnapshot) {
        settingItems.clear()
        // 按功能分组构建设置列表
        val settingMap = s.settings
            .filter { it.key !in MSG_KEYS && it.key !in HIDDEN_KEYS && it.key !in SHIZUKU_KEYS }
            .associateBy { it.key }
        SETTING_GROUPS.forEach { (groupName, keys) ->
            val items = keys.mapNotNull { settingMap[it] }
            if (items.isNotEmpty()) {
                settingItems.add(SettingListItem.Header(groupName))
                items.forEach { settingItems.add(SettingListItem.Item(it)) }
            }
        }
        // 不在任何分组中的独立设置项，归入「其他」
        val groupedKeys = SETTING_GROUPS.values.flatten().toSet()
        val ungrouped = s.settings
            .filter { it.key !in MSG_KEYS && it.key !in HIDDEN_KEYS && it.key !in SHIZUKU_KEYS && it.key !in groupedKeys }
        if (ungrouped.isNotEmpty()) {
            settingItems.add(SettingListItem.Header("其他"))
            ungrouped.forEach { settingItems.add(SettingListItem.Item(it)) }
        }
        settingAdapter.notifyDataSetChanged()
        renderMsgChannelCard(s)
        renderStatuses(s)
    }

    /** 解绑态禁用设置页全部行的下发交互（置灰，恢复后 notifyDataSetChanged 还原）；
     *  消息渠道配置按钮在适配器之外（独立镜像卡片），需一并置灰 */
    fun setCommandsEnabled(enabled: Boolean) {
        commandsEnabled = enabled
        if (this::settingAdapter.isInitialized) {
            settingAdapter.commandsEnabled = enabled
            if (_binding != null) settingAdapter.notifyDataSetChanged()
        }
        if (_binding != null) {
            binding.btnEditMsgChannel.isEnabled = enabled
            binding.btnEditMsgChannel.alpha = if (enabled) 1f else 0.45f
        }
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
        // 更新节假日：触发型字段（快照恒为 false），点击即下发一次并复位；成功提示等被控端 ACK 回执
        if (item.key == "uh") {
            if (!on) return
            onToggle?.invoke(item, true)
            item.value = false
            settingAdapter.notifyDataSetChanged()
            return
        }
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

    /** 智能提醒等「当日时间点」：用 MaterialTimePicker，value 为当日分钟数 0~1439 */
    private fun showTimePicker(item: SettingItem) {
        val minutes = ((item.value as? Int) ?: 0).coerceIn(0, 1439)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setTitleText(item.label)
            .setHour(minutes / 60)
            .setMinute(minutes % 60)
            .build()
        picker.addOnPositiveButtonClickListener {
            val v = (picker.hour * 60 + picker.minute).coerceIn(0, 1439)
            item.value = v
            onIntChange?.invoke(item, v)
            settingAdapter.notifyDataSetChanged()
        }
        picker.show(parentFragmentManager, "setting_time_${item.key}")
    }

    private fun showSliderIfNeeded(item: SettingItem) {
        val dlgBinding = DialogSliderBinding.inflate(LayoutInflater.from(requireContext()))
        val unit = unitFor(item.key)
        val opts = item.options
        // 离散档位滑块：与被控端伪息屏延迟档位完全一致（非线性，时间越大间隔越大）
        if (opts != null && opts.isNotEmpty()) {
            val current = (item.value as? Int) ?: opts[0]
            var index = 0
            var bestDiff = Int.MAX_VALUE
            opts.forEachIndexed { i, v ->
                val diff = kotlin.math.abs(v - current)
                if (diff < bestDiff) { bestDiff = diff; index = i }
            }
            dlgBinding.tvSliderValue.text = "${opts[index]} $unit"
            dlgBinding.slider.apply {
                valueFrom = 0f
                valueTo = (opts.size - 1).toFloat()
                stepSize = 1f
                value = index.toFloat()
                addOnChangeListener { _, value, _ ->
                    dlgBinding.tvSliderValue.text = "${opts[value.toInt().coerceIn(0, opts.lastIndex)]} $unit"
                }
            }
            UnifiedDialogKit.showForm(
                ctx = requireContext(),
                contentView = dlgBinding.root,
                title = item.label,
                positiveText = "保存",
                negativeText = "取消",
                onConfirm = {
                    val idx = dlgBinding.slider.value.toInt().coerceIn(0, opts.lastIndex)
                    val v = opts[idx]
                    item.value = v
                    onIntChange?.invoke(item, v)
                    true
                }
            )
            return
        }
        val min = item.min ?: 0
        val max = item.max ?: 100
        val step = item.step ?: 1
        val refreshValue = { dlgBinding.tvSliderValue.text = "${item.value as? Int ?: min} $unit" }
        dlgBinding.tvSliderValue.text = "${item.value as? Int ?: min} $unit"
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
        UnifiedDialogKit.showForm(
            ctx = requireContext(),
            contentView = dlgBinding.root,
            title = item.label,
            positiveText = "保存",
            negativeText = "取消",
            onConfirm = {
                val v = item.value as? Int ?: min
                onIntChange?.invoke(item, v)
                true
            }
        )
    }

    /** 屏幕模式（镜像自被控端）：0 伪息屏 / 1 息屏 / 2 常亮；
     * 选择「息屏」需先弹完整提示确认（三步前置设置 + 风险声明），确认后才应用 */
    private fun showScreenModeDialog(item: SettingItem) {
        if (!item.writable) {
            android.widget.Toast.makeText(
                requireContext(),
                "伪息屏开启时由伪息屏策略接管，不可修改屏幕模式",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val current = ((item.value as? Int) ?: 0).coerceIn(0, 2)
        val options = listOf(
            "伪息屏：前台无操作达到延迟后自动盖全黑蒙层",
            "息屏：允许系统按超时自然灭屏（可能导致任务无法正常执行）",
            "常亮：保持亮屏，阻止系统自动灭屏"
        )
        UnifiedDialogKit.showSingleChoice(
            requireContext(),
            item.label,
            options,
            current
        ) { which ->
            // 选择「息屏」：高风险切换，弹与被控端一致的完整提示（三步前置设置 + 风险声明），确认后才应用
            if (which == 1) {
                UnifiedDialogKit.showConfirm(
                    requireContext(),
                    "确定使用息屏模式？",
                    "为保证打卡任务正常进行，请先完成以下设置：\n1. 关闭锁屏密码；\n2. 打开开发者选项，开启「直接进入系统」选项；\n3. 关闭开发者选项。\n\n⚠ 息屏模式下，不能确保任务一定能正常执行，确定使用前，请充分测试验证。",
                    confirmText = "确认使用",
                    cancelText = "取消",
                    icon = UnifiedDialogKit.IconType.WARNING,
                    onCancel = { settingAdapter.notifyDataSetChanged() },
                    onConfirm = {
                        item.value = which
                        onIntChange?.invoke(item, which)
                        settingAdapter.notifyDataSetChanged()
                    }
                )
            } else {
                item.value = which
                onIntChange?.invoke(item, which)
                settingAdapter.notifyDataSetChanged()
            }
        }
    }

    /** 自定义工作日：多选星期（周一~周日），序列化为 "1,2,3,4,5" 下发 cw 字段 */
    private fun showWorkdayDialog(item: SettingItem) {
        val raw = item.value.toString()
        val selected = parseWorkdayValues(raw).toMutableSet()
        val labels = WEEKDAY_LABELS
        val checked = BooleanArray(7) { it + 1 in selected }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("自定义工作日")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) selected.add(which + 1) else selected.remove(which + 1)
            }
            .setPositiveButton("保存") { _, _ ->
                if (selected.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "至少保留一天为工作日", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val serialized = serializeWorkdayValues(selected)
                item.value = serialized
                onStringChange?.invoke(item, serialized)
                settingAdapter.notifyDataSetChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun parseWorkdayValues(raw: String): Set<Int> =
        raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

    private fun serializeWorkdayValues(values: Set<Int>): String =
        (1..7).filter { it in values }.joinToString(",")

    private fun unitFor(key: String): String = when (key) {
        "tm" -> "秒"
        "ot" -> "秒"
        "tr" -> "分"
        "rh" -> "时"
        "lb" -> "%"
        "bw" -> "分"
        "bs" -> "段"
        "br" -> "时"
        "bd" -> "时"
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
