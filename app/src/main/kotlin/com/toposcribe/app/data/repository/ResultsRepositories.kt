package com.toposcribe.app.data.repository

import com.toposcribe.calc.ContourCandidate
import com.toposcribe.calc.ContourEligibility
import com.toposcribe.calc.ContourResult
import com.toposcribe.calc.ElevationSource
import com.toposcribe.calc.PlanModel
import com.toposcribe.calc.PlanPoint
import com.toposcribe.calc.PlanPolyline
import com.toposcribe.calc.PlanRadiation
import com.toposcribe.calc.ResultState
import com.toposcribe.calc.computeContours
import com.toposcribe.calc.evaluateContourEligibility
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.CoordinateMode
import com.toposcribe.data.local.entity.DataSource
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.ProjectEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Estado de un punto con sus valores vigentes (guardados) y el origen de cada uno. */
data class PointResult(
    val point: PointEntity,
    val east: Double?,
    val north: Double?,
    val elevation: Double?,
    val elevationSource: ElevationSource,
    val planimetryOrigin: String,
) {
    val hasCoordinates get() = east != null && north != null
}

fun coordinateSystemText(p: ProjectEntity): String =
    if (p.coordinateMode == CoordinateMode.UTM) "UTM — datum ${p.utmDatum ?: "(pendiente)"}, zona ${p.utmZone ?: "(pendiente)"}${p.utmHemisphere?.name ?: ""}"
    else (p.coordinateSystem?.takeIf { it.isNotBlank() } ?: "Local")

@Singleton
class ResultsRepository @Inject constructor(private val db: TopoScribeDatabase) {

    suspend fun getProjectResults(projectId: Long): List<PointResult> {
        val points = db.pointDao().listByProject(projectId)
        val known = resolveKnownPoints(db, projectId)
        return points.map { p ->
            val s = known[p.id]
            val planOrigin = listOfNotNull(s?.eastSource, s?.northSource).map { MethodPriority.label(it.method) }.distinct().joinToString(" + ")
            val elevSource = when (val m = s?.elevationSource?.method) {
                null -> ElevationSource.NINGUNA
                "nivelacion_geometrica" -> ElevationSource.NIVEL_INGENIERO
                "dato_fijo" -> if (p.isControl || p.dataSource == DataSource.CONTROL_OFICIAL || p.dataSource == DataSource.LOCAL_DECLARADO) ElevationSource.CONTROL_CONOCIDO else ElevationSource.OTRO
                else -> if (m.isNotEmpty()) ElevationSource.OTRO else ElevationSource.NINGUNA
            }
            PointResult(p, s?.east, s?.north, s?.elevation, elevSource, planOrigin.ifEmpty { "Sin coordenadas" })
        }
    }
}

data class ProjectStates(val level: Map<Long, ResultState>, val planimetry: ResultState) {
    /** true si algún resultado guardado no corresponde a las lecturas vigentes. */
    val anyStale: Boolean get() = planimetry == ResultState.DESACTUALIZADO || level.values.any { it == ResultState.DESACTUALIZADO }
    fun warnings(runNames: Map<Long, String>): List<String> = buildList {
        for ((id, st) in level) if (st == ResultState.DESACTUALIZADO || st == ResultState.VERSION_ANTERIOR) add("Altimetría «${runNames[id] ?: id}»: ${st.labelEs}.")
        if (planimetry == ResultState.DESACTUALIZADO || planimetry == ResultState.VERSION_ANTERIOR) add("Planimetría: ${planimetry.labelEs}.")
    }
}

@Singleton
class StatusRepository @Inject constructor(
    private val leveling: LevelingRepository,
    private val radiation: RadiationRepository,
) {
    suspend fun states(projectId: Long): ProjectStates {
        val level = leveling.runs(projectId).associate { it.id to (leveling.evaluate(it.id)?.state ?: ResultState.SIN_CALCULAR) }
        return ProjectStates(level, radiation.evaluate(projectId).state)
    }
}

data class ContourAnalysis(
    val equidistance: Double,
    val candidates: List<ContourCandidate>,
    val eligibility: ContourEligibility,
    /** null si no es elegible o la equidistancia es inválida. */
    val result: ContourResult?,
    val states: ProjectStates,
)

@Singleton
class ContourRepository @Inject constructor(
    private val results: ResultsRepository,
    private val status: StatusRepository,
    private val db: TopoScribeDatabase,
) {
    suspend fun analyze(projectId: Long, equidistance: Double): ContourAnalysis {
        val res = results.getProjectResults(projectId)
        val candidates = res.map { ContourCandidate(it.point.id, it.point.code, it.east, it.north, it.elevation, it.elevationSource) }
        val elig = evaluateContourEligibility(candidates)
        val result = if (elig.eligible && equidistance > 0.0) computeContours(elig.usable, equidistance) else null
        return ContourAnalysis(equidistance, candidates, elig, result, status.states(projectId))
    }

    suspend fun saveEquidistance(projectId: Long, value: Double?) {
        val p = db.projectDao().getById(projectId) ?: return
        db.projectDao().update(p.copy(contourEquidistance = value, updatedAt = java.time.Instant.now().toString()))
    }
}

@Singleton
class PlanRepository @Inject constructor(
    private val db: TopoScribeDatabase,
    private val results: ResultsRepository,
    private val contours: ContourRepository,
    private val status: StatusRepository,
) {
    /** Modelo del plano: solo puntos con Este y Norte; las curvas solo entran si se calcularon válidamente. */
    suspend fun getPlanModel(projectId: Long, includeContours: Boolean = true): PlanModel {
        val project = db.projectDao().getById(projectId)
        val res = results.getProjectResults(projectId)
        val obs = db.planimetryObservationDao().listSnapshot(projectId).filter { it.traverseId == null }
        val setups = db.stationSetupDao().listSnapshot(projectId)
        val stationIds = (obs.map { it.occupiedPointId } + setups.map { it.occupiedPointId }).toSet()
        val withCoords = res.filter { it.hasCoordinates }
        val points = withCoords.map {
            PlanPoint(it.point.id, it.point.code, it.point.description, it.east!!, it.north!!, it.elevation, it.elevationSource, it.point.id in stationIds)
        }
        val ids = points.map { it.pointId }.toSet()
        val radiations = obs.filter { it.occupiedPointId in ids && it.targetPointId in ids }.map { PlanRadiation(it.occupiedPointId, it.targetPointId) }.distinct()
        val lines = db.alignmentDao().listSnapshot(projectId).map { PlanPolyline(it.name, it.pointSequence, it.closed) }
        var contourResult: ContourResult? = null
        if (includeContours) {
            val eq = project?.contourEquidistance
            if (eq != null && eq > 0.0) {
                val a = contours.analyze(projectId, eq)
                if (a.result?.applicable == true) contourResult = a.result
            }
        }
        return PlanModel(
            projectName = project?.name.orEmpty(), coordinateSystem = project?.let(::coordinateSystemText) ?: "Local", unitsDistance = project?.unitsDistance ?: "m",
            points = points, radiations = radiations, polylines = lines, contours = contourResult, pointsWithoutCoordinates = res.size - points.size,
        )
    }

    suspend fun states(projectId: Long): ProjectStates = status.states(projectId)
}
