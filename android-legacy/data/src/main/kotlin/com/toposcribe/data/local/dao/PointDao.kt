package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.toposcribe.data.local.entity.PointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PointDao {
    @Query("SELECT * FROM points WHERE project_id = :projectId AND deleted_at IS NULL ORDER BY code ASC")
    fun observeByProject(projectId: Long): Flow<List<PointEntity>>

    @Query("SELECT * FROM points WHERE project_id = :projectId AND deleted_at IS NULL ORDER BY code ASC")
    suspend fun listByProject(projectId: Long): List<PointEntity>

    /** "Control/BM" NO es una tabla separada: es esta misma tabla filtrada por rol/isControl --
     * evita dos fuentes de verdad para la misma entidad del mundo real. */
    @Query("SELECT * FROM points WHERE project_id = :projectId AND deleted_at IS NULL AND (role = 'CONTROL' OR is_control = 1) ORDER BY code ASC")
    fun observeControlPoints(projectId: Long): Flow<List<PointEntity>>

    @Query("SELECT * FROM points WHERE id = :id")
    suspend fun getById(id: Long): PointEntity?

    @Query("SELECT * FROM points WHERE project_id = :projectId AND code = :code AND deleted_at IS NULL")
    suspend fun getByCode(projectId: Long, code: String): PointEntity?

    @Insert
    suspend fun insert(point: PointEntity): Long

    @Update
    suspend fun update(point: PointEntity)

    @Query("UPDATE points SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: String)

    @Query(
        """SELECT
            (SELECT COUNT(*) FROM planimetry_observations WHERE (occupied_point_id = :id OR target_point_id = :id) AND deleted_at IS NULL) +
            (SELECT COUNT(*) FROM leveling_observations WHERE point_id = :id AND deleted_at IS NULL) +
            (SELECT COUNT(*) FROM leveling_trig_observations WHERE (occupied_point_id = :id OR target_point_id = :id) AND deleted_at IS NULL)
        """,
    )
    suspend fun countReferences(id: Long): Int
}
