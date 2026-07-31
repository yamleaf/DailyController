package com.yample.daily.controller

/**
 * 绑定二维码载荷（与 DailyTask 端 com.pengxh.daily.protocol.BindingPayload 字段一致）。
 * 最终模型：仅含「控制端 CTL 凭证 + 配对令牌」，会话密钥由两端在配对握手时经
 * HKDF(pairingToken || deviceId) 独立派生，不进二维码。
 */
data class BindingPayload(
    val broker: String,
    val deviceId: String,
    val ctlUser: String,
    val ctlPass: String,
    val pairingToken: String
)
