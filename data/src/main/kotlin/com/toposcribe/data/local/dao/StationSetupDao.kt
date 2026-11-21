package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import com.toposcribe.data.local.entity.StationSetupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StationSetupDao {
    @Query("SELECT * FROM station_setups WHERE project_id = :projectId ORDER BY created_at DESC")
    fun observeByProject(projectId: Long): Flow<List<StationSetupEntity>>

    @Query("SELECT * FROM station_setups WHERE project_id = :projectId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatest(projectId: Long): StationSetupEntity?

    @Query("SELECT * FROM station_setups WHERE project_id = :projectId ORDER BY created_at ASC, id ASC")
    suspend fun listSnapshot(projectId: Long): List<StationSetupEntity>

    @Query("SELECT * FROM station_setups WHERE id = :id")
    suspend fun getById(id: Long): StationSetupEntity?

    @Insert
    suspend fun insert(setup: StationSetupEntity): Long

    @Update
    suspend fun update(setup: StationSetupEntity)

    @Delete
    suspend fun delete(setup: StationSetupEntity)
}
