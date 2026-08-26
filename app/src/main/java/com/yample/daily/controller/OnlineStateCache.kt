package com.yample.daily.controller

import com.google.gson.JsonParser

/**
 * 在线状态缓存：设备列表探活（MainActivity）与后台监测（OfflineMonitorService）共享，
 * 避免同一探活结果被重复消费。布尔值含义：true=在线 / false=离线 / null=未知（超时或异常）。
 *
 * 另存被控端 presence 心跳（HB）最近时间戳：HB 节流 ≤120s 一跳（retained），
 * 探活流程据此走快速路径——HB 足够新鲜直接判在线，免去一次完整 MQTT 短连接。
 */
object OnlineStateCache {

    /** presence HB 心跳新鲜度阈值：HB 节流 ≤120s 一跳，3 分钟内视为在线（允许丢一跳 + 时钟误差） */
    const val HB_FRESH_MS = 3 * 60 * 1000L

    /** 会话校验间隔：HB 判在线后仍需周期性做一次 QUERY 验签，才能识别解绑/换绑（SIGN_FAIL/UNBOUND） */
    const val SESSION_CHECK_INTERVAL_MS = 10 * 60 * 1000L

    private val cache = mutableMapOf<String, Pair<Boolean?, Long>>()
    private val hbCache = mutableMapOf<String, Long>()
    private val sessionCheckCache = mutableMapOf<String, Long>()

    /** @return (在线状态, 探活时间戳)，无记录返回 null */
    @Synchronized
    fun get(deviceId: String): Pair<Boolean?, Long>? = cache[deviceId]

    @Synchronized
    fun put(deviceId: String, online: Boolean?, ts: Long) {
        cache[deviceId] = online to ts
    }

    @Synchronized
    fun remove(deviceId: String) {
        cache.remove(deviceId)
        hbCache.remove(deviceId)
        sessionCheckCache.remove(deviceId)
    }

    /**
     * 记录 presence 报文中的 HB 心跳时间戳。
     * 报文为轻量 JSON {t:"HB", sid, ts}；非 HB 类型（PRB/CLM 仲裁报文）忽略。
     */
    @Synchronized
    fun noteHb(deviceId: String, payload: String) {
        runCatching {
            val obj = JsonParser.parseString(payload).asJsonObject
            if (obj.get("t")?.asString == "HB") {
                obj.get("ts")?.asLong?.let { hbCache[deviceId] = it }
            }
        }
    }

    /** @return 距最近一次 HB 的毫秒数；从未收到返回 null */
    @Synchronized
    fun hbAgeMs(deviceId: String): Long? =
        hbCache[deviceId]?.let { System.currentTimeMillis() - it }

    /** 记录一次成功的会话 QUERY 校验（验签通过，未被解绑） */
    @Synchronized
    fun noteSessionChecked(deviceId: String) {
        sessionCheckCache[deviceId] = System.currentTimeMillis()
    }

    /** @return 距上次会话校验的毫秒数；从未校验过返回 null */
    @Synchronized
    fun sessionCheckAgeMs(deviceId: String): Long? =
        sessionCheckCache[deviceId]?.let { System.currentTimeMillis() - it }
}