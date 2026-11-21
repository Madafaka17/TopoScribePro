package com.toposcribe.app.data.repository

import androidx.room.withTransaction
import com.toposcribe.calc.DifferentialLevelingResult
import com.toposcribe.calc.LevelingObservationCalcInput
import com.toposcribe.calc.LevelingProfile
import com.toposcribe.calc.LevelingRowResult
import com.toposcribe.calc.ReadingType
import com.toposcribe.calc.ResultState
import com.toposcribe.calc.buildLevelingProfile
import com.toposcribe.calc.computeDifferentialLeveling
import com.toposcribe.calc.computeFieldMisclosure
import com.toposcribe.calc.inputFingerprint
import com.toposcribe.calc.resultState
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.CalcSnapshotEntity
import com.toposcribe.data.local.entity.ComputedPointEntity
import com.toposcribe.data.local.entity.LevelingObservationEntity
import com.toposcribe.data.local.entity.LevelingRunEntity
import com.toposcribe.data.local.entity.SourceObservationRef
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

const val KIND_ALTIMETRIA = "ALTIMETRIA"
const val KIND_PLANIMETRIA = "PLANIMETRIA"
const val TABLE_LEVEL_OBS = "leveling_observations"

/** Una lectura de la libreta con sus datos calculados (solo para mostrar). */
data class LevelRowView(
    val observation: LevelingObservationEntity,
    val pointCode: String,
    val calc: LevelingRowResult?,
    /** «corregido: original X → vigente Y» o null. */
    val correction: String?,
)

data class LevelingEvaluation(
    val run: LevelingRunEntity,
    val startBmCode: String,
    val startElevation: Double?,
    val endBmCode: String?,
    val knownEndElevation: Double?,
    val rows: List<LevelRowView>,
    val result: DifferentialLevelingResult?,
    val misclosure: Double?,
    val profile: LevelingProfile?,
    val state: ResultState,
    val fingerprint: String,
    /** Qué impide calcular (sin BM con cota, sin lecturas…). */
    val blockers: List<String>,
)

@Singleton
class LevelingRepository @Inject constructor(
    private val db: TopoScribeDatabase,
    private val history: EditHistoryRepository,
) {
    fun observeRuns(projectId: Long): Flow<List<LevelingRunEntity>> = db.levelingRunDao().observeByProject(projectId)
    fun observeObservations(runId: Long): Flow<List<LevelingObservationEntity>> = db.levelingObservationDao().observeByRun(runId)
    suspend fun runs(projectId: Long): List<LevelingRunEntity> = db.levelingRunDao().listSnapshot(projectId).sortedBy { it.id }
    suspend fun observations(runId: Long): List<LevelingObservationEntity> = db.levelingObservationDao().listSnapshot(runId)

    // ---- nivelaciones ----

    /** El BM inicial debe ser un punto del proyecto con cota conocida (control). */
    suspend fun createRun(projectId: Long, name: String, startBmPointId: Long, endBmPointId: Long?, endKnownElevation: Double?): Outcome<Long> {
        val start = db.pointDao().getById(startBmPointId)
        if (start == null || start.projectId != projectId) return Outcome.Fail("El BM inicial no existe en este proyecto.")
        if (start.fixedElevation == null) return Outcome.Fail("El BM inicial «${start.code}» no tiene cota conocida: edite el punto e indique su cota.")
        if (endBmPointId != null) {
            val end = db.pointDao().getById(endBmPointId)
            if (end == null || end.projectId != projectId) return Outcome.Fail("El BM final no existe en este proyecto.")
            if (end.id == start.id) return Outcome.Fail("El BM final no puede ser el mismo que el inicial.")
            if (end.fixedElevation == null && endKnownElevation == null) return Outcome.Fail("El BM final «${end.code}» no tiene cota conocida.")
        }
        val clean = name.trim().ifEmpty { "Nivelación desde ${start.code}" }
        return Outcome.Ok(
            db.levelingRunDao().insert(
                LevelingRunEntity(projectId = projectId, name = clean, startBmPointId = startBmPointId, endBmPointId = endBmPointId, endKnownElevation = endKnownElevation, createdAt = Instant.now().toString()),
            ),
        )
    }

    suspend fun updateRun(run: LevelingRunEntity, name: String, endBmPointId: Long?, endKnownElevation: Double?): Outcome<Unit> {
        if (endBmPointId != null) {
            val end = db.pointDao().getById(endBmPointId) ?: return Outcome.Fail("El BM final no existe.")
            if (end.id == run.startBmPointId) return Outcome.Fail("El BM final no puede ser el mismo que el inicial.")
            if (end.fixedElevation == null && endKnownElevation == null) return Outcome.Fail("El BM final «${end.code}» no tiene cota conocida.")
        }
        db.levelingRunDao().update(run.copy(name = name.trim().ifEmpty { run.name }, endBmPointId = endBmPointId, endKnownElevation = endKnownElevation, updatedAt = Instant.now().toString()))
        return Outcome.Ok(Unit)
    }

    /** Elimina la nivelación con sus lecturas y retira las cotas que había calculado. */
    suspend fun deleteRun(run: LevelingRunEntity) = db.withTransaction {
        retireRunResults(run)
        db.levelingRunDao().delete(run)
    }

    // ---- lecturas ----

    private suspend fun validateReading(runId: Long, pointId: Long, value: Double, partial: Double?, type: ReadingType, editingId: Long?): String? {
        val run = db.levelingRunDao().getById(runId) ?: return "La nivelación ya no existe."
        val point = db.pointDao().getById(pointId)
        if (point == null || point.projectId != run.projectId) return "El punto de la lectura no existe en este proyecto."
        if (value.isNaN() || value <= 0.0) return "La lectura de mira debe ser un número mayor que 0 (en las unidades del proyecto)."
        if (value > 6.0) return "La lectura de mira (${"%.3f".format(value)}) es mayor que 6 m: revise el valor."
        if (partial != null && (partial.isNaN() || partial < 0.0)) return "La distancia parcial no puede ser negativa."
        if (type == ReadingType.BS && partial != null && partial != 0.0) return "La distancia parcial se registra en las vistas intermedias y adelante, no en la vista atrás."
        val existing = db.levelingObservationDao().listSnapshot(runId).filter { it.id != editingId }
        if (existing.isEmpty() && editingId == null && type != ReadingType.BS) return "La primera lectura debe ser una Vista Atrás (VA) sobre el BM de referencia."
        return null
    }

    suspend fun addReading(runId: Long, pointId: Long, type: ReadingType, value: Double, turning: Boolean, partialDistance: Double?, notes: String?): Outcome<Long> {
        validateReading(runId, pointId, value, partialDistance, type, null)?.let { return Outcome.Fail(it) }
        val order = (db.levelingObservationDao().listSnapshot(runId).maxOfOrNull { it.sequenceOrder } ?: 0) + 1
        return Outcome.Ok(
            db.levelingObservationDao().insert(
                LevelingObservationEntity(
                    levelingRunId = runId, sequenceOrder = order, pointId = pointId, readingType = type, readingValue = value,
                    isTurningPoint = turning && type == ReadingType.FS, partialDistance = if (type == ReadingType.BS) null else partialDistance,
                    rawNotes = notes?.ifBlank { null }, createdAt = Instant.now().toString(),
                ),
            ),
        )
    }

    /** Edita una lectura; el valor anterior de cada campo cambiado queda en el historial. */
    suspend fun updateReading(
        before: LevelingObservationEntity, pointId: Long, type: ReadingType, value: Double, turning: Boolean, partialDistance: Double?, notes: String?,
    ): Outcome<Unit> {
        validateReading(before.levelingRunId, pointId, value, partialDistance, type, before.id)?.let { return Outcome.Fail(it) }
        val oldCode = db.pointDao().getById(before.pointId)?.code ?: before.pointId.toString()
        val newCode = db.pointDao().getById(pointId)?.code ?: pointId.toString()
        history.logChanges(
            TABLE_LEVEL_OBS, before.id,
            listOf(
                EditHistoryRepository.Change("point", oldCode, newCode),
                EditHistoryRepository.Change("reading_type", before.readingType, type),
                EditHistoryRepository.Change("reading_value", fmt3(before.readingValue), fmt3(value)),
                EditHistoryRepository.Change("is_turning_point", before.isTurningPoint, turning && type == ReadingType.FS),
                EditHistoryRepository.Change("partial_distance", before.partialDistance?.let(::fmt3), partialDistance?.let(::fmt3)),
                EditHistoryRepository.Change("raw_notes", before.rawNotes, notes?.ifBlank { null }),
            ),
        )
        db.levelingObservationDao().update(
            before.copy(pointId = pointId, readingType = type, readingValue = value, isTurningPoint = turning && type == ReadingType.FS,
                partialDistance = if (type == ReadingType.BS) null else partialDistance, rawNotes = notes?.ifBlank { null }, updatedAt = Instant.now().toString()),
        )
        return Outcome.Ok(Unit)
    }

    suspend fun deleteReading(obs: LevelingObservationEntity) = db.levelingObservationDao().softDelete(obs.id, Instant.now().toString())

    private fun fmt3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)

    // ---- evaluación (pura, sin escribir) y cálculo (guarda) ----

    /** BM final conocido: cota propia de la nivelación o la cota fija del punto BM final. */
    private suspend fun knownEnd(run: LevelingRunEntity): Pair<String?, Double?> {
        val endPoint = run.endBmPointId?.let { db.pointDao().getById(it) }
        val elev = run.endKnownElevation ?: endPoint?.fixedElevation
        return endPoint?.code to elev
    }

    suspend fun evaluate(runId: Long): LevelingEvaluation? {
        val run = db.levelingRunDao().getById(runId) ?: return null
        val points = db.pointDao().listByProject(run.projectId).associateBy { it.id }
        val start = points[run.startBmPointId]
        val readings = db.levelingObservationDao().listSnapshot(runId)
        val (endCode, endElev) = knownEnd(run)
        val histories = history.historyByRow(TABLE_LEVEL_OBS)

        val blockers = mutableListOf<String>()
        if (start?.fixedElevation == null) blockers += "El BM inicial no tiene cota conocida."
        if (readings.isEmpty()) blockers += "No hay lecturas registradas."

        val fingerprint = inputFingerprint(
            listOf("run", run.id.toString(), run.startBmPointId.toString(), start?.fixedElevation.toString(), run.endBmPointId.toString(), endElev.toString()) +
                readings.map { "${it.id}|${it.sequenceOrder}|${it.pointId}|${it.readingType}|${it.readingValue}|${it.isTurningPoint}|${it.partialDistance}" },
        )
        var result: DifferentialLevelingResult? = null
        if (blockers.isEmpty()) {
            result = computeDifferentialLeveling(
                readings.map { LevelingObservationCalcInput(it.id, it.sequenceOrder, it.pointId, it.readingType, it.readingValue, it.isTurningPoint, it.partialDistance) },
                start!!.fixedElevation!!,
            )
        }
        val byObs = result?.rows?.associateBy { it.observationId }.orEmpty()
        val rows = readings.map { r ->
            val h = histories[r.id]
            val corr = listOfNotNull(
                correctionText(h, "reading_value", fmt3(r.readingValue)),
                correctionText(h, "point", points[r.pointId]?.code ?: ""),
                correctionText(h, "partial_distance", r.partialDistance?.let(::fmt3) ?: "—"),
            ).joinToString("; ").ifEmpty { null }
            LevelRowView(r, points[r.pointId]?.code ?: "?", byObs[r.id], corr)
        }
        val snapshot = db.calcSnapshotDao().find(run.projectId, KIND_ALTIMETRIA, run.id)
        var state = resultState(snapshot?.inputHash, fingerprint)
        if (snapshot == null && hasLegacyResults(run)) state = ResultState.VERSION_ANTERIOR
        val codes = points.mapValues { it.value.code }
        return LevelingEvaluation(
            run = run, startBmCode = start?.code ?: "?", startElevation = start?.fixedElevation, endBmCode = endCode, knownEndElevation = endElev,
            rows = rows, result = result, misclosure = if (result != null && endElev != null) computeFieldMisclosure(result.finalElevation, endElev) else null,
            profile = result?.let { buildLevelingProfile(it.rows, codes) }, state = state, fingerprint = fingerprint, blockers = blockers,
        )
    }

    private suspend fun hasLegacyResults(run: LevelingRunEntity): Boolean {
        val ids = db.levelingObservationDao().allIdsByRun(run.id).toSet()
        return db.computedPointDao().listCurrentSnapshot(run.projectId).any { c ->
            c.method == "nivelacion_geometrica" && c.sourceObservationIds.any { it.table == TABLE_LEVEL_OBS && it.id in ids }
        }
    }

    private suspend fun retireRunResults(run: LevelingRunEntity) {
        val ids = db.levelingObservationDao().allIdsByRun(run.id).toSet()
        for (c in db.computedPointDao().listCurrentSnapshot(run.projectId)) {
            if (c.method == "nivelacion_geometrica" && c.sourceObservationIds.any { it.table == TABLE_LEVEL_OBS && it.id in ids }) db.computedPointDao().retireById(c.id)
        }
    }

    /** Guarda las cotas calculadas (Recalcular = filas nuevas, las anteriores se retiran) y la huella del cálculo. */
    suspend fun compute(runId: Long): Outcome<LevelingEvaluation> {
        val eval = evaluate(runId) ?: return Outcome.Fail("La nivelación ya no existe.")
        if (eval.blockers.isNotEmpty() || eval.result == null) return Outcome.Fail("No se puede calcular: " + eval.blockers.joinToString(" "))
        val now = Instant.now().toString()
        db.withTransaction {
            retireRunResults(eval.run)
            for (row in eval.result.rows) {
                if (row.readingType == ReadingType.BS || row.elevation.isNaN()) continue
                db.computedPointDao().insert(
                    ComputedPointEntity(
                        projectId = eval.run.projectId, pointId = row.pointId, east = null, north = null, elevation = row.elevation,
                        method = "nivelacion_geometrica", formulaId = "NIVELACION-DIFERENCIAL",
                        sourceObservationIds = listOf(SourceObservationRef(TABLE_LEVEL_OBS, row.observationId)), computedAt = now,
                    ),
                )
            }
            db.calcSnapshotDao().upsert(CalcSnapshotEntity(projectId = eval.run.projectId, kind = KIND_ALTIMETRIA, subjectId = eval.run.id, inputHash = eval.fingerprint, computedAt = now))
        }
        return Outcome.Ok(evaluate(runId)!!)
    }
}
