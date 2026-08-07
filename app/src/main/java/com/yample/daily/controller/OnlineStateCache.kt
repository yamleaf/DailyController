package com.yample.daily.controller

/**
 * 在线状态共享缓存：设备列表探活（MainActivity）与离线监测服务（OfflineMonitorService）
 * 共用同一份探测结果，避免两条通道各自建立短连接造成重复探活。
 * 值语义：true=在线 / false=离线 / null=未知（超时或异常）。
 */
object OnlineStateCache {

    private val cache = mutableMapOf<String, Pair<Boolean?, Long>>()

    /** @return (在线状态, 探测时间戳)，无记录返回 null */
    @Synchronized
    fun get(deviceId: String): Pair<Boolean?, Long>? = cache[deviceId]

    @Synchronized
    fun put(deviceId: String, online: Boolean?, ts: Long) {
        cache[deviceId] = online to ts
    }

    @Synchronized
    fun remove(deviceId: String) {
        cache.remove(deviceId)
    }
}
