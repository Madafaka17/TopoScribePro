package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanimetryObservationDao {
    @Query("SELECT * FROM planimetry_observations WHERE project_id = :projectId AND deleted_at IS NULL ORDER BY id ASC")
    fun observeByProject(projectId: Long): Flow<List<PlanimetryObservationEntity>>

    @Query("SELECT * FROM planimetry_observations WHERE project_id = :projectId AND deleted_at IS NULL ORDER BY id ASC")
    suspend fun listSnapshot(projectId: Long): List<PlanimetryObservationEntity>

    @Query("SELECT * FROM planimetry_observations WHERE traverse_id = :traverseId AND deleted_at IS NULL ORDER BY sequence_order ASC, id ASC")
    suspend fun listByTraverse(traverseId: Long): List<PlanimetryObservationEntity>

    @Query("SELECT * FROM planimetry_observations WHERE id = :id")
    suspend fun getById(id: Long): PlanimetryObservationEntity?

    @Insert
    suspend fun insert(observation: PlanimetryObservationEntity): Long

    @Update
    suspend fun update(observation: PlanimetryObservationEntity)

    @Query("UPDATE planimetry_observations SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: String)
}
