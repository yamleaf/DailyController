package com.yample.daily.controller

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * EMQX Serverless 后台配置（控制端「客户端管理」）。
 * 独立于设备：一个控制端可配置多个后台，各自管理对应 broker 上的在线客户端/订阅。
 */
@Entity(tableName = "serverless_backends")
data class ServerlessBackend(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 如 https://xxx.emqxsl.com/api/v5 */
    val baseUrl: String,
    val appId: String,
    val appSecret: String,
    val sortOrder: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
