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
    val bound: Boolean,
    val pinned: Boolean = false, // QQ 式置顶：置顶设备排在列表最前
    val group: String = "",       // 设备分组（空 = 未分组）
    val sortOrder: Int = 0        // 手动排序序号：同置顶级别内按此升序排列（上移/下移交换）
) : Serializable
