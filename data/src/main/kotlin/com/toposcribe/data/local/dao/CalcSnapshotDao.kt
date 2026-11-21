package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.toposcribe.data.local.entity.CalcSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CalcSnapshotDao {
    @Query("SELECT * FROM calc_snapshots WHERE project_id = :projectId")
    fun observeByProject(projectId: Long): Flow<List<CalcSnapshotEntity>>

    @Query("SELECT * FROM calc_snapshots WHERE project_id = :projectId")
    suspend fun listByProject(projectId: Long): List<CalcSnapshotEntity>

    @Query("SELECT * FROM calc_snapshots WHERE project_id = :projectId AND kind = :kind AND subject_id = :subjectId")
    suspend fun find(projectId: Long, kind: String, subjectId: Long): CalcSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: CalcSnapshotEntity): Long
}