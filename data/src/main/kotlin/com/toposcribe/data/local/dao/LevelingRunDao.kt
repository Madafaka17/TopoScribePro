package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.toposcribe.data.local.entity.LevelingRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LevelingRunDao {
    @Query("SELECT * FROM leveling_runs WHERE project_id = :projectId ORDER BY created_at DESC")
    fun observeByProject(projectId: Long): Flow<List<LevelingRunEntity>>

    @Query("SELECT * FROM leveling_runs WHERE project_id = :projectId ORDER BY created_at DESC")
    suspend fun listSnapshot(projectId: Long): List<LevelingRunEntity>

    @Query("SELECT * FROM leveling_runs WHERE id = :id")
    suspend fun getById(id: Long): LevelingRunEntity?

    @Insert
    suspend fun insert(run: LevelingRunEntity): Long

    @androidx.room.Update
    suspend fun update(run: LevelingRunEntity)

    @androidx.room.Delete
    suspend fun delete(run: LevelingRunEntity)
}
