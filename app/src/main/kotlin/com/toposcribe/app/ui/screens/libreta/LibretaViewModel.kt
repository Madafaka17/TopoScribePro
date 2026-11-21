package com.toposcribe.app.ui.screens.libreta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.EditHistoryRepository
import com.toposcribe.app.data.repository.ExportRepository
import com.toposcribe.app.data.repository.LevelingEvaluation
import com.toposcribe.app.data.repository.LevelingRepository
import com.toposcribe.app.data.repository.Outcome
import com.toposcribe.app.data.repository.PointRepository
import com.toposcribe.app.data.repository.PointResult
import com.toposcribe.app.data.repository.ProjectRepository
import com.toposcribe.app.data.repository.RadiationEvaluation
import com.toposcribe.app.data.repository.RadiationReadingForm
import com.toposcribe.app.data.repository.RadiationRepository
import com.toposcribe.app.data.repository.ResultsRepository
import com.toposcribe.app.data.repository.StationSetupRepository
import com.toposcribe.app.data.repository.TABLE_LEVEL_OBS
import com.toposcribe.app.data.repository.TABLE_PLAN_OBS
import com.toposcribe.app.data.repository.errorOrNull
import com.toposcribe.calc.AngleConvention
import com.toposcribe.calc.ReadingType
import com.toposcribe.calc.decimalToDms
import com.toposcribe.calc.parseAngleText
import com.toposcribe.data.local.entity.DataSource
import com.toposcribe.data.local.entity.LevelingObservationEntity
import com.toposcribe.data.local.entity.LevelingRunEntity
import com.toposcribe.data.local.entity.ObservationEditLogEntity
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.PointRole
import com.toposcribe.data.local.entity.ProjectEntity
import com.toposcribe.data.local.entity.StationSetupEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibretaMode(val label: String) { NIVEL("Nivel de ingeniero"), TEODOLITO("Teodolito") }

data class LibretaUi(
    val project: ProjectEntity? = null,
    val mode: LibretaMode = LibretaMode.NIVEL,
    val points: List<PointEntity> = emptyList(),
    val results: Map<Long, PointResult> = emptyMap(),
    val runs: List<LevelingRunEntity> = emptyList(),
    val level: LevelingEvaluation? = null,
    val setups: List<StationSetupEntity> = emptyList(),
    val radiation: RadiationEvaluation? = null,
    val loading: Boolean = true,
)

/** Datos del formulario de punto (ya como texto del usuario; el VM los valida). */
data class PointForm(
    val code: String, val description: String, val observations: String, val instrumentRef: String,
    val hasElevation: Boolean, val elevation: String,
    val hasCoords: Boolean, val east: String, val north: String, val coordsAreControl: Boolean,
)

data class SetupForm(
    val stationPointId: Long?, val instrumentHeight: String, val backsightLabel: String, val backsightPointId: Long?,
    val referenceAzimuth: String, val referenceReading: String, val convention: AngleConvention,
    /** Si la estación aún no tiene coordenadas: las declaradas aquí (locales) se guardan en el punto. */
    val declareEast: String = "", val declareNorth: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibretaViewModel @Inject constructor(
    private val holder: CurrentProjectHolder,
    private val projects: ProjectRepository,
    private val points: PointRepository,
    private val leveling: LevelingRepository,
    private val radiation: RadiationRepository,
    private val setups: StationSetupRepository,
    private val results: ResultsRepository,
    private val history: EditHistoryRepository,
    private val exports: ExportRepository,
) : ViewModel() {

    private val mode = MutableStateFlow(LibretaMode.NIVEL)
    private val selectedRun = MutableStateFlow<Long?>(null)

    private data class Base(val project: ProjectEntity?, val points: List<PointEntity>, val runs: List<LevelingRunEntity>, val obs: List<PlanimetryObservationEntity>, val setups: List<StationSetupEntity>)

    val ui: StateFlow<LibretaUi> = holder.currentProjectId.flatMapLatest { pid ->
        if (pid == null) flowOf(LibretaUi(loading = false)) else {
            val base = combine(projects.observeById(pid), points.observeByProject(pid), leveling.observeRuns(pid), radiation.observeObservations(pid), setups.observeByProject(pid)) { a, b, c, d, e -> Base(a, b, c, d, e) }
            combine(base, mode, selectedRun) { b, m, r -> Triple(b, m, r) }.flatMapLatest { (b, m, r) ->
                val runId = b.runs.firstOrNull { it.id == r }?.id ?: b.runs.firstOrNull()?.id
                val obsFlow = if (runId == null) flowOf(emptyList()) else leveling.observeObservations(runId)
                obsFlow.map { _ ->
                    LibretaUi(
                        project = b.project, mode = m, points = b.points, results = results.getProjectResults(pid).associateBy { it.point.id }, runs = b.runs,
                        level = runId?.let { leveling.evaluate(it) }, setups = b.setups, radiation = radiation.evaluate(pid), loading = false,
                    )
                }
            }
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibretaUi())

    private val projectId: Long? get() = holder.currentProjectId.value

    fun setMode(m: LibretaMode) { mode.value = m }
    fun selectRun(id: Long) { selectedRun.value = id }

    private fun launchOutcome(onDone: (String?) -> Unit, block: suspend () -> Outcome<*>) {
        viewModelScope.launch { onDone(block().errorOrNull()) }
    }

    // ---- puntos ----

    private fun parseNumber(text: String, label: String): Pair<Double?, String?> {
        val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null to "«$label» debe ser un número."
        return v to null
    }

    fun savePoint(existing: PointEntity?, f: PointForm, onDone: (String?) -> Unit) = launchOutcome(onDone) {
        val pid = projectId ?: return@launchOutcome Outcome.Fail("Cree o abra un proyecto primero.")
        var elevation: Double? = null; var east: Double? = null; var north: Double? = null
        if (f.hasElevation) { val (v, e) = parseNumber(f.elevation, "Cota"); if (e != null) return@launchOutcome Outcome.Fail(e); elevation = v }
        if (f.hasCoords) {
            val (e1, err1) = parseNumber(f.east, "Este"); if (err1 != null) return@launchOutcome Outcome.Fail(err1)
            val (n1, err2) = parseNumber(f.north, "Norte"); if (err2 != null) return@launchOutcome Outcome.Fail(err2)
            east = e1; north = n1
        }
        val isControl = f.hasElevation || (f.hasCoords && f.coordsAreControl)
        val dataSource = when { isControl -> DataSource.CONTROL_OFICIAL; f.hasCoords -> DataSource.LOCAL_DECLARADO; else -> DataSource.OTRO }
        val base = existing ?: PointEntity(projectId = pid, code = f.code, createdAt = "")
        val point = base.copy(
            code = f.code.trim(), description = f.description.ifBlank { null }, observations = f.observations.ifBlank { null }, instrumentRef = f.instrumentRef.ifBlank { null },
            isControl = isControl, role = if (isControl) PointRole.CONTROL else if (base.role == PointRole.CONTROL) PointRole.RELLENO else base.role, dataSource = dataSource,
            fixedElevation = elevation, fixedEast = east, fixedNorth = north,
        )
        if (existing == null) points.create(point) else points.update(point)
    }

    fun deletePoint(point: PointEntity, onDone: (String?) -> Unit) = launchOutcome(onDone) { points.delete(point) }
    fun setPhoto(pointId: Long, path: String) = viewModelScope.launch { points.setPhoto(pointId, path) }
    fun removePhoto(pointId: Long) = viewModelScope.launch { points.removePhoto(pointId) }
    fun saveCaption(pointId: Long, caption: String) = viewModelScope.launch { points.setCaption(pointId, caption) }

    // ---- nivelación ----

    fun createRun(name: String, startBm: Long?, endBm: Long?, endKnown: String, onDone: (String?) -> Unit) = launchOutcome(onDone) {
        val pid = projectId ?: return@launchOutcome Outcome.Fail("Cree o abra un proyecto primero.")
        if (startBm == null) return@launchOutcome Outcome.Fail("Elija el BM inicial (un punto con cota conocida).")
        val known = if (endKnown.isBlank()) null else parseNumber(endKnown, "Cota de cierre conocida").let { (v, e) -> if (e != null) return@launchOutcome Outcome.Fail(e) else v }
        when (val r = leveling.createRun(pid, name, startBm, endBm, known)) {
            is Outcome.Ok -> { selectedRun.value = r.value; r }
            is Outcome.Fail -> r
        }
    }

    fun updateRun(run: LevelingRunEntity, name: String, endBm: Long?, endKnown: String, onDone: (String?) -> Unit) = launchOutcome(onDone) {
        val known = if (endKnown.isBlank()) null else parseNumber(endKnown, "Cota de cierre conocida").let { (v, e) -> if (e != null) return@launchOutcome Outcome.Fail(e) else v }
        leveling.updateRun(run, name, endBm, known)
    }

    fun deleteRun(run: LevelingRunEntity) = viewModelScope.launch { leveling.deleteRun(run); selectedRun.value = null }

    fun addLevelReading(pointCode: String, description: String, type: ReadingType, value: String, turning: Boolean, partial: String, notes: String, onDone: (String?) -> Unit) = launchOutcome(onDone) {
        val pid = projectId ?: return@launchOutcome Outcome.Fail("Cree o abra un proyecto primero.")
        val runId = ui.value.level?.run?.id ?: return@launchOutcome Outcome.Fail("Cree primero una nivelación (con su BM inicial de cota conocida).")
        val v = parseNumber(value, "Lectura").let { (x, e) -> if (e != null) return@launchOutcome Outcome.Fail(e) else x!! }
        val p = if (partial.isBlank()) null else parseNumber(partial, "Distancia parcial").let { (x, e) -> if (e != null) return@launchOutcome Outcome.Fail(e) else x }
        val point = resolvePoint(pid, pointCode, description, "NIVEL") ?: return@launchOutcome Outcome.Fail("El código del punto es obligatorio.")
        if (point is Outcome.Fail) return@launchOutcome point
        leveling.addReading(runId, (point as Outcome.Ok).value.id, type, v, turning, p, notes)
    }

    fun updateLevelReading(obs: LevelingObservationEntity, pointCode: String, type: ReadingType, value: String, turning: Boolean, partial: String, notes: String, onDone: (String?) -> Unit) = launchOutcome(onDone) {
        val pid = projectId ?: return@launchOutcome Outcome.Fail("Cree o abra un proyecto primero.")
        val v = parseNumber(value, "Lectura").let { (x, e) -> if (e != null) return@launchOutcome Outcome.Fail(e) else x!! }
        val p = if (partial.isBlank()) null else parseNumber(partial, "Distancia parcial").let { (x, e) -> if (e != null) return@launchOutcome Outcome.Fail(e) else x }
        val point = resolvePoint(pid, pointCode, "", "NIVEL") ?: return@launchOutcome Outcome.Fail("El código del punto es obligatorio.")
        if (point is Outcome.Fail) return@launchOutcome point
        leveling.updateReading(obs, (point as Outcome.Ok).value.id, type, v, turning, p, notes)
    }

    fun deleteLevelReading(obs: LevelingObservationEntity) = viewModelScope.launch { leveling.deleteReading(obs) }

    private suspend fun resolvePoint(pid: Long, code: String, description: String, instrumentRef: String): Outcome<PointEntity>? {
        if (code.isBlank()) return null
        points.findByCode(pid, code)?.let { return Outcome.Ok(it) }
        return points.createSimple(pid, code, description, instrumentRef)
    }

    // ---- teodolito: estación + orientación ----

    fun saveSetup(existing: StationSetupEntity?, f: SetupForm, onDone: (String?) -> Unit) = launchOutcome(onDone) {
        val pid = projectId ?: return@launchOutcome Outcome.Fail("Cree o abra un proyecto primero.")
        val stationId = f.stationPointId ?: return@launchOutcome Outcome.Fail("Elija la estación ocupada.")
        val station = points.getById(stationId) ?: return@launchOutcome Outcome.Fail("La estación ocupada no existe.")
        val ai = parseNumber(f.instrumentHeight, "Altura del instrumento").let { (x, e) -> if (e != null) return@launchOutcome Outcome.Fail(e) else x!! }
        val az = parseAngleText(f.referenceAzimuth, "Azimut de la referencia").let { if (it.error != null) return@launchOutcome Outcome.Fail(it.error!!) else it.degrees!! }
        val rd = parseAngleText(f.referenceReading.ifBlank { "0" }, "Lectura sobre la referencia").let { if (it.error != null) return@launchOutcome Outcome.Fail(it.error!!) else it.degrees!! }
        if (f.backsightPointId != null && f.backsightPointId == stationId) return@launchOutcome Outcome.Fail("La referencia de orientación no puede ser la propia estación.")
        val refPoint = f.backsightPointId?.let { points.getById(it) }
        if (f.backsightPointId != null && (refPoint == null || refPoint.projectId != pid)) return@launchOutcome Outcome.Fail("El punto de referencia no existe en este proyecto.")
        val label = f.backsightLabel.ifBlank { refPoint?.code.orEmpty() }
        // Coordenadas locales declaradas de la estación (si aún no las tiene)
        if (f.declareEast.isNotBlank() || f.declareNorth.isNotBlank()) {
            val e = parseNumber(f.declareEast, "Este de la estación").let { (x, err) -> if (err != null) return@launchOutcome Outcome.Fail(err) else x!! }
            val n = parseNumber(f.declareNorth, "Norte de la estación").let { (x, err) -> if (err != null) return@launchOutcome Outcome.Fail(err) else x!! }
            val r = points.update(station.copy(fixedEast = e, fixedNorth = n, dataSource = if (station.isControl) station.dataSource else DataSource.LOCAL_DECLARADO))
            if (r is Outcome.Fail) return@launchOutcome r
        }
        val dms = decimalToDms(az)
        val entity = (existing ?: StationSetupEntity(projectId = pid, occupiedPointId = stationId, instrumentHeightAi = ai, backsightPointLabel = label, baseAzimuthDeg = 0, baseAzimuthMin = 0, baseAzimuthSec = 0.0, baseAzimuthDecimal = az, createdAt = "")).copy(
            occupiedPointId = if (existing == null) stationId else existing.occupiedPointId, instrumentHeightAi = ai, backsightPointLabel = label,
            baseAzimuthDeg = dms.deg, baseAzimuthMin = dms.min, baseAzimuthSec = dms.sec, baseAzimuthDecimal = az, backsightReadingDeg = rd,
            angleConvention = f.convention, backsightPointId = f.backsightPointId,
        )
        if (existing == null) setups.create(entity) else setups.update(entity)
    }

    // ---- teodolito: lecturas ----

    fun addRadiation(form: RadiationReadingForm, onDone: (String?) -> Unit) = launchOutcome(onDone) {
        val pid = projectId ?: return@launchOutcome Outcome.Fail("Cree o abra un proyecto primero.")
        radiation.add(pid, form)
    }

    fun updateRadiation(obs: PlanimetryObservationEntity, form: RadiationReadingForm, onDone: (String?) -> Unit) = launchOutcome(onDone) { radiation.update(obs, form) }
    fun deleteRadiation(obs: PlanimetryObservationEntity) = viewModelScope.launch { radiation.delete(obs) }

    // ---- historial y exportación ----

    suspend fun levelHistory(id: Long): List<ObservationEditLogEntity> = history.history(TABLE_LEVEL_OBS, id)
    suspend fun radiationHistory(id: Long): List<ObservationEditLogEntity> = history.history(TABLE_PLAN_OBS, id)
    suspend fun pointHistory(id: Long): List<ObservationEditLogEntity> = history.history("points", id)
    suspend fun levelingCsv(): String? = withContext(Dispatchers.IO) { projectId?.let { exports.levelingBookCsv(it) } }
    suspend fun radiationCsv(): String? = withContext(Dispatchers.IO) { projectId?.let { exports.radiationBookCsv(it) } }
}
