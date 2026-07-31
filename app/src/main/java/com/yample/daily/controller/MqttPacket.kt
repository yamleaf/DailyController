package com.yample.daily.controller

import androidx.annotation.Keep

@Keep
sealed class PacketValue {
    data class BooleanValue(val b: Boolean) : PacketValue()
    data class IntValue(val i: Int) : PacketValue()
    data class StringValue(val s: String) : PacketValue()

    fun toBooleanStrict(): Boolean = (this as BooleanValue).b
    fun toInt(): Int = (this as IntValue).i
    fun toStringValue(): String = (this as StringValue).s
}

@Keep
data class MqttPacket(
    val c: String,          // S, U, A, N, P, PA, UB
    val f: String,          // ps, pm, tm ...
    val v: PacketValue?,
    val rid: String,
    val ts: Long,
    val sign: String
) {
    companion object {
        const val CMD_SYNC = "S"
        const val CMD_UPDATE = "U"
        const val CMD_ACK = "A"
        const val CMD_NOTIFY = "N"
        const val CMD_PAIR = "P"          // 控制端发起配对（携带 pairingToken）
        const val CMD_PAIR_ACCEPT = "PA"  // 被控端配对成功回执
        const val CMD_UNBOUND = "UB"      // 解除绑定

        const val TOPIC_PREFIX = "dt"     // 最终落地主题前缀
        const val PAIRING_INFO = "daily-pairing-v1"
        const val SESSION_KEY_LEN = 32
    }
}
