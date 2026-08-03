package com.yample.daily.controller

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices")
    suspend fun getAll(): List<DeviceRecord>

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId")
    suspend fun getById(deviceId: String): DeviceRecord?

    /**
     * 重新扫码时用 REPLACE：以最新二维码里的 pairingToken 覆盖旧记录，
     * 并把 sessionSecret 重置为空、bound=false，强制控制端重新走配对流程。
     * 若用默认 ABORT，已存在设备会被跳过，旧 sessionSecret 残留，导致“重新扫码无法配对”。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: DeviceRecord)

    @Update
    suspend fun update(device: DeviceRecord)

    @Query("DELETE FROM devices WHERE deviceId = :deviceId")
    suspend fun deleteById(deviceId: String)
}
