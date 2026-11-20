package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.toposcribe.data.local.entity.StationSetupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StationSetupDao {
    @Query("SELECT * FROM station_setups WHERE project_id = :projectId ORDER BY created_at DESC")
    fun observeByProject(projectId: Long): Flow<List<StationSetupEntity>>

    @Query("SELECT * FROM station_setups WHERE project_id = :projectId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatest(projectId: Long): StationSetupEntity?

    @Insert
    suspend fun insert(setup: StationSetupEntity): Long
}
