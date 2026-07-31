package com.yample.daily.controller

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yample.daily.controller.databinding.ActivityDeviceControlBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import com.google.gson.Gson
import androidx.room.Room

class DeviceControlActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDeviceControlBinding
    private lateinit var device: DeviceRecord
    private lateinit var db: AppDatabase
    private var mqttClient: MqttClient? = null
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        device = intent.getSerializableExtra("device") as DeviceRecord
        db = Room.databaseBuilder(this, AppDatabase::class.java, "daily-db").build()

        binding.tvDeviceName.text = device.name
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.switchPowerSave.setOnCheckedChangeListener { _, isChecked ->
            sendUpdate("ps", PacketValue.BooleanValue(isChecked))
        }
        binding.switchForcePseudoMask.setOnCheckedChangeListener { _, isChecked ->
            sendUpdate("pm", PacketValue.BooleanValue(isChecked))
        }
        binding.sliderTimer.addOnChangeListener { _, value, _ ->
            binding.tvTimerValue.text = "${value.toInt()} 秒"
            sendUpdate("tm", PacketValue.IntValue(value.toInt()))
        }

        binding.btnUnbind.setOnClickListener { confirmUnbind() }

        setConnStatus("连接中…", false)
        initMqtt()
    }

    private fun initMqtt() {
        mqttClient = MqttClient(device.broker, "ctl-" + device.deviceId, MemoryPersistence())
        val options = MqttConnectOptions().apply {
            userName = device.ctlUser
            password = device.ctlPass.toCharArray()
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 60
        }
        try {
            mqttClient?.connect(options)
            mqttClient?.subscribe("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/status", 1)
            mqttClient?.subscribe("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/ack", 1)
            mqttClient?.subscribe("${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/pair/accept", 1)
            onConnected()
            // 已配对（本地存有会话密钥）则跳过握手；否则发起配对
            if (device.sessionSecret.isBlank()) {
                publishPair()
            } else {
                setConnStatus("已连接（已配对）", true)
            }
            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    runOnUiThread { setConnStatus("连接断开", false) }
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.payload?.let { handleIncoming(topic ?: "", String(it)) }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
        } catch (e: MqttException) {
            e.printStackTrace()
            runOnUiThread { setConnStatus("连接失败", false) }
            Toast.makeText(this, "MQTT 连接失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onConnected() {
        runOnUiThread { setConnStatus("已连接", true) }
    }

    /** 发起配对：携带 pairingToken 到 dt/{id}/pair */
    private fun publishPair() {
        val ts = System.currentTimeMillis()
        val rid = java.util.UUID.randomUUID().toString()
        val packet = MqttPacket(
            c = MqttPacket.CMD_PAIR,
            f = "",
            v = PacketValue.StringValue(device.pairingToken),
            rid = rid,
            ts = ts,
            sign = ""
        )
        try {
            mqttClient?.publish(
                "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/pair",
                MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 }
            )
            runOnUiThread { setConnStatus("已连接（配对中…）", true) }
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    private fun handleIncoming(topic: String, payload: String) {
        when {
            topic.endsWith("/status") -> {
                // status 主题为纯文本（online / offline / unbound），非 MqttPacket
                val online = payload.trim() == "online"
                runOnUiThread { setConnStatus(if (online) "已连接" else "设备离线/已解绑", online) }
            }
            topic.endsWith("/pair/accept") -> onPairAccepted()
            topic.endsWith("/ack") -> {
                try {
                    val packet = gson.fromJson(payload, MqttPacket::class.java)
                    runOnUiThread {
                        Toast.makeText(this, "设备回执：${packet?.v?.toStringValue() ?: "-"}", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 配对成功：HKDF 派生会话密钥并持久化 */
    private fun onPairAccepted() {
        val session = Hkdf.deriveHex(
            device.pairingToken,
            device.deviceId,
            MqttPacket.PAIRING_INFO,
            MqttPacket.SESSION_KEY_LEN
        )
        device = device.copy(sessionSecret = session, pairingToken = "", bound = true)
        lifecycleScope.launch(Dispatchers.IO) {
            db.deviceDao().update(device)
        }
        runOnUiThread {
            setConnStatus("已连接（已配对）", true)
            Toast.makeText(this, "已与控制端完成配对", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendUpdate(field: String, value: PacketValue) {
        if (device.sessionSecret.isBlank()) {
            Toast.makeText(this, "尚未完成配对，无法下发指令", Toast.LENGTH_SHORT).show()
            return
        }
        val ts = System.currentTimeMillis()
        val rid = java.util.UUID.randomUUID().toString()
        val (type, vStr) = when (value) {
            is PacketValue.BooleanValue -> "b" to value.b.toString()
            is PacketValue.IntValue -> "i" to value.i.toString()
            is PacketValue.StringValue -> "s" to value.s
        }
        val sign = MqttSigner.sign(device.sessionSecret, device.deviceId, ts, rid, field, type, vStr, "U")
        val packet = MqttPacket("U", field, value, rid, ts, sign)
        try {
            mqttClient?.publish(
                "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/cmd",
                MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 }
            )
        } catch (e: MqttException) {
            e.printStackTrace()
            Toast.makeText(this, "指令发送失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmUnbind() {
        AlertDialog.Builder(this)
            .setTitle("解绑设备")
            .setMessage("确定从控制端移除该设备？被控端将收到解绑通知并清除绑定。本机 MQTT 配置（被控端填写的服务器/账号）不受影响。")
            .setPositiveButton("解绑") { _, _ -> doUnbind() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doUnbind() {
        // 通知被控端解绑（dt/{id}/pair，c=UB）
        val packet = MqttPacket(
            c = MqttPacket.CMD_UNBOUND,
            f = "",
            v = PacketValue.StringValue(""),
            rid = java.util.UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            sign = ""
        )
        try {
            mqttClient?.publish(
                "${MqttPacket.TOPIC_PREFIX}/${device.deviceId}/pair",
                MqttMessage(gson.toJson(packet).toByteArray()).apply { qos = 1 }
            )
        } catch (_: MqttException) {
        }
        lifecycleScope.launch(Dispatchers.IO) {
            db.deviceDao().deleteById(device.deviceId)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@DeviceControlActivity, "已解绑", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun setConnStatus(text: String, online: Boolean) {
        binding.tvConnStatus.text = text
        binding.dotStatus.setBackgroundResource(
            if (online) com.yample.daily.controller.R.drawable.bg_dot_online
            else com.yample.daily.controller.R.drawable.bg_dot_offline
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (_: Exception) {
        }
    }
}
