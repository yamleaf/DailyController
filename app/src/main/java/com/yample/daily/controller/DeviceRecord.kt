package com.yample.daily.controller

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "devices")
data class DeviceRecord(
    @PrimaryKey val deviceId: String,
    val name: String,
    val broker: String,
    val ctlUser: String,        // 控制端 CTL 账户（来自绑定二维码）
    val ctlPass: String,
    val sessionSecret: String,  // 配对握手后派生的会话密钥，运行时报文验签
    val pairingToken: String,   // 配对令牌（握手成功后清空）
    val bound: Boolean
) : Serializable
