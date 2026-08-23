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
    val predictedTime: String = "",
    val rid: String = ""        // MQTT 包 rid，用于控制页与后台监测去重
)

/** 告警历史持久化：按设备维度存 SharedPreferences，最新在前，最多 30 条 */
object AlertHistory {

    private const val MAX = 30
    private const val PREFS = "remote_ctrl"
    /** 清除黑名单上限：FIFO 淘汰，防无限膨胀 */
    private const val CLEARED_MAX = 200
    private fun key(deviceId: String) = "alert_history_${deviceId}"
    private fun clearedKey(deviceId: String) = "alert_cleared_${deviceId}"

    fun load(ctx: Context, deviceId: String): List<AlertRecord> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(key(deviceId), null) ?: return emptyList()
        return try {
            val type = object : TypeToken<ArrayList<AlertRecord>>() {}.type
            val list = Gson().fromJson<ArrayList<AlertRecord>>(json, type)
            list.filter { it.msg.isNotBlank() && it.ts > 0L }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * @return true 表示新写入；同 rid 已存在、或命中清除黑名单（用户已清空的记录被 AQ 回放/重发）
     * 则跳过并返回 false
     */
    @Synchronized
    fun add(ctx: Context, deviceId: String, record: AlertRecord): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (isCleared(prefs, deviceId, record)) return false
        val list = load(ctx, deviceId).toMutableList()
        if (record.rid.isNotBlank() && list.any { it.rid == record.rid }) return false
        list.add(0, record)
        while (list.size > MAX) list.removeAt(list.size - 1)
        prefs.edit().putString(key(deviceId), Gson().toJson(list)).apply()
        return true
    }

    /**
     * 清空历史并把现有记录指纹（rid 优先，缺失时 type|msg 兜底，如本地生成的 device_offline）
     * 并入清除黑名单——被控端环形缓冲仍在，AQ 回放会让已清空告警「复活」，此处按指纹拦截。
     */
    @Synchronized
    fun clear(ctx: Context, deviceId: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cleared = loadCleared(prefs, deviceId).toMutableList()
        load(ctx, deviceId).forEach { r ->
            cleared += fingerprint(r)
        }
        while (cleared.size > CLEARED_MAX) cleared.removeAt(0)
        prefs.edit()
            .putString(clearedKey(deviceId), Gson().toJson(cleared))
            .remove(key(deviceId))
            .apply()
    }

    private fun fingerprint(r: AlertRecord): String =
        if (r.rid.isNotBlank()) "r:${r.rid}" else "f:${r.type}|${r.msg}"

    private fun loadCleared(prefs: android.content.SharedPreferences, deviceId: String): List<String> {
        val json = prefs.getString(clearedKey(deviceId), null) ?: return emptyList()
        return try {
            Gson().fromJson(json, object : TypeToken<ArrayList<String>>() {}.type)
        } catch (_: Exception) { emptyList() }
    }

    private fun isCleared(
        prefs: android.content.SharedPreferences,
        deviceId: String,
        record: AlertRecord
    ): Boolean {
        val fp = fingerprint(record)
        return loadCleared(prefs, deviceId).any { it == fp }
    }
}
