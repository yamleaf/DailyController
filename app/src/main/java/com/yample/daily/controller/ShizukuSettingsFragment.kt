package com.yample.daily.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.yample.daily.controller.databinding.FragmentShizukuSettingsBinding
import com.yample.mqttprotocol.Protocol

/**
 * Shizuku 高级设置镜像页（feat_shiziku，独立文件）。
 *
 * - 状态卡：展示被控端快照上报的 Shizuku 配置摘要（sz_*，只读）。
 * - 配置编辑：登录方式 / 登录步骤 / 验证码等待 / 身份验证步骤 / 等待，
 *   保存后经 FIELD_SHIZUKU_CONFIG 下发到被控端（**不含密码明文**，密码仅被控端本地设置）。
 * - 操作：手动登录 / 身份验证（经 CMD_ACTION 下发，结果经 alert 通道回弹）。
 */
class ShizukuSettingsFragment : Fragment() {

    private var _binding: FragmentShizukuSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentShizukuSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnShizukuBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.btnSaveShizukuConfig.setOnClickListener { saveConfig() }
        binding.btnManualLogin.setOnClickListener { sendAction(Protocol.ACTION_MANUAL_LOGIN) }
        binding.btnIdentityVerify.setOnClickListener { sendAction(Protocol.ACTION_IDENTITY_VERIFY) }
        renderMirror()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 从宿主读取快照摘要并渲染只读状态卡 */
    private fun renderMirror() {
        val act = activity as? DeviceControlActivity ?: return
        val s = act.shizukuMirrorSummary()
        binding.txtSzEnabled.text = "高级功能：${if (s["sz_enabled"] == "true") "已开启" else "未开启"}"
        binding.txtSzMethod.text = "登录方式：${s["sz_method"] ?: "未知"}"
        binding.txtSzSteps.text = "登录步骤：${s["sz_steps"] ?: "未知"}"
        binding.txtSzAuth.text = "身份验证步骤：${s["sz_auth"] ?: "未知"}"
        binding.txtPasswordMirror.text = if (s["hasPassword"] == null) {
            "密码：状态未知（请在被控端高级设置中设置）"
        } else {
            "密码：${if (s["hasPassword"] == "true") "已设置" else "未设置"}（修改请在被控端完成）"
        }
        // 登录方式回显
        binding.radioPassword.isChecked = s["sz_method"] != "验证码登录"
        binding.radioVerifyCode.isChecked = s["sz_method"] == "验证码登录"
    }

    /** 组装配置 JSON 下发（不含密码） */
    private fun saveConfig() {
        val act = activity as? DeviceControlActivity ?: return
        val method = if (binding.radioPassword.isChecked) "PASSWORD" else "VERIFY_CODE"
        val json = JsonObject().apply {
            addProperty("enabled", true)
            addProperty("method", method)
            add("loginSteps", parseSteps(binding.etLoginSteps.text.toString()))
            addProperty("verifyWait", binding.etVerifyWait.text.toString().toIntOrNull() ?: 60)
            add("authSteps", parseSteps(binding.etAuthSteps.text.toString()))
            addProperty("authWait", binding.etAuthWait.text.toString().toIntOrNull() ?: 60)
        }
        act.sendShizukuConfig(json.toString())
        Toast.makeText(requireContext(), "已下发 Shizuku 配置", Toast.LENGTH_SHORT).show()
    }

    /** "a, b，c→d" → [{"t":"a"},{"t":"b"}...] */
    private fun parseSteps(raw: String): JsonArray {
        val arr = JsonArray()
        raw.split(",", "，", "→")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { arr.add(JsonObject().apply { addProperty("t", it) }) }
        return arr
    }

    private fun sendAction(action: String) {
        val act = activity as? DeviceControlActivity ?: return
        val label = if (action == Protocol.ACTION_MANUAL_LOGIN) "手动登录" else "身份验证"
        act.sendShizukuAction(action)
        Toast.makeText(requireContext(), "已下发：$label", Toast.LENGTH_SHORT).show()
    }
}
