package com.yample.daily.controller

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ServerlessBackendDao {
    @Query("SELECT * FROM serverless_backends ORDER BY sortOrder, updatedAt DESC")
    suspend fun getAll(): List<ServerlessBackend>

    @Query("SELECT * FROM serverless_backends WHERE id = :id")
    suspend fun getById(id: Long): ServerlessBackend?

    @Insert
    suspend fun insert(backend: ServerlessBackend): Long

    @Update
    suspend fun update(backend: ServerlessBackend)

    @Delete
    suspend fun delete(backend: ServerlessBackend)

    @Query("DELETE FROM serverless_backends")
    suspend fun clearAll()
}
