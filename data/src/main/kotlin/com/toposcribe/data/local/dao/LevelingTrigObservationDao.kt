package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.toposcribe.data.local.entity.LevelingTrigObservationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LevelingTrigObservationDao {
    @Query("SELECT * FROM leveling_trig_observations WHERE project_id = :projectId AND deleted_at IS NULL ORDER BY id ASC")
    fun observeByProject(projectId: Long): Flow<List<LevelingTrigObservationEntity>>

    @Query("SELECT * FROM leveling_trig_observations WHERE project_id = :projectId AND deleted_at IS NULL ORDER BY id ASC")
    suspend fun listSnapshot(projectId: Long): List<LevelingTrigObservationEntity>

    @Insert
    suspend fun insert(observation: LevelingTrigObservationEntity): Long

    @Update
    suspend fun update(observation: LevelingTrigObservationEntity)

    @Query("UPDATE leveling_trig_observations SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: String)
}
