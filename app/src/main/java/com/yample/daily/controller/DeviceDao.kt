package com.yample.daily.controller

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices")
    suspend fun getAll(): List<DeviceRecord>

    @Insert
    suspend fun insert(device: DeviceRecord)

    @Update
    suspend fun update(device: DeviceRecord)

    @Query("DELETE FROM devices WHERE deviceId = :deviceId")
    suspend fun deleteById(deviceId: String)
}
