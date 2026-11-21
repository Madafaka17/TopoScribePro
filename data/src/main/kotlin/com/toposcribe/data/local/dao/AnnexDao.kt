package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.toposcribe.data.local.entity.AnnexEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnexDao {
    @Query("SELECT * FROM annexes WHERE project_id = :projectId ORDER BY sort_order ASC, id ASC")
    fun observeByProject(projectId: Long): Flow<List<AnnexEntity>>

    @Query("SELECT * FROM annexes WHERE project_id = :projectId ORDER BY sort_order ASC, id ASC")
    suspend fun listByProject(projectId: Long): List<AnnexEntity>

    @Query("SELECT * FROM annexes WHERE id = :id")
    suspend fun getById(id: Long): AnnexEntity?

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM annexes WHERE project_id = :projectId")
    suspend fun maxOrder(projectId: Long): Int

    @Insert
    suspend fun insert(annex: AnnexEntity): Long

    @Update
    suspend fun update(annex: AnnexEntity)

    @Delete
    suspend fun delete(annex: AnnexEntity)
}