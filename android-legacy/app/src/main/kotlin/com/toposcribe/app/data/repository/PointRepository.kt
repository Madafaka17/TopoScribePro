package com.toposcribe.app.data.repository

import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.ObservationEditLogEntity
import com.toposcribe.data.local.entity.PointEntity
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class PointRepository @Inject constructor(
    private val db: TopoScribeDatabase,
) {
    fun observeByProject(projectId: Long): Flow<List<PointEntity>> = db.pointDao().observeByProject(projectId)

    /** "Control/BM" es una vista filtrada sobre la misma tabla points -- nunca una tabla aparte. */
    fun observeControlPoints(projectId: Long): Flow<List<PointEntity>> = db.pointDao().observeControlPoints(projectId)

    suspend fun getById(id: Long): PointEntity? = db.pointDao().getById(id)

    suspend fun getByCode(projectId: Long, code: String): PointEntity? = db.pointDao().getByCode(projectId, code)

    suspend fun create(point: PointEntity): Long {
        val now = Instant.now().toString()
        return db.pointDao().insert(point.copy(createdAt = now))
    }

    /** Toda edición de un dato crudo escribe primero en el log de auditoría el {campo,
     * valor_anterior, valor_nuevo} ANTES del UPDATE -- nunca se corrige en silencio. */
    suspend fun update(updated: PointEntity) {
        val before = db.pointDao().getById(updated.id) ?: run {
            db.pointDao().update(updated)
            return
        }
        val now = Instant.now().toString()
        logFieldChange(before.code, updated.code, "code", before.id, now)
        logFieldChange(before.description, updated.description, "description", before.id, now)
        logFieldChange(before.observations, updated.observations, "observations", before.id, now)
        logFieldChange(before.tramoSector, updated.tramoSector, "tramo_sector", before.id, now)
        logFieldChange(before.isControl, updated.isControl, "is_control", before.id, now)
        logFieldChange(before.role, updated.role, "role", before.id, now)
        logFieldChange(before.dataSource, updated.dataSource, "data_source", before.id, now)
        logFieldChange(before.fixType, updated.fixType, "fix_type", before.id, now)
        logFieldChange(before.fixedEast, updated.fixedEast, "fixed_east", before.id, now)
        logFieldChange(before.fixedNorth, updated.fixedNorth, "fixed_north", before.id, now)
        logFieldChange(before.fixedElevation, updated.fixedElevation, "fixed_elevation", before.id, now)
        logFieldChange(before.photoCaption, updated.photoCaption, "photo_caption", before.id, now)
        logFieldChange(before.photoSegment, updated.photoSegment, "photo_segment", before.id, now)
        logFieldChange(before.photoChainage, updated.photoChainage, "photo_chainage", before.id, now)
        // sketchPhotoPath NUNCA se toca aquí -- lo maneja solo setPhoto()/removePhoto(), para que
        // una edición normal de datos no borre en silencio la foto ya subida.
        db.pointDao().update(updated.copy(sketchPhotoPath = before.sketchPhotoPath, updatedAt = now))
    }

    suspend fun setPhoto(pointId: Long, photoPath: String?) {
        val point = db.pointDao().getById(pointId) ?: return
        db.pointDao().update(point.copy(sketchPhotoPath = photoPath, updatedAt = Instant.now().toString()))
    }

    suspend fun countReferences(id: Long): Int = db.pointDao().countReferences(id)

    suspend fun softDelete(id: Long) {
        db.pointDao().softDelete(id, Instant.now().toString())
    }

    private suspend fun <T> logFieldChange(old: T, new: T, field: String, rowId: Long, now: String) {
        if (old == new) return
        db.observationEditLogDao().insert(
            ObservationEditLogEntity(
                tableName = "points",
                rowId = rowId,
                field = field,
                oldValue = old?.toString(),
                newValue = new?.toString(),
                editedAt = now,
            ),
        )
    }
}
