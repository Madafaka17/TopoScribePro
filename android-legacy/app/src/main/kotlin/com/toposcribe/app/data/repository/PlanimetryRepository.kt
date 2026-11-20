package com.toposcribe.app.data.repository

import com.toposcribe.calc.bearingToAzimuth
import com.toposcribe.calc.computePolarCoordinates
import com.toposcribe.calc.elevationDiffFromVerticalAngle
import com.toposcribe.calc.elevationDiffFromZenith
import com.toposcribe.calc.horizontalDistanceFromVerticalAngle
import com.toposcribe.calc.horizontalDistanceFromZenith
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.ComputedPointEntity
import com.toposcribe.data.local.entity.InstrumentType
import com.toposcribe.data.local.entity.PlanimetryMethod
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import com.toposcribe.data.local.entity.SourceObservationRef
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

data class ComputeWarning(val observationId: Long, val targetPointId: Long, val message: String)
data class PlanimetryComputeResult(val computedCount: Int, val warnings: List<ComputeWarning>)

private fun resolveAzimuth(obs: PlanimetryObservationEntity): Double? {
    obs.azimuth?.let { return it }
    obs.bearing?.let { return bearingToAzimuth(it) }
    return null
}

/** Compartida con TraverseRepository: mismo cálculo de distancia horizontal reducida (incluida la
 * constante prismática de estación total) para radiación y para tramos de poligonal. */
internal fun resolveHorizontalDistance(obs: PlanimetryObservationEntity, prismConstant: Double): Double? {
    obs.horizontalDistance?.let { return it }
    val slopeDistance = obs.slopeDistance?.plus(prismConstant) ?: return null
    obs.zenithAngle?.let { return horizontalDistanceFromZenith(slopeDistance, it) }
    obs.verticalAngle?.let { return horizontalDistanceFromVerticalAngle(slopeDistance, it) }
    return null
}

@Singleton
class PlanimetryRepository @Inject constructor(
    private val db: TopoScribeDatabase,
) {
    fun observeByProject(projectId: Long): Flow<List<PlanimetryObservationEntity>> = db.planimetryObservationDao().observeByProject(projectId)

    suspend fun create(observation: PlanimetryObservationEntity): Long =
        db.planimetryObservationDao().insert(observation.copy(createdAt = Instant.now().toString()))

    suspend fun update(observation: PlanimetryObservationEntity) =
        db.planimetryObservationDao().update(observation.copy(updatedAt = Instant.now().toString()))

    suspend fun softDelete(id: Long) = db.planimetryObservationDao().softDelete(id, Instant.now().toString())

    /**
     * Liga a la poligonal las observaciones de la libreta que aún no pertenecen a ninguna y traen
     * ángulo interior (lectura horizontal), en el orden en que se registraron. Solo actúa si la
     * poligonal todavía no tiene tramos, para no mover observaciones ya asignadas. Devuelve cuántas ligó.
     */
    suspend fun adoptFreeObservations(projectId: Long, traverseId: Long): Int {
        val dao = db.planimetryObservationDao()
        if (dao.listByTraverse(traverseId).isNotEmpty()) return 0
        val free = dao.listSnapshot(projectId)
            .filter { it.traverseId == null && it.horizontalAngle != null }
            .sortedBy { it.id }
        free.forEachIndexed { index, obs ->
            dao.update(obs.copy(traverseId = traverseId, sequenceOrder = index + 1, updatedAt = Instant.now().toString()))
        }
        return free.size
    }

    private data class ElevationInfo(val delta: Double, val formulaId: String)

    private fun resolveElevationDelta(obs: PlanimetryObservationEntity, prismConstant: Double): ElevationInfo? {
        // Variables locales, no el smart-cast de obs.xxx: Kotlin no garantiza el smart-cast de un
        // val declarado en otro módulo (PlanimetryObservationEntity vive en :data), aunque ya se
        // haya comprobado que no es null acá mismo.
        val rawSlopeDistance = obs.slopeDistance ?: return null
        val instrumentHeight = obs.instrumentHeight ?: return null
        val prismHeight = obs.prismHeight ?: return null
        val slopeDistance = rawSlopeDistance + prismConstant
        obs.zenithAngle?.let {
            return ElevationInfo(elevationDiffFromZenith(slopeDistance, it, instrumentHeight, prismHeight), "REDUCCION-ZENITAL")
        }
        obs.verticalAngle?.let {
            return ElevationInfo(elevationDiffFromVerticalAngle(slopeDistance, it, instrumentHeight, prismHeight), "REDUCCION-VERTICAL")
        }
        return null
    }

    /**
     * Calcula coordenadas por radiación (azimut+distancia) propagando desde los puntos conocidos
     * (control o ya calculados) hacia los visados, mediante un algoritmo de lista de trabajo: se
     * repite hasta que una pasada completa no resuelva ningún punto más. Lo que quede sin
     * resolver se reporta como advertencia (nunca se calcula con datos inventados ni se oculta el
     * punto).
     */
    suspend fun computePlanimetry(projectId: Long): PlanimetryComputeResult {
        val project = db.projectDao().getById(projectId)
        val prismConstant = if (project?.instrumentType == InstrumentType.ESTACION_TOTAL) project.prismConstantM else 0.0
        val known = resolveKnownPoints(db, projectId).toMutableMap()
        val allObsSnapshot = db.planimetryObservationDao().listSnapshot(projectId)

        val warnings = mutableListOf<ComputeWarning>()
        var pending = allObsSnapshot.filter { obs ->
            if (obs.method == PlanimetryMethod.ANGULO_DISTANCIA) {
                warnings.add(
                    ComputeWarning(
                        obs.id,
                        obs.targetPointId,
                        "Método ángulo+distancia: aún no se calcula automáticamente fuera de una poligonal. Conviértalo a azimut+distancia o use la herramienta de poligonal.",
                    ),
                )
                false
            } else {
                true
            }
        }

        var computedCount = 0
        var progress = true
        while (progress && pending.isNotEmpty()) {
            progress = false
            val stillPending = mutableListOf<PlanimetryObservationEntity>()

            for (obs in pending) {
                val occupied = known[obs.occupiedPointId]
                if (occupied == null || occupied.east == null || occupied.north == null) {
                    stillPending.add(obs)
                    continue
                }

                val azimuth = resolveAzimuth(obs)
                if (azimuth == null) {
                    warnings.add(ComputeWarning(obs.id, obs.targetPointId, "Falta el azimut o rumbo de la observación; no se puede calcular la posición."))
                    continue
                }

                val horizontalDistance = resolveHorizontalDistance(obs, prismConstant)
                if (horizontalDistance == null) {
                    warnings.add(
                        ComputeWarning(obs.id, obs.targetPointId, "Faltan datos de distancia (horizontal, o inclinada con ángulo cenital/vertical); no se puede calcular la posición."),
                    )
                    continue
                }

                val result = computePolarCoordinates(
                    com.toposcribe.calc.PolarCoordinatesInput(occupied.east, occupied.north, azimuth, horizontalDistance),
                )

                var elevation: Double? = null
                val formulaIds = mutableListOf("RADIACION")
                val rowWarnings = mutableListOf<String>()
                val elevationInfo = resolveElevationDelta(obs, prismConstant)
                if (elevationInfo != null && occupied.elevation != null) {
                    elevation = occupied.elevation + elevationInfo.delta
                    formulaIds.add(elevationInfo.formulaId)
                } else if ((obs.zenithAngle != null || obs.verticalAngle != null) && occupied.elevation == null) {
                    rowWarnings.add("No se calculó la cota del punto visado: se conoce el ángulo vertical pero no la cota del punto ocupado.")
                }

                db.computedPointDao().retireCurrent(obs.targetPointId, "radiacion")
                db.computedPointDao().insert(
                    ComputedPointEntity(
                        projectId = projectId,
                        pointId = obs.targetPointId,
                        east = result.east,
                        north = result.north,
                        elevation = elevation,
                        method = "radiacion",
                        formulaId = formulaIds.joinToString("+"),
                        sourceObservationIds = listOf(SourceObservationRef("planimetry_observations", obs.id)),
                        warnings = rowWarnings.ifEmpty { null },
                        computedAt = Instant.now().toString(),
                    ),
                )

                known[obs.targetPointId] = ResolvedPointState(
                    east = result.east,
                    north = result.north,
                    elevation = elevation ?: known[obs.targetPointId]?.elevation,
                )

                computedCount += 1
                progress = true
                warnings.addAll(rowWarnings.map { ComputeWarning(obs.id, obs.targetPointId, it) })
            }

            pending = stillPending
        }

        for (obs in pending) {
            warnings.add(
                ComputeWarning(
                    obs.id,
                    obs.targetPointId,
                    "No se pudo calcular: no se conoce la posición del punto de estación ocupada (ni es punto de control ni fue calculado desde otra observación).",
                ),
            )
        }

        return PlanimetryComputeResult(computedCount, warnings)
    }
}
