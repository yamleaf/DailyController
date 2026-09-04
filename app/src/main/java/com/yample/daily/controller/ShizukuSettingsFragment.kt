package com.yample.daily.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.yample.daily.controller.databinding.FragmentShizukuSettingsBinding
import com.yample.mqttprotocol.Protocol

/**
 * Shizuku 高级设置镜像页（feat_shiziku，设备详情子 tab，入口在设置 tab，独立文件）。
 *
 * 三大块（风格与 DC 整体一致，只读镜像为主）：
 *  - 服务权限状态区：镜像被控端服务卡（Shizuku 通道 / 服务 / 授权来源 / 开发者选项 / 无线调试 / ADB 状态），只读。
 *  - 操作区：密码登录 / 验证码登录 / 身份验证 / 模拟打卡 / 手动截屏 / 操作1（并列按钮，2×3）。
 *  - 配置区：5 个操作卡片步骤**只读镜像**（步骤由被控端本地维护，控制端不编辑不下发）。
 *
 * 可用性：仅当被控端服务可用且已授权（sz_granted=已授权）时，操作才可下发（授权即开启，无独立开关）。
 */
class ShizukuSettingsFragment : Fragment(), SnapshotFragment {

    private var _binding: FragmentShizukuSettingsBinding? = null
    private val binding get() = _binding!!

    private var snapshot: DeviceSnapshot? = null

    /** 解绑态禁用（由宿主统一控制；恢复后 render 还原） */
    private var commandsEnabled = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentShizukuSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnManualLogin.setOnClickListener { if (editable()) sendAction(Protocol.ACTION_MANUAL_LOGIN) }
        binding.btnIdentityVerify.setOnClickListener { if (editable()) sendAction(Protocol.ACTION_IDENTITY_VERIFY) }
        binding.btnSimulatePunch.setOnClickListener { if (editable()) sendAction(Protocol.ACTION_SIMULATE_PUNCH) }
        binding.btnVerifyLogin.setOnClickListener { if (editable()) sendAction(Protocol.ACTION_VERIFY_LOGIN) }
        binding.btnScreenshot.setOnClickListener { if (editable()) sendAction(Protocol.ACTION_SCREENSHOT) }
        binding.btnCustom1.setOnClickListener { if (editable()) sendAction(Protocol.ACTION_CUSTOM_1) }
        snapshot?.let { render(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun refresh(snapshot: DeviceSnapshot) {
        this.snapshot = snapshot
        if (isAdded && _binding != null) render(snapshot)
    }

    /** 解绑态统一禁用（由宿主在设备未绑定时调用） */
    fun setCommandsEnabled(enabled: Boolean) {
        commandsEnabled = enabled
        val s = snapshot ?: return
        if (_binding != null) render(s)
    }

    /** 服务可用 + 已授权 且 未解绑 → 操作/配置可用（授权即开启，无独立开关） */
    private fun editable(): Boolean =
        commandsEnabled && strOf("sz_granted") == "已授权"

    private fun settingOf(key: String): SettingItem? = snapshot?.settings?.firstOrNull { it.key == key }

    private fun strOf(key: String): String = settingOf(key)?.value?.toString().orEmpty()

    private fun render(s: DeviceSnapshot) {
        // 服务权限状态（镜像被控端服务卡 6 行，只读）
        binding.txtSzChannel.text = strOf("sz_channel").ifBlank { "不可用" }
        binding.txtSzServer.text = strOf("sz_server").ifBlank { "未知" }
        binding.txtSzAuthSource.text = strOf("sz_authSource").ifBlank { "不可用" }
        binding.txtSzDevOpt.text = strOf("sz_devOpt").ifBlank { "已关闭" }
        binding.txtSzWirelessAdb.text = strOf("sz_wirelessAdb").ifBlank { "N/A" }
        binding.txtSzAdbStatus.text = strOf("sz_adbStatus").ifBlank { "N/A" }

        // 5 个操作只读镜像（步骤串来自被控端快照，控制端不编辑不下发）
        binding.txtMirrorPwdSteps.text = strOf("sz_pwdStepsLabel").ifBlank { "未配置" }
        binding.txtMirrorVerifySteps.text = strOf("sz_verifyStepsLabel").ifBlank { "未配置" }
        binding.txtMirrorAuthSteps.text = strOf("sz_authStepsLabel").ifBlank { "未配置" }
        binding.txtMirrorPunchSteps.text = strOf("sz_punchStepsLabel").ifBlank { "未配置" }
        binding.txtMirrorCustom1Steps.text = strOf("sz_custom1StepsLabel").ifBlank { "未配置" }
        listOf(
            binding.txtMirrorPwdSteps, binding.txtMirrorVerifySteps,
            binding.txtMirrorAuthSteps, binding.txtMirrorPunchSteps,
            binding.txtMirrorCustom1Steps
        ).forEach { it.isSelected = true }

        // 操作1 名称（被控端高级设置可改名，快照镜像同步回显）
        val op1 = strOf("sz_opName1").ifBlank { "操作1" }
        binding.txtCustom1Name.text = op1
        binding.txtMirrorCustom1Title.text = op1

        // 可用性：仅操作区（配置区为只读镜像，无需置灰）
        val on = editable()
        binding.btnManualLogin.isEnabled = on
        binding.btnIdentityVerify.isEnabled = on
        binding.btnSimulatePunch.isEnabled = on
        binding.btnVerifyLogin.isEnabled = on
        binding.btnScreenshot.isEnabled = on
        binding.btnCustom1.isEnabled = on
        val alpha = if (on) 1f else 0.45f
        binding.btnManualLogin.alpha = alpha
        binding.btnIdentityVerify.alpha = alpha
        binding.btnSimulatePunch.alpha = alpha
        binding.btnVerifyLogin.alpha = alpha
        binding.btnScreenshot.alpha = alpha
        binding.btnCustom1.alpha = alpha
    }

    private fun sendAction(action: String) {
        val act = activity as? DeviceControlActivity ?: return
        val op1 = strOf("sz_opName1").ifBlank { "操作1" }
        val label = when (action) {
            Protocol.ACTION_MANUAL_LOGIN -> "密码登录"
            Protocol.ACTION_VERIFY_LOGIN -> "验证码登录"
            Protocol.ACTION_IDENTITY_VERIFY -> "身份验证"
            Protocol.ACTION_SIMULATE_PUNCH -> "模拟打卡"
            Protocol.ACTION_SCREENSHOT -> "手动截屏"
            Protocol.ACTION_CUSTOM_1 -> op1
            else -> action
        }
        act.sendShizukuAction(action)
        Toast.makeText(requireContext(), "已下发：$label", Toast.LENGTH_SHORT).show()
    }
}