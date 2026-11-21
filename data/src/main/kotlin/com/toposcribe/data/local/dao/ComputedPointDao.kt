package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.toposcribe.data.local.entity.ComputedPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ComputedPointDao {
    @Query("SELECT * FROM computed_points WHERE project_id = :projectId AND is_current = 1 ORDER BY point_id")
    fun observeCurrentByProject(projectId: Long): Flow<List<ComputedPointEntity>>

    @Query("SELECT * FROM computed_points WHERE project_id = :projectId AND is_current = 1 ORDER BY point_id")
    suspend fun listCurrentSnapshot(projectId: Long): List<ComputedPointEntity>

    @Query("SELECT * FROM computed_points WHERE point_id = :pointId AND is_current = 1")
    suspend fun listCurrentForPoint(pointId: Long): List<ComputedPointEntity>

    @Query("SELECT * FROM computed_points WHERE id = :id")
    suspend fun getById(id: Long): ComputedPointEntity?

    /** Recalcular SIEMPRE inserta una fila nueva y retira (is_current=0) la anterior -- nunca se
     * sobrescribe un east/north/elevation ya calculado. */
    @Query("UPDATE computed_points SET is_current = 0 WHERE point_id = :pointId AND method = :method AND is_current = 1")
    suspend fun retireCurrent(pointId: Long, method: String)

    @Query("UPDATE computed_points SET is_current = 0 WHERE project_id = :projectId AND method = :method AND is_current = 1")
    suspend fun retireAllForMethod(projectId: Long, method: String)

    @Query("DELETE FROM computed_points WHERE project_id = :projectId AND method = :method")
    suspend fun deleteAllForMethod(projectId: Long, method: String)

    @Query("UPDATE computed_points SET is_current = 0 WHERE id = :id")
    suspend fun retireById(id: Long)

    @Insert
    suspend fun insert(row: ComputedPointEntity): Long
}
