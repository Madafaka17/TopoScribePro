package com.toposcribe.app.data.repository

import com.toposcribe.calc.BowditchApplicability
import com.toposcribe.calc.BowditchApplicabilityInput
import com.toposcribe.calc.LegDelta
import com.toposcribe.calc.StationCoordinate
import com.toposcribe.calc.applyBowditch
import com.toposcribe.calc.chainAzimuths
import com.toposcribe.calc.checkBowditchApplicability
import com.toposcribe.calc.computeAngularClosureClosedPolygon
import com.toposcribe.calc.computeLinearClosure
import com.toposcribe.calc.legDelta
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.AdjustmentEntity
import com.toposcribe.data.local.entity.ComputedPointEntity
import com.toposcribe.data.local.entity.InstrumentType
import com.toposcribe.data.local.entity.PointCorrection
import com.toposcribe.data.local.entity.SourceObservationRef
import com.toposcribe.data.local.entity.TraverseEntity
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Error de validación que impide calcular la poligonal del todo (faltan datos imprescindibles) --
 * distinto de una advertencia: se le muestra al usuario como bloqueante, no se calcula a medias. */
class TraverseComputeException(message: String) : Exception(message)

data class TraverseAngularClosure(
    val sumMeasuredDeg: Double,
    val theoreticalSumDeg: Double,
    val misclosureDeg: Double,
    val correctionPerAngleDeg: Double,
)

data class TraverseLinearClosure(
    val fE: Double,
    val fN: Double,
    val fL: Double,
    val perimeter: Double,
    val relativePrecisionLabel: String,
)

data class TraverseStationResult(
    val pointId: Long,
    val rawEast: Double,
    val rawNorth: Double,
    val adjustedEast: Double?,
    val adjustedNorth: Double?,
)

data class TraverseComputeResult(
    val legsCount: Int,
    val angularClosure: TraverseAngularClosure?,
    val angularClosureNote: String?,
    val linearClosure: TraverseLinearClosure?,
    val bowditch: BowditchApplicability,
    val stations: List<TraverseStationResult>,
    val warnings: List<String>,
    /** Coordenadas del punto de partida: la UI las necesita para derivar ΔE/ΔN de cada tramo. */
    val startEast: Double = 0.0,
    val startNorth: Double = 0.0,
)

@Singleton
class TraverseRepository @Inject constructor(
    private val db: TopoScribeDatabase,
) {
    fun observeByProject(projectId: Long): Flow<List<TraverseEntity>> = db.traverseDao().observeByProject(projectId)

    suspend fun create(traverse: TraverseEntity): Long =
        db.traverseDao().insert(traverse.copy(createdAt = Instant.now().toString()))

    suspend fun update(traverse: TraverseEntity) =
        db.traverseDao().update(traverse.copy(updatedAt = Instant.now().toString()))

    suspend fun listAdjustments(traverseId: Long): List<AdjustmentEntity> = db.adjustmentDao().listByTraverse(traverseId)

    /**
     * Calcula el cierre angular (si corresponde), encadena azimutes, calcula el cierre lineal y
     * aplica Bowditch cuando sea aplicable. Las coordenadas BRUTAS siempre se calculan y guardan
     * (method="poligonal_bruto"), aunque no haya cierre conocido; las compensadas son una fila
     * APARTE (method="poligonal_bowditch") solo cuando corresponde -- nunca se sobrescribe el bruto.
     */
    suspend fun computeTraverse(traverseId: Long): TraverseComputeResult {
        val traverse = db.traverseDao().getById(traverseId)
            ?: throw TraverseComputeException("La poligonal $traverseId no existe.")

        val legs = db.planimetryObservationDao().listByTraverse(traverseId)
        val minLegs = if (traverse.type == com.toposcribe.calc.TraverseType.CERRADA_POLIGONO) 3 else 1
        if (legs.size < minLegs) {
            throw TraverseComputeException(
                if (traverse.type == com.toposcribe.calc.TraverseType.CERRADA_POLIGONO) {
                    "Una poligonal cerrada necesita al menos 3 tramos (estaciones) para calcularse."
                } else {
                    "Esta poligonal todavía no tiene tramos (observaciones) registrados."
                },
            )
        }

        val project = db.projectDao().getById(traverse.projectId)
        val prismConstant = if (project?.instrumentType == InstrumentType.ESTACION_TOTAL) project.prismConstantM else 0.0

        val warnings = mutableListOf<String>()
        val interiorAngles = mutableListOf<Double>()
        val horizontalDistances = mutableListOf<Double>()

        for (leg in legs) {
            val angle = leg.horizontalAngle
                ?: throw TraverseComputeException("El tramo de \"${leg.occupiedPointId}\" a \"${leg.targetPointId}\" no tiene registrado el ángulo interior; no se puede calcular la poligonal.")
            val dist = resolveHorizontalDistance(leg, prismConstant)
                ?: throw TraverseComputeException("El tramo de \"${leg.occupiedPointId}\" a \"${leg.targetPointId}\" no tiene distancia suficiente (horizontal, o inclinada con ángulo cenital/vertical).")
            interiorAngles.add(angle)
            horizontalDistances.add(dist)
        }

        val known = resolveKnownPoints(db, traverse.projectId)

        val startPointId = traverse.startPointId
            ?: throw TraverseComputeException("La poligonal no tiene definido un punto de partida.")
        val startState = known[startPointId]
        if (startState?.east == null || startState.north == null) {
            throw TraverseComputeException("No se conoce la posición (E/N) del punto de partida de la poligonal. Márquelo como punto de control o calcúlelo primero.")
        }
        val startAzimuth = traverse.startAzimuth
            ?: throw TraverseComputeException("La poligonal no tiene definido un azimut de arranque; es necesario para orientar los tramos.")

        var angularClosure: TraverseAngularClosure? = null
        var angularClosureNote: String? = null
        var balancedAngles = interiorAngles.toList()

        if (traverse.type == com.toposcribe.calc.TraverseType.CERRADA_POLIGONO) {
            val closure = computeAngularClosureClosedPolygon(interiorAngles)
            angularClosure = TraverseAngularClosure(closure.sumMeasuredDeg, closure.theoreticalSumDeg, closure.misclosureDeg, closure.correctionPerAngleDeg)
            balancedAngles = closure.balancedAnglesDeg
        } else {
            angularClosureNote = "El cierre angular teórico (suma de ángulos) solo se calcula para poligonales cerradas: una poligonal abierta necesitaría también un azimut de cierre conocido."
        }

        val azimuths = chainAzimuths(startAzimuth, balancedAngles)
        val legDeltas = legs.indices.map { i -> legDelta(azimuths[i], horizontalDistances[i]) }

        var knownEndEast: Double? = null
        var knownEndNorth: Double? = null
        if (traverse.type == com.toposcribe.calc.TraverseType.CERRADA_POLIGONO) {
            knownEndEast = startState.east
            knownEndNorth = startState.north
            if (legs.last().targetPointId != traverse.startPointId) {
                warnings.add("El último tramo registrado no vuelve al punto de partida; revise el orden de las observaciones de esta poligonal cerrada.")
            }
        } else if (traverse.type == com.toposcribe.calc.TraverseType.ABIERTA_AMARRADA && traverse.endPointId != null) {
            val endState = known[traverse.endPointId]
            if (endState?.east != null && endState.north != null) {
                knownEndEast = endState.east
                knownEndNorth = endState.north
            }
        }

        val bothEndsKnown = knownEndEast != null && knownEndNorth != null
        val bowditch = checkBowditchApplicability(BowditchApplicabilityInput(traverse.type, bothEndsKnown))

        var linearClosure: TraverseLinearClosure? = null
        var adjustedStations: List<StationCoordinate>? = null

        if (bothEndsKnown) {
            val closure = computeLinearClosure(legDeltas, startState.east, startState.north, knownEndEast!!, knownEndNorth!!)
            linearClosure = TraverseLinearClosure(closure.fE, closure.fN, closure.fL, closure.perimeter, closure.relativePrecisionLabel)

            if (bowditch.applicable) {
                val adjustment = applyBowditch(legDeltas, closure.fE, closure.fN, closure.perimeter, startState.east, startState.north)
                adjustedStations = adjustment.adjustedStations
                val perPointCorrections = legs.mapIndexed { i, leg ->
                    PointCorrection(leg.targetPointId, adjustment.corrections[i].correctionE, adjustment.corrections[i].correctionN)
                }
                db.adjustmentDao().insert(
                    AdjustmentEntity(
                        traverseId = traverseId,
                        method = "bowditch",
                        angularMisclosure = angularClosure?.misclosureDeg,
                        fE = closure.fE,
                        fN = closure.fN,
                        fL = closure.fL,
                        perimeter = closure.perimeter,
                        relativePrecision = closure.relativePrecisionLabel,
                        perPointCorrections = perPointCorrections,
                        createdAt = Instant.now().toString(),
                    ),
                )
            }
        } else {
            warnings.add(bowditch.reason ?: "No se pudo calcular el cierre lineal: falta un punto de cierre conocido.")
        }

        // Coordenadas brutas (sin compensar): siempre se calculan y se guardan, aunque no haya cierre.
        var east = startState.east
        var north = startState.north
        val rawStations = legDeltas.map { d ->
            east += d.deltaE
            north += d.deltaN
            StationCoordinate(east, north)
        }

        val now = Instant.now().toString()
        val stations = legs.mapIndexed { i, leg ->
            val raw = rawStations[i]
            val adjusted = adjustedStations?.get(i)

            db.computedPointDao().retireCurrent(leg.targetPointId, "poligonal_bruto")
            db.computedPointDao().insert(
                ComputedPointEntity(
                    projectId = traverse.projectId,
                    pointId = leg.targetPointId,
                    east = raw.east,
                    north = raw.north,
                    elevation = null,
                    method = "poligonal_bruto",
                    formulaId = "POLIGONAL-BRUTO",
                    sourceObservationIds = listOf(SourceObservationRef("planimetry_observations", leg.id)),
                    computedAt = now,
                ),
            )

            if (adjusted != null) {
                db.computedPointDao().retireCurrent(leg.targetPointId, "poligonal_bowditch")
                db.computedPointDao().insert(
                    ComputedPointEntity(
                        projectId = traverse.projectId,
                        pointId = leg.targetPointId,
                        east = adjusted.east,
                        north = adjusted.north,
                        elevation = null,
                        method = "poligonal_bowditch",
                        formulaId = "POLIGONAL-BOWDITCH",
                        sourceObservationIds = listOf(SourceObservationRef("planimetry_observations", leg.id)),
                        computedAt = now,
                    ),
                )
            }

            TraverseStationResult(leg.targetPointId, raw.east, raw.north, adjusted?.east, adjusted?.north)
        }

        return TraverseComputeResult(legs.size, angularClosure, angularClosureNote, linearClosure, bowditch, stations, warnings, startState.east, startState.north)
    }
}
