package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.toposcribe.data.local.entity.AlignmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlignmentDao {
    @Query("SELECT * FROM alignments WHERE project_id = :projectId ORDER BY created_at DESC")
    fun observeByProject(projectId: Long): Flow<List<AlignmentEntity>>

    @Query("SELECT * FROM alignments WHERE project_id = :projectId ORDER BY created_at DESC")
    suspend fun listSnapshot(projectId: Long): List<AlignmentEntity>

    @Query("SELECT * FROM alignments WHERE id = :id")
    suspend fun getById(id: Long): AlignmentEntity?

    @Insert
    suspend fun insert(alignment: AlignmentEntity): Long

    @androidx.room.Update
    suspend fun update(alignment: AlignmentEntity)

    @Delete
    suspend fun delete(alignment: AlignmentEntity)
}
