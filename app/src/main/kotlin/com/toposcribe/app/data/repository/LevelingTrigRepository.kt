package com.toposcribe.app.data.repository

import com.toposcribe.calc.TrigLevelingInput
import com.toposcribe.calc.computeTrigElevation
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.ComputedPointEntity
import com.toposcribe.data.local.entity.LevelingTrigObservationEntity
import com.toposcribe.data.local.entity.SourceObservationRef
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class LevelingTrigRepository @Inject constructor(
    private val db: TopoScribeDatabase,
) {
    fun observeByProject(projectId: Long): Flow<List<LevelingTrigObservationEntity>> = db.levelingTrigObservationDao().observeByProject(projectId)

    suspend fun create(observation: LevelingTrigObservationEntity): Long =
        db.levelingTrigObservationDao().insert(observation.copy(createdAt = Instant.now().toString()))

    suspend fun update(observation: LevelingTrigObservationEntity) =
        db.levelingTrigObservationDao().update(observation.copy(updatedAt = Instant.now().toString()))

    suspend fun softDelete(id: Long) = db.levelingTrigObservationDao().softDelete(id, Instant.now().toString())

    /** Igual que la radiación planimétrica: propaga desde puntos con cota conocida (control o ya
     * calculados) mediante una lista de trabajo, hasta que una pasada completa no resuelva más. */
    suspend fun computeLevelingTrig(projectId: Long): PlanimetryComputeResult {
        val known = resolveKnownPoints(db, projectId).toMutableMap()
        var pending = pendingSnapshot(projectId)
        val warnings = mutableListOf<ComputeWarning>()
        var computedCount = 0
        var progress = true

        while (progress && pending.isNotEmpty()) {
            progress = false
            val stillPending = mutableListOf<LevelingTrigObservationEntity>()

            for (obs in pending) {
                val occupied = known[obs.occupiedPointId]
                if (occupied?.elevation == null) {
                    stillPending.add(obs)
                    continue
                }

                val result = computeTrigElevation(
                    TrigLevelingInput(
                        startElevation = occupied.elevation,
                        slopeDistance = obs.slopeDistance,
                        horizontalDistance = obs.horizontalDistance,
                        zenithDeg = obs.zenithAngle,
                        verticalAngleDeg = obs.verticalAngle,
                        instrumentHeight = obs.instrumentHeight,
                        targetHeight = obs.targetHeight,
                    ),
                )
                if (result == null) {
                    warnings.add(ComputeWarning(obs.id, obs.targetPointId, "Faltan datos de distancia o ángulo para calcular esta observación."))
                    continue
                }

                db.computedPointDao().retireCurrent(obs.targetPointId, "nivelacion_trigonometrica")
                db.computedPointDao().insert(
                    ComputedPointEntity(
                        projectId = projectId,
                        pointId = obs.targetPointId,
                        east = null,
                        north = null,
                        elevation = result.finalElevation,
                        method = "nivelacion_trigonometrica",
                        formulaId = result.formulaId,
                        sourceObservationIds = listOf(SourceObservationRef("leveling_trig_observations", obs.id)),
                        computedAt = Instant.now().toString(),
                    ),
                )

                known[obs.targetPointId] = (known[obs.targetPointId] ?: ResolvedPointState()).copy(elevation = result.finalElevation)
                computedCount += 1
                progress = true
            }
            pending = stillPending
        }

        for (obs in pending) {
            warnings.add(ComputeWarning(obs.id, obs.targetPointId, "No se pudo calcular: no se conoce la cota del punto de estación ocupada."))
        }

        return PlanimetryComputeResult(computedCount, warnings)
    }

    private suspend fun pendingSnapshot(projectId: Long): List<LevelingTrigObservationEntity> =
        db.levelingTrigObservationDao().listSnapshot(projectId)
}
