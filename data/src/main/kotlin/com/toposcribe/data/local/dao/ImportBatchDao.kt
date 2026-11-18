package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.toposcribe.data.local.entity.ImportBatchEntity

@Dao
interface ImportBatchDao {
    @Query("SELECT * FROM import_batches WHERE project_id = :projectId ORDER BY created_at DESC")
    suspend fun listByProject(projectId: Long): List<ImportBatchEntity>

    @Insert
    suspend fun insert(batch: ImportBatchEntity): Long
}
