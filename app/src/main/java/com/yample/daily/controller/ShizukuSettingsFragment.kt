package com.yample.daily.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.yample.daily.controller.databinding.FragmentShizukuSettingsBinding
import com.yample.mqttprotocol.Protocol

/**
 * Shizuku 高级设置镜像页（feat_shiziku，设备详情子 tab，入口在设置 tab，独立文件）。
 *
 * 三大块（风格与 DC 整体一致，服务状态胶囊化）：
 *  - 服务权限状态区：被控端快照 sz_status / sz_granted，胶囊 chip 只读。
 *  - 操作区：手动登录 / 身份验证 / 模拟打卡（并列按钮）。
 *  - 配置区：登录方式 / 验证码超时（可配置下发）；登录步骤与身份验证步骤**只读展示**，
 *    由被控端本地维护，控制端不编辑不下发（密码亦不落网络）。
 *
 * 可用性：仅当被控端服务可用且已授权（sz_granted=已授权）时，操作与配置才可下发（授权即开启，无独立开关）。
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
        binding.btnSaveShizukuConfig.setOnClickListener { if (editable()) saveConfig() }
        binding.btnManualLogin.setOnClickListener { if (editable()) sendAction(Protocol.ACTION_MANUAL_LOGIN) }
        binding.btnIdentityVerify.setOnClickListener { if (editable()) sendAction(Protocol.ACTION_IDENTITY_VERIFY) }
        binding.btnSimulatePunch.setOnClickListener { if (editable()) sendAction(Protocol.ACTION_SIMULATE_PUNCH) }
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
        val service = strOf("sz_status").ifBlank { "未知" }
        val granted = strOf("sz_granted").ifBlank { "未知" }

        binding.txtSzService.text = "服务：$service"
        binding.txtSzGranted.text = "授权：$granted"
        chipColor(binding.txtSzService, service == "可用")
        chipColor(binding.txtSzGranted, granted == "已授权")

        // 登录方式回显
        val method = strOf("sz_method")
        binding.radioPassword.isChecked = method != "验证码登录"
        binding.radioVerifyCode.isChecked = method == "验证码登录"
        binding.txtPasswordMirror.text = "密码:${if (strOf("sz_hasPassword") == "true") "已设置" else "未设置"}（修改请在被控端完成）"

        // 超时回显（供编辑下发）
        val verifyWait = settingOf("sz_verifyWait")?.value?.toString().orEmpty()
        if (verifyWait.isNotBlank()) binding.etVerifyWait.setText(verifyWait)
        val authWait = settingOf("sz_authWait")?.value?.toString().orEmpty()
        if (authWait.isNotBlank()) binding.etAuthWait.setText(authWait)

        // 步骤只读展示（来自快照，控制端不编辑不下发；备注优先显示步骤串，单行超宽用 marquee 滚动）
        binding.txtLoginSteps.text = strOf("sz_pwdStepsLabel").let { pwd ->
            val verify = strOf("sz_verifyStepsLabel")
            val cur = if (method == "验证码登录" && verify.isNotBlank()) verify else pwd
            if (cur.isBlank()) "未配置" else cur
        }
        binding.txtAuthSteps.text = strOf("sz_authStepsLabel")
            .let { if (it.isBlank()) "未配置" else it }
        binding.txtPunchSteps.text = strOf("sz_punchStepsLabel")
            .let { if (it.isBlank()) "未配置" else it }
        binding.txtLoginSteps.isSelected = true
        binding.txtAuthSteps.isSelected = true
        binding.txtPunchSteps.isSelected = true

        // 可用性：操作区 + 配置区
        val on = editable()
        binding.btnManualLogin.isEnabled = on
        binding.btnIdentityVerify.isEnabled = on
        binding.btnSimulatePunch.isEnabled = on
        binding.btnSaveShizukuConfig.isEnabled = on
        val alpha = if (on) 1f else 0.45f
        binding.btnManualLogin.alpha = alpha
        binding.btnIdentityVerify.alpha = alpha
        binding.btnSimulatePunch.alpha = alpha
        binding.btnSaveShizukuConfig.alpha = alpha
    }

    private fun chipColor(tv: TextView, ok: Boolean) {
        tv.setTextColor(ContextCompat.getColor(requireContext(),
            if (ok) R.color.md_success else R.color.md_warning))
    }

    /** 组装配置 JSON 下发：仅登录方式 + 超时；步骤由被控端本地维护，不在此下发 */
    private fun saveConfig() {
        val act = activity as? DeviceControlActivity ?: return
        val method = if (binding.radioPassword.isChecked) "PASSWORD" else "VERIFY_CODE"
        val json = JsonObject().apply {
            addProperty("method", method)
            addProperty("verifyWait", binding.etVerifyWait.text.toString().toIntOrNull() ?: 60)
            addProperty("authWait", binding.etAuthWait.text.toString().toIntOrNull() ?: 60)
        }
        act.sendShizukuConfig(json.toString())
        Toast.makeText(requireContext(), "已下发配置", Toast.LENGTH_SHORT).show()
    }

    private fun sendAction(action: String) {
        val act = activity as? DeviceControlActivity ?: return
        val label = when (action) {
            Protocol.ACTION_MANUAL_LOGIN -> "手动登录"
            Protocol.ACTION_SIMULATE_PUNCH -> "模拟打卡"
            else -> "身份验证"
        }
        act.sendShizukuAction(action)
        Toast.makeText(requireContext(), "已下发：$label", Toast.LENGTH_SHORT).show()
    }
}