package com.toposcribe.app.data.repository

import com.toposcribe.calc.LevelingObservationCalcInput
import com.toposcribe.calc.LevelingRowResult
import com.toposcribe.calc.ReadingType
import com.toposcribe.calc.computeDifferentialLeveling
import com.toposcribe.calc.computeFieldMisclosure
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.ComputedPointEntity
import com.toposcribe.data.local.entity.LevelingObservationEntity
import com.toposcribe.data.local.entity.LevelingRunEntity
import com.toposcribe.data.local.entity.SourceObservationRef
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

class LevelingComputeException(message: String) : Exception(message)

data class LevelingComputeResult(
    val startElevation: Double,
    val finalElevation: Double,
    val sumBacksight: Double,
    val sumForesight: Double,
    val checkValue: Double,
    val arithmeticCheckOk: Boolean,
    val warnings: List<String>,
    val insertedCount: Int,
    val rows: List<LevelingRowResult>,
    val fieldMisclosure: Double?,
    val knownEndElevation: Double?,
)

@Singleton
class LevelingRepository @Inject constructor(
    private val db: TopoScribeDatabase,
) {
    fun observeRunsByProject(projectId: Long): Flow<List<LevelingRunEntity>> = db.levelingRunDao().observeByProject(projectId)

    fun observeObservations(runId: Long): Flow<List<LevelingObservationEntity>> = db.levelingObservationDao().observeByRun(runId)

    suspend fun createRun(run: LevelingRunEntity): Long = db.levelingRunDao().insert(run.copy(createdAt = Instant.now().toString()))

    suspend fun createObservation(observation: LevelingObservationEntity): Long =
        db.levelingObservationDao().insert(observation.copy(createdAt = Instant.now().toString()))

    suspend fun updateObservation(observation: LevelingObservationEntity) =
        db.levelingObservationDao().update(observation.copy(updatedAt = Instant.now().toString()))

    suspend fun softDeleteObservation(id: Long) = db.levelingObservationDao().softDelete(id, Instant.now().toString())

    /**
     * Resuelve la cota de cierre INDEPENDIENTEMENTE conocida de una corrida (no la que esta misma
     * corrida calcula): primero endKnownElevation si está, si no el fixedElevation crudo (no vía
     * resolveKnownPoints, que podría devolver un valor calculado por esta misma corrida) del
     * punto en endBmPointId, solo si ese punto es de control. Si no hay ninguna, no hay cierre de
     * campo que mostrar -- nunca se fabrica uno.
     */
    suspend fun resolveKnownEndElevation(run: LevelingRunEntity): Double? {
        run.endKnownElevation?.let { return it }
        val endPointId = run.endBmPointId ?: return null
        val point = db.pointDao().getById(endPointId)
        return if (point?.isControl == true && point.fixedElevation != null) point.fixedElevation else null
    }

    /**
     * Comprobación de solo lectura (no guarda cotas): ΣVA − ΣVD = ΔCota. Devuelve null si la corrida
     * no se puede evaluar (sin cota de partida u observaciones).
     */
    suspend fun checkRunArithmetic(run: LevelingRunEntity): Boolean? {
        val startElevation = resolveKnownPoints(db, run.projectId)[run.startBmPointId]?.elevation ?: return null
        val snapshot = db.levelingObservationDao().listSnapshot(run.id)
        if (snapshot.isEmpty()) return null
        val input = snapshot.map {
            LevelingObservationCalcInput(it.id, it.sequenceOrder, it.pointId, it.readingType, it.readingValue, it.isTurningPoint)
        }
        return computeDifferentialLeveling(input, startElevation).arithmeticCheckOk
    }

    suspend fun computeLevelingRun(runId: Long): LevelingComputeResult {
        val run = db.levelingRunDao().getById(runId) ?: throw LevelingComputeException("La nivelación $runId no existe.")

        val known = resolveKnownPoints(db, run.projectId)
        val startState = known[run.startBmPointId]
        if (startState?.elevation == null) {
            throw LevelingComputeException("No se conoce la cota del punto de referencia (BM) inicial. Márquelo como punto de control con una cota fija, o calcule primero la observación que lo define.")
        }

        val obsSnapshot = db.levelingObservationDao().listSnapshot(runId)
        if (obsSnapshot.isEmpty()) {
            throw LevelingComputeException("Esta nivelación no tiene observaciones registradas todavía.")
        }

        val calcInput = obsSnapshot.map {
            LevelingObservationCalcInput(it.id, it.sequenceOrder, it.pointId, it.readingType, it.readingValue, it.isTurningPoint)
        }
        val result = computeDifferentialLeveling(calcInput, startState.elevation)

        var insertedCount = 0
        val now = Instant.now().toString()
        for (row in result.rows) {
            if (row.readingType == ReadingType.BS || row.elevation.isNaN()) continue
            db.computedPointDao().retireCurrent(row.pointId, "nivelacion_geometrica")
            db.computedPointDao().insert(
                ComputedPointEntity(
                    projectId = run.projectId,
                    pointId = row.pointId,
                    east = null,
                    north = null,
                    elevation = row.elevation,
                    method = "nivelacion_geometrica",
                    formulaId = "NIVELACION-DIFERENCIAL",
                    sourceObservationIds = listOf(SourceObservationRef("leveling_observations", row.observationId)),
                    computedAt = now,
                ),
            )
            insertedCount += 1
        }

        val knownEndElevation = resolveKnownEndElevation(run)
        val fieldMisclosure = knownEndElevation?.let { computeFieldMisclosure(result.finalElevation, it) }

        return LevelingComputeResult(
            startElevation = result.startElevation,
            finalElevation = result.finalElevation,
            sumBacksight = result.sumBacksight,
            sumForesight = result.sumForesight,
            checkValue = result.checkValue,
            arithmeticCheckOk = result.arithmeticCheckOk,
            warnings = result.warnings,
            insertedCount = insertedCount,
            rows = result.rows,
            fieldMisclosure = fieldMisclosure,
            knownEndElevation = knownEndElevation,
        )
    }
}
