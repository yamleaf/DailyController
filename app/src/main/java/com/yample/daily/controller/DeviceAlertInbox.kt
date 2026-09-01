package com.yample.daily.controller

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
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
        val record = parse(json, rid, device.broker) ?: return null
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

    private fun parse(json: String, rid: String, broker: String = ""): AlertRecord? {
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
            Protocol.ALERT_TYPE_ID_CONFLICT -> {
                // 在位设备代报的异常接入事件；仅提示，不影响本机与在位设备的连接
                val challenger = obj.get("challenger")?.asString?.takeIf { it.isNotBlank() } ?: "未知设备"
                title = "⚠️ 设备ID冲突告警"
                val host = brokerHost(broker)
                val isPublic = host.contains("emqx.io", ignoreCase = true)
                if (isPublic) {
                    // 公共 broker：命名空间共享，ID 撞车可能是陌生人，需防凭据泄露
                    "你的设备正连着公共 MQTT 服务器（$host），另一台设备（${challenger}…）也在用相同的设备ID登录该服务器，已被你的设备拒绝。\n\n" +
                        "公共服务器上所有人都能看到彼此的客户端ID，设备ID撞车很常见。但对方能连上说明它还知道你的用户名/密码或主题规则，存在被人控制你设备的风险。\n\n" +
                        "建议立即处理：\n1. 在被控端修改一个别人猜不到的设备ID\n2. 更换 MQTT 用户名和密码\n3. 有条件的话换成自建/私有 MQTT 服务器"
                } else {
                    // 自建 broker：命名空间私有，基本是自己两台设备配了相同 ID
                    "你自建的 MQTT 服务器（$host）上出现了两台设备ID完全相同的设备：另一台（${challenger}…）正在尝试以相同ID登录。\n\n" +
                        "自建服务器只有你自己能用，这几乎可以肯定是：你有两台设备被设置成了同一个设备ID（比如克隆系统、恢复备份、或手动配置时填重复了）。\n\n" +
                        "两台同ID设备会互相顶替上线，导致指令错发、状态混乱。建议检查所有设备的ID设置，改成各不相同的ID。"
                }
            }
            Protocol.ALERT_TYPE_REMOTE_STOP -> {
                val from = obj.get("msg")?.asString ?: "远程终止任务"
                title = "🛑 任务被终止"
                from
            }
            Protocol.ALERT_TYPE_LOOP_OFF -> {
                val from = obj.get("msg")?.asString ?: "关闭每日循环"
                title = "⏸️ 每日循环已关闭"
                from
            }
            Protocol.ALERT_TYPE_PAUSED -> {
                val from = obj.get("msg")?.asString ?: "进入暂停使用"
                title = "⏹️ 已暂停使用"
                from
            }
            Protocol.ALERT_TYPE_TASK_RESET -> {
                val from = obj.get("msg")?.asString ?: "每日任务重置"
                title = "🔄 任务已重置"
                from
            }
            Protocol.ALERT_TYPE_UNBOUND -> {
                val from = obj.get("msg")?.asString ?: "解除绑定"
                title = "🔗 设备已解绑"
                from
            }
            Protocol.ALERT_TYPE_PUNCH_RESULT -> {
                val from = obj.get("msg")?.asString ?: "手动打卡结果"
                title = "📲 手动打卡结果"
                from
            }
            Protocol.ALERT_TYPE_VERIFY_CODE_REQUEST -> {
                // Shizuku：请求验证码输入回填；msg 为被控端原始 JSON，弹窗需按字段解析
                val raw = obj.get("msg")?.asString ?: ""
                title = "📩 验证码输入"
                raw
            }
            Protocol.ALERT_TYPE_SMS_CAPTURE -> {
                // Shizuku：钉钉短信采集（内容+收件人）；msg 为被控端原始 JSON，弹窗需按字段解析
                val raw = obj.get("msg")?.asString ?: ""
                title = "📲 发送短信验证"
                raw
            }
            Protocol.ALERT_TYPE_RESULT_SCREENSHOT -> {
                // Shizuku：结果判定截图已回传，等待人工确认；msg 为被控端原始 JSON
                val raw = obj.get("msg")?.asString ?: ""
                title = "🔍 结果确认"
                raw
            }
            Protocol.ALERT_TYPE_LOGIN_RESULT -> {
                val from = obj.get("msg")?.asString ?: "登录结果"
                title = "🔐 登录结果"
                from
            }
            Protocol.ALERT_TYPE_VERIFY_RESULT -> {
                val from = obj.get("msg")?.asString ?: "验证结果"
                title = "🛡️ 验证结果"
                from
            }
            Protocol.ALERT_TYPE_SIMULATE_PUNCH_RESULT -> {
                val from = obj.get("msg")?.asString ?: "模拟打卡结果"
                title = "🕐 模拟打卡结果"
                from
            }
            Protocol.ALERT_TYPE_TASK_START -> {
                val from = obj.get("msg")?.asString ?: "远程启动任务"
                title = "▶️ 任务已启动"
                from
            }
            Protocol.ALERT_TYPE_LOOP_ON -> {
                val from = obj.get("msg")?.asString ?: "开启每日循环"
                title = "⏯️ 每日循环已开启"
                from
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

    /** 从 "tcp://host:port" / "host:port" 形式的 broker 串中提取主机名 */
    private fun brokerHost(broker: String): String =
        broker.trim().substringAfter("://").substringBefore(':').ifBlank { "未知服务器" }

    /**
     * AQ 告警回放入库：把回放条目 {aid, occurredAt, alert} 转成历史记录写入 [AlertHistory]，
     * rid=aid 幂等去重，时间戳用被控端原始发生时刻。
     * @return 新写入的记录；格式非法 / 已存在 / 载荷为空时返回 null
     */
    fun acceptReplayed(ctx: Context, device: DeviceRecord, entry: JsonObject?): AlertRecord? {
        if (entry == null) return null
        val aid = entry.get("aid")?.asString.orEmpty()
        if (aid.isBlank()) return null
        val body = entry.getAsJsonObject("alert") ?: return null
        val base = parse(body.toString(), aid, device.broker) ?: return null
        val occurredAt = entry.get("occurredAt")?.asLong ?: 0L
        val record = if (occurredAt > 0) base.copy(ts = occurredAt) else base
        return if (AlertHistory.add(ctx, device.deviceId, record)) record else null
    }
}
