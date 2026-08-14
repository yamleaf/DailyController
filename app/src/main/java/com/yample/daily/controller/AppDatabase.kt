package com.yample.daily.controller

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DeviceRecord::class, ServerlessBackend::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun serverlessBackendDao(): ServerlessBackendDao

    companion object {
        /** v4 → v5：新增 serverless_backends 表（客户端管理多后台配置） */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `serverless_backends` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`baseUrl` TEXT NOT NULL, " +
                        "`appId` TEXT NOT NULL, " +
                        "`appSecret` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
            }
        }

        /** v5 → v6：devices 表新增 sortOrder 列（上移/下移手动排序） */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `devices` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
