package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.toposcribe.data.local.entity.TraverseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TraverseDao {
    @Query("SELECT * FROM traverses WHERE project_id = :projectId ORDER BY created_at DESC")
    fun observeByProject(projectId: Long): Flow<List<TraverseEntity>>

    @Query("SELECT * FROM traverses WHERE project_id = :projectId ORDER BY created_at DESC")
    suspend fun listSnapshot(projectId: Long): List<TraverseEntity>

    @Query("SELECT * FROM traverses WHERE id = :id")
    suspend fun getById(id: Long): TraverseEntity?

    @Insert
    suspend fun insert(traverse: TraverseEntity): Long

    @Update
    suspend fun update(traverse: TraverseEntity)
}
