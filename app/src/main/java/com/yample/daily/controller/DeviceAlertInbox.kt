package com.yample.daily.controller

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.yample.mqttprotocol.MqttPacket
import com.yample.mqttprotocol.MqttSigner
import com.yample.mqttprotocol.PacketValue
import com.yample.mqttprotocol.PacketValueAdapter
import com.yample.mqttprotocol.Protocol
import com.yample.mqttprotocol.SecretBox

/**
 * 被控端 dt/{id}/alert 统一入库：验签 → 解密 → 解析 → 按 rid 去重写入 [AlertHistory]。
 * 供设备控制页与后台监测服务共用，避免重复历史。
 */
object DeviceAlertInbox {

    private val gson = GsonBuilder()
        .registerTypeAdapter(PacketValue::class.java, PacketValueAdapter)
        .create()

    /**
     * @return 新写入的告警；验签失败 / 解密失败 / rid 重复则返回 null
     */
    fun accept(ctx: Context, device: DeviceRecord, payload: String): AlertRecord? {
        val packet = try {
            gson.fromJson(payload, MqttPacket::class.java)
        } catch (_: Exception) {
            null
        } ?: return null
        if (!verify(device, packet)) return null
        val rid = packet.rid.orEmpty()
        val wire = packet.v?.toStringValue() ?: return null
        val json = try {
            SecretBox.open(device.sessionSecret, wire)
        } catch (_: Exception) {
            return null
        }
        val record = parse(json, rid) ?: return null
        if (!AlertHistory.add(ctx, device.deviceId, record)) return null
        return record
    }

    private fun verify(device: DeviceRecord, packet: MqttPacket): Boolean {
        val session = device.sessionSecret
        if (session.isBlank()) return false
        val json = packet.v?.toStringValue() ?: return false
        val expected = MqttSigner.sign(
            session, device.deviceId, packet.ts, packet.rid, packet.f, "s", json, Protocol.CMD_ALERT
        )
        return expected == packet.sign
    }

    private fun parse(json: String, rid: String): AlertRecord? {
        val obj = try {
            JsonParser.parseString(json).asJsonObject
        } catch (_: Exception) {
            return null
        }
        val type = obj.get("type")?.asString ?: return null
        val battery = if (obj.has("battery")) obj.get("battery").asInt else -1
        var threshold = -1
        var stage = 1
        var predictedTime = ""
        val title: String
        val msg = when (type) {
            "low_battery" -> {
                threshold = if (obj.has("threshold")) obj.get("threshold").asInt else -1
                stage = if (obj.has("stage")) obj.get("stage").asInt else 1
                title = "🔋 低电量告警"
                "被控端电量 ${battery}%${if (threshold > 0) "，已低于阈值 ${threshold}%" else ""}（第${stage}档）"
            }
            "charging_resumed" -> {
                title = "⚡ 已开始充电"
                "被控端已开始充电（${battery}%），低电量告警已取消"
            }
            "battery_full" -> {
                title = "🔋 电量已充满"
                "被控端电量已充满（${battery}%），可拔除电源"
            }
            "battery_smart_alert" -> {
                predictedTime = obj.get("predictedTime")?.asString ?: ""
                title = "⚠️ 电量智能预警"
                "设备电量预计 $predictedTime 降至低电量阈值，请及时充电"
            }
            else -> {
                title = "收到被控端告警"
                "告警类型：$type"
            }
        }
        return AlertRecord(
            ts = System.currentTimeMillis(),
            type = type,
            title = title,
            msg = msg,
            battery = battery,
            threshold = threshold,
            stage = stage,
            predictedTime = predictedTime,
            rid = rid
        )
    }
}
