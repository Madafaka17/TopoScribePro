package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.toposcribe.data.local.entity.AdjustmentEntity

@Dao
interface AdjustmentDao {
    @Query("SELECT * FROM adjustments WHERE traverse_id = :traverseId ORDER BY created_at DESC")
    suspend fun listByTraverse(traverseId: Long): List<AdjustmentEntity>

    @Query("SELECT * FROM adjustments WHERE traverse_id = :traverseId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatest(traverseId: Long): AdjustmentEntity?

    @Insert
    suspend fun insert(adjustment: AdjustmentEntity): Long
}
