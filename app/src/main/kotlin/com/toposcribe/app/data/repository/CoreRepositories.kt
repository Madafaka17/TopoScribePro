package com.toposcribe.app.data.repository

import androidx.room.withTransaction
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.AlignmentEntity
import com.toposcribe.data.local.entity.DataSource
import com.toposcribe.data.local.entity.ObservationEditLogEntity
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.PointRole
import com.toposcribe.data.local.entity.ProjectEntity
import com.toposcribe.data.local.entity.StationSetupEntity
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Resultado de una operación con validación: el mensaje de error se muestra tal cual al usuario. */
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Fail(val message: String) : Outcome<Nothing>()
}

fun <T> Outcome<T>.errorOrNull(): String? = (this as? Outcome.Fail)?.message

/** Código de punto normalizado para comparar duplicados (sin espacios laterales, sin distinguir mayúsculas). */
fun normalizeCode(code: String): String = code.trim().uppercase()

// -------------------------------------------------------------------------------------------------
// Historial de correcciones: nunca se pierde la lectura original
// -------------------------------------------------------------------------------------------------

@Singleton
class EditHistoryRepository @Inject constructor(private val db: TopoScribeDatabase) {

    data class Change(val field: String, val old: Any?, val new: Any?)

    /** Registra cada campo que cambió (valor anterior → nuevo) ANTES de guardar la edición. */
    suspend fun logChanges(table: String, rowId: Long, changes: List<Change>) {
        val now = Instant.now().toString()
        for (c in changes) {
            if (c.old == c.new) continue
            db.observationEditLogDao().insert(
                ObservationEditLogEntity(tableName = table, rowId = rowId, field = c.field, oldValue = c.old?.toString(), newValue = c.new?.toString(), editedAt = now),
            )
        }
    }

    suspend fun history(table: String, rowId: Long): List<ObservationEditLogEntity> = db.observationEditLogDao().listFor(table, rowId)

    /** Todo el historial de una tabla, agrupado por fila (para mostrar «original → vigente» sin una consulta por fila). */
    suspend fun historyByRow(table: String): Map<Long, List<ObservationEditLogEntity>> =
        db.observationEditLogDao().listForTable(table).groupBy { it.rowId }
}

/** «Original 1.250 → vigente 1.260» para un campo con correcciones; null si nunca se corrigió. */
fun correctionText(history: List<ObservationEditLogEntity>?, field: String, current: String): String? {
    val changes = history?.filter { it.field == field } ?: return null
    if (changes.isEmpty()) return null
    val original = changes.first().oldValue ?: "—"
    return "corregido: original $original → vigente $current"
}

// -------------------------------------------------------------------------------------------------
// Proyectos
// -------------------------------------------------------------------------------------------------

@Singleton
class ProjectRepository @Inject constructor(
    private val db: TopoScribeDatabase,
    private val files: AppFiles,
) {
    fun observeAll(): Flow<List<ProjectEntity>> = db.projectDao().observeAll()
    fun observeById(id: Long): Flow<ProjectEntity?> = db.projectDao().observeById(id)
    suspend fun getById(id: Long): ProjectEntity? = db.projectDao().getById(id)

    /** Crea un proyecto NUEVO VACÍO: nunca arranca con mediciones, fotografías ni anexos. */
    suspend fun createEmpty(project: ProjectEntity): Long =
        db.projectDao().insert(project.copy(isDemo = false, createdAt = Instant.now().toString()))

    suspend fun update(project: ProjectEntity) = db.projectDao().update(project.copy(updatedAt = Instant.now().toString()))

    /** Elimina el proyecto y los archivos (fotografías y anexos) que solo le pertenecían. */
    suspend fun delete(project: ProjectEntity) {
        val photos = db.pointDao().listByProject(project.id).mapNotNull { it.sketchPhotoPath }
        val annexes = db.annexDao().listByProject(project.id).map { it.storedFile }
        db.projectDao().delete(project)
        photos.forEach { files.deletePhoto(it) }
        annexes.forEach { files.deleteAnnex(it) }
    }
}

/** Carpetas de archivos privados de la app. */
@Singleton
class AppFiles @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext val context: android.content.Context) {
    val photosDir: File get() = File(context.filesDir, "photos").apply { mkdirs() }
    val annexesDir: File get() = File(context.filesDir, "annexes").apply { mkdirs() }
    val workDir: File get() = File(context.cacheDir, "work").apply { mkdirs() }

    fun photo(path: String?): File? = com.toposcribe.data.backup.resolveStoredFile(path, photosDir)
    fun annex(stored: String): File = File(annexesDir, stored)

    fun deletePhoto(path: String?) { photo(path)?.delete() }
    fun deleteAnnex(stored: String) { if (stored.isNotBlank()) annex(stored).delete() }
}

// -------------------------------------------------------------------------------------------------
// Puntos
// -------------------------------------------------------------------------------------------------

@Singleton
class PointRepository @Inject constructor(
    private val db: TopoScribeDatabase,
    private val history: EditHistoryRepository,
    private val files: AppFiles,
) {
    fun observeByProject(projectId: Long): Flow<List<PointEntity>> = db.pointDao().observeByProject(projectId)
    suspend fun list(projectId: Long): List<PointEntity> = db.pointDao().listByProject(projectId)
    suspend fun getById(id: Long): PointEntity? = db.pointDao().getById(id)

    /** Busca por código sin distinguir mayúsculas ni espacios laterales. */
    suspend fun findByCode(projectId: Long, code: String): PointEntity? {
        val n = normalizeCode(code)
        return db.pointDao().listByProject(projectId).firstOrNull { normalizeCode(it.code) == n }
    }

    /** Valida y crea un punto. El código debe ser único en el proyecto (sin distinguir mayúsculas). */
    suspend fun create(point: PointEntity): Outcome<Long> {
        val code = point.code.trim()
        if (code.isEmpty()) return Outcome.Fail("El código del punto es obligatorio.")
        if (code.length > 40) return Outcome.Fail("El código del punto es demasiado largo (máximo 40 caracteres).")
        if (findByCode(point.projectId, code) != null) return Outcome.Fail("Ya existe un punto con el código «$code» en este proyecto.")
        validateFixed(point)?.let { return Outcome.Fail(it) }
        val now = Instant.now().toString()
        return Outcome.Ok(db.pointDao().insert(point.copy(code = code, createdAt = now)))
    }

    private fun validateFixed(p: PointEntity): String? {
        if ((p.fixedEast == null) != (p.fixedNorth == null)) return "Este y Norte deben indicarse juntos (o ninguno)."
        listOf(p.fixedEast, p.fixedNorth, p.fixedElevation).forEach { if (it != null && (it.isNaN() || it.isInfinite())) return "Los valores numéricos no son válidos." }
        return null
    }

    /** Edita un punto dejando constancia (valor anterior → nuevo) de cada campo en el historial. */
    suspend fun update(updated: PointEntity): Outcome<Unit> {
        val before = db.pointDao().getById(updated.id) ?: return Outcome.Fail("El punto ya no existe.")
        val code = updated.code.trim()
        if (code.isEmpty()) return Outcome.Fail("El código del punto es obligatorio.")
        val other = findByCode(before.projectId, code)
        if (other != null && other.id != before.id) return Outcome.Fail("Ya existe un punto con el código «$code» en este proyecto.")
        validateFixed(updated)?.let { return Outcome.Fail(it) }
        history.logChanges(
            "points", before.id,
            listOf(
                EditHistoryRepository.Change("code", before.code, code),
                EditHistoryRepository.Change("description", before.description, updated.description),
                EditHistoryRepository.Change("observations", before.observations, updated.observations),
                EditHistoryRepository.Change("instrument_ref", before.instrumentRef, updated.instrumentRef),
                EditHistoryRepository.Change("is_control", before.isControl, updated.isControl),
                EditHistoryRepository.Change("fixed_east", before.fixedEast, updated.fixedEast),
                EditHistoryRepository.Change("fixed_north", before.fixedNorth, updated.fixedNorth),
                EditHistoryRepository.Change("fixed_elevation", before.fixedElevation, updated.fixedElevation),
                EditHistoryRepository.Change("photo_caption", before.photoCaption, updated.photoCaption),
            ),
        )
        // La fotografía se gestiona solo con setPhoto/removePhoto: una edición de datos no la toca.
        db.pointDao().update(updated.copy(code = code, sketchPhotoPath = before.sketchPhotoPath, updatedAt = Instant.now().toString()))
        return Outcome.Ok(Unit)
    }

    /** Cuántos elementos usan el punto (observaciones, estaciones, BM de nivelaciones, líneas). */
    suspend fun countReferences(point: PointEntity): Int {
        var n = db.pointDao().countReferences(point.id)
        n += db.alignmentDao().listSnapshot(point.projectId).count { point.id in it.pointSequence }
        return n
    }

    /** Elimina el punto (y su fotografía) solo si ningún dato lo usa; los anexos relacionados quedan sin relación. */
    suspend fun delete(point: PointEntity): Outcome<Unit> {
        val refs = countReferences(point)
        if (refs > 0) return Outcome.Fail("El punto «${point.code}» se usa en $refs dato(s) (lecturas, estaciones, nivelaciones o líneas). Elimine primero esos datos.")
        db.withTransaction {
            db.pointDao().purgeDeletedPlanimetry(point.id)
            db.pointDao().purgeDeletedLeveling(point.id)
            db.pointDao().purgeDeletedTrig(point.id)
            db.pointDao().purgeComputed(point.id)
            db.pointDao().hardDelete(point.id)
        }
        files.deletePhoto(point.sketchPhotoPath)
        return Outcome.Ok(Unit)
    }

    // ---- Fotografía del punto: agregar / reemplazar / retirar ----

    suspend fun setPhoto(pointId: Long, newPath: String): Outcome<Unit> {
        val point = db.pointDao().getById(pointId) ?: return Outcome.Fail("El punto ya no existe.")
        val old = point.sketchPhotoPath
        db.pointDao().update(point.copy(sketchPhotoPath = newPath, updatedAt = Instant.now().toString()))
        if (old != null && old != newPath) files.deletePhoto(old)
        return Outcome.Ok(Unit)
    }

    suspend fun setCaption(pointId: Long, caption: String?) {
        val point = db.pointDao().getById(pointId) ?: return
        history.logChanges("points", pointId, listOf(EditHistoryRepository.Change("photo_caption", point.photoCaption, caption?.ifBlank { null })))
        db.pointDao().update(point.copy(photoCaption = caption?.ifBlank { null }, updatedAt = Instant.now().toString()))
    }

    suspend fun removePhoto(pointId: Long) {
        val point = db.pointDao().getById(pointId) ?: return
        val old = point.sketchPhotoPath
        db.pointDao().update(point.copy(sketchPhotoPath = null, photoCaption = null, updatedAt = Instant.now().toString()))
        files.deletePhoto(old)
    }

    /** Crea un punto simple por código (para lecturas que citan un punto nuevo). */
    suspend fun createSimple(projectId: Long, code: String, description: String?, instrumentRef: String): Outcome<PointEntity> {
        val r = create(PointEntity(projectId = projectId, code = code, description = description?.ifBlank { null }, instrumentRef = instrumentRef, role = PointRole.RELLENO, dataSource = DataSource.OTRO, createdAt = ""))
        return when (r) {
            is Outcome.Fail -> r
            is Outcome.Ok -> Outcome.Ok(db.pointDao().getById(r.value)!!)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Estaciones del teodolito (orientación y convenio angular)
// -------------------------------------------------------------------------------------------------

@Singleton
class StationSetupRepository @Inject constructor(
    private val db: TopoScribeDatabase,
    private val history: EditHistoryRepository,
) {
    fun observeByProject(projectId: Long): Flow<List<StationSetupEntity>> = db.stationSetupDao().observeByProject(projectId)
    suspend fun list(projectId: Long): List<StationSetupEntity> = db.stationSetupDao().listSnapshot(projectId)
    suspend fun getById(id: Long): StationSetupEntity? = db.stationSetupDao().getById(id)

    /** Última ocupación de esa estación (la que se usa por defecto en lecturas nuevas). */
    suspend fun latestFor(projectId: Long, pointId: Long): StationSetupEntity? =
        list(projectId).filter { it.occupiedPointId == pointId }.maxWithOrNull(compareBy({ it.createdAt }, { it.id }))

    suspend fun create(setup: StationSetupEntity): Outcome<Long> {
        validate(setup)?.let { return Outcome.Fail(it) }
        val point = db.pointDao().getById(setup.occupiedPointId)
        if (point == null || point.projectId != setup.projectId) return Outcome.Fail("La estación ocupada no existe en este proyecto.")
        return Outcome.Ok(db.stationSetupDao().insert(setup.copy(id = 0, createdAt = Instant.now().toString())))
    }

    private fun validate(s: StationSetupEntity): String? {
        if (s.instrumentHeightAi.isNaN() || s.instrumentHeightAi < 0 || s.instrumentHeightAi > 5) return "La altura del instrumento debe estar entre 0 y 5."
        if (s.backsightPointLabel.isBlank()) return "Indique la referencia de orientación (vista atrás)."
        if (s.baseAzimuthDecimal < 0 || s.baseAzimuthDecimal >= 360.0) return "El azimut de la referencia debe estar entre 0° y 360° (sin incluir 360°)."
        if (s.backsightReadingDeg < 0 || s.backsightReadingDeg >= 360.0) return "La lectura horizontal sobre la referencia debe estar entre 0° y 360°."
        return null
    }

    /** Edita una ocupación: el historial conserva los valores anteriores y las lecturas que la usan quedan desactualizadas. */
    suspend fun update(updated: StationSetupEntity): Outcome<Unit> {
        val before = db.stationSetupDao().getById(updated.id) ?: return Outcome.Fail("La estación ya no existe.")
        validate(updated)?.let { return Outcome.Fail(it) }
        history.logChanges(
            "station_setups", before.id,
            listOf(
                EditHistoryRepository.Change("instrument_height_ai", before.instrumentHeightAi, updated.instrumentHeightAi),
                EditHistoryRepository.Change("backsight_point_label", before.backsightPointLabel, updated.backsightPointLabel),
                EditHistoryRepository.Change("base_azimuth_decimal", before.baseAzimuthDecimal, updated.baseAzimuthDecimal),
                EditHistoryRepository.Change("backsight_reading_deg", before.backsightReadingDeg, updated.backsightReadingDeg),
                EditHistoryRepository.Change("angle_convention", before.angleConvention, updated.angleConvention),
            ),
        )
        db.stationSetupDao().update(updated.copy(projectId = before.projectId, occupiedPointId = before.occupiedPointId, createdAt = before.createdAt))
        return Outcome.Ok(Unit)
    }
}

// -------------------------------------------------------------------------------------------------
// Líneas: unir puntos en el orden que defina el usuario
// -------------------------------------------------------------------------------------------------

@Singleton
class LineRepository @Inject constructor(private val db: TopoScribeDatabase) {
    fun observeByProject(projectId: Long): Flow<List<AlignmentEntity>> = db.alignmentDao().observeByProject(projectId)
    suspend fun list(projectId: Long): List<AlignmentEntity> = db.alignmentDao().listSnapshot(projectId)

    suspend fun save(projectId: Long, id: Long?, name: String, pointIds: List<Long>, closed: Boolean): Outcome<Long> {
        val clean = name.trim()
        if (clean.isEmpty()) return Outcome.Fail("La línea necesita un nombre.")
        if (pointIds.size < 2) return Outcome.Fail("Una línea necesita al menos 2 puntos.")
        val valid = db.pointDao().listByProject(projectId).map { it.id }.toSet()
        if (pointIds.any { it !in valid }) return Outcome.Fail("La línea cita puntos que no existen en este proyecto.")
        if (id == null) return Outcome.Ok(db.alignmentDao().insert(AlignmentEntity(projectId = projectId, name = clean, pointSequence = pointIds, closed = closed, createdAt = Instant.now().toString())))
        val before = db.alignmentDao().getById(id) ?: return Outcome.Fail("La línea ya no existe.")
        db.alignmentDao().update(before.copy(name = clean, pointSequence = pointIds, closed = closed))
        return Outcome.Ok(id)
    }

    suspend fun delete(line: AlignmentEntity) = db.alignmentDao().delete(line)
}
