package com.yample.daily.controller

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 设备告警记录（被控端 dt/{id}/alert 事件，含低电量 / 充电 / 充满 / 智能预警） */
data class AlertRecord(
    val ts: Long,               // 收到时间戳
    val type: String,           // low_battery | charging_resumed | battery_full | battery_smart_alert
    val title: String,          // 弹窗标题（含 emoji）
    val msg: String,            // 弹窗正文
    val battery: Int = -1,
    val threshold: Int = -1,
    val stage: Int = 1,
    val predictedTime: String = ""
)

/** 告警历史持久化：按设备维度存 SharedPreferences，最新在前，最多 30 条 */
object AlertHistory {

    private const val MAX = 30
    private const val PREFS = "remote_ctrl"
    private fun key(deviceId: String) = "alert_history_${deviceId}"

    fun load(ctx: Context, deviceId: String): List<AlertRecord> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(key(deviceId), null) ?: return emptyList()
        return try {
            val type = object : TypeToken<ArrayList<AlertRecord>>() {}.type
            val list = Gson().fromJson<ArrayList<AlertRecord>>(json, type)
            list.filter { it.msg.isNotBlank() && it.ts > 0L }
        } catch (_: Exception) { emptyList() }
    }

    fun add(ctx: Context, deviceId: String, record: AlertRecord) {
        val list = load(ctx, deviceId).toMutableList()
        list.add(0, record)
        while (list.size > MAX) list.removeAt(list.size - 1)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key(deviceId), Gson().toJson(list)).apply()
    }

    fun clear(ctx: Context, deviceId: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(deviceId)).apply()
    }
}
