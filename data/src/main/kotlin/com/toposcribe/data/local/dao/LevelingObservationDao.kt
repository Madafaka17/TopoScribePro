package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.toposcribe.data.local.entity.LevelingObservationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LevelingObservationDao {
    @Query("SELECT * FROM leveling_observations WHERE leveling_run_id = :runId AND deleted_at IS NULL ORDER BY sequence_order ASC")
    fun observeByRun(runId: Long): Flow<List<LevelingObservationEntity>>

    @Query("SELECT * FROM leveling_observations WHERE leveling_run_id = :runId AND deleted_at IS NULL ORDER BY sequence_order ASC")
    suspend fun listSnapshot(runId: Long): List<LevelingObservationEntity>

    @Query("SELECT * FROM leveling_observations WHERE id = :id")
    suspend fun getById(id: Long): LevelingObservationEntity?

    /** Todos los ids de la nivelación, incluidas las lecturas eliminadas lógicamente (para retirar sus resultados). */
    @Query("SELECT id FROM leveling_observations WHERE leveling_run_id = :runId")
    suspend fun allIdsByRun(runId: Long): List<Long>

    @Insert
    suspend fun insert(observation: LevelingObservationEntity): Long

    @Update
    suspend fun update(observation: LevelingObservationEntity)

    @Query("UPDATE leveling_observations SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: String)
}
