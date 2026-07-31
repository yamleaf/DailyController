package com.yample.daily.controller

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [DeviceRecord::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
}
