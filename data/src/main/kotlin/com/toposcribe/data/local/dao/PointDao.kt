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

    @Query("DELETE FROM points WHERE id = :id")
    suspend fun hardDelete(id: Long)

    @Query("UPDATE points SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: String)

    @Query(
        """SELECT
            (SELECT COUNT(*) FROM planimetry_observations WHERE (occupied_point_id = :id OR target_point_id = :id) AND deleted_at IS NULL) +
            (SELECT COUNT(*) FROM leveling_observations WHERE point_id = :id AND deleted_at IS NULL) +
            (SELECT COUNT(*) FROM leveling_trig_observations WHERE (occupied_point_id = :id OR target_point_id = :id) AND deleted_at IS NULL) +
            (SELECT COUNT(*) FROM station_setups WHERE occupied_point_id = :id OR backsight_point_id = :id) +
            (SELECT COUNT(*) FROM leveling_runs WHERE start_bm_point_id = :id OR end_bm_point_id = :id)
        """,
    )
    suspend fun countReferences(id: Long): Int

    /** Limpieza previa al borrado físico: filas ya eliminadas lógicamente y resultados calculados del punto. */
    @Query("DELETE FROM planimetry_observations WHERE deleted_at IS NOT NULL AND (occupied_point_id = :id OR target_point_id = :id)")
    suspend fun purgeDeletedPlanimetry(id: Long)

    @Query("DELETE FROM leveling_observations WHERE deleted_at IS NOT NULL AND point_id = :id")
    suspend fun purgeDeletedLeveling(id: Long)

    @Query("DELETE FROM leveling_trig_observations WHERE deleted_at IS NOT NULL AND (occupied_point_id = :id OR target_point_id = :id)")
    suspend fun purgeDeletedTrig(id: Long)

    @Query("DELETE FROM computed_points WHERE point_id = :id")
    suspend fun purgeComputed(id: Long)
}
