package com.toposcribe.app.ui.screens.libreta

import com.toposcribe.app.ui.components.toDecimalOrNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.LevelingComputeException
import com.toposcribe.app.data.repository.LevelingRepository
import com.toposcribe.app.data.repository.PlanimetryRepository
import com.toposcribe.app.data.repository.PointRepository
import com.toposcribe.app.data.repository.ProjectRepository
import com.toposcribe.app.data.repository.StationSetupRepository
import com.toposcribe.calc.Dms
import com.toposcribe.calc.LevelingObservationCalcInput
import com.toposcribe.calc.ReadingType
import com.toposcribe.calc.computeDifferentialLeveling
import com.toposcribe.calc.dmsToDecimal
import com.toposcribe.data.local.entity.FixType
import com.toposcribe.data.local.entity.InstrumentType
import com.toposcribe.data.local.entity.LevelingObservationEntity
import com.toposcribe.data.local.entity.LevelingRunEntity
import com.toposcribe.data.local.entity.PlanimetryMethod
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.PointRole
import com.toposcribe.data.local.entity.DataSource
import com.toposcribe.data.local.entity.ProjectEntity
import com.toposcribe.data.local.entity.StationSetupEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Los instrumentos que maneja la libreta: estación total, teodolito, nivel de ingeniero y GPS diferencial (GNSS). */
enum class LibretaMode { ESTACION_TOTAL, TEODOLITO, NIVEL, GNSS }

/** Una lectura de nivelación con la altura de instrumento y la cota calculadas (solo para mostrar; null si faltan datos). */
data class LevelRowView(val reading: LevelingObservationEntity, val instrumentHeight: Double?, val elevation: Double?)

data class LibretaUiState(
    val project: ProjectEntity? = null,
    val mode: LibretaMode = LibretaMode.ESTACION_TOTAL,
    val points: List<PointEntity> = emptyList(),
    val observations: List<PlanimetryObservationEntity> = emptyList(),
    val latestSetup: StationSetupEntity? = null,
    val levelRun: LevelingRunEntity? = null,
    val levelReferenceCode: String? = null,
    val levelReferenceElevation: Double? = null,
    val levelRows: List<LevelRowView> = emptyList(),
    val levelWarnings: List<String> = emptyList(),
    val levelCheckOk: Boolean? = null,
    /** Puntos medidos con GPS diferencial (modo GNSS). */
    val gnssPoints: List<PointEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** Datos de un punto GNSS que no tienen columna propia: se guardan como texto "PDOP=1.8;SAT=12;ANT=1.800" en las observaciones del punto. */
data class GnssExtra(val pdop: Double?, val satellites: Int?, val antennaHeight: Double?)

fun encodeGnssExtra(extra: GnssExtra): String = listOfNotNull(
    extra.pdop?.let { "PDOP=$it" },
    extra.satellites?.let { "SAT=$it" },
    extra.antennaHeight?.let { "ANT=$it" },
).joinToString(";")

fun decodeGnssExtra(text: String?): GnssExtra {
    fun field(key: String): String? = text?.let { Regex("(?:^|;)$key=([^;]*)").find(it)?.groupValues?.get(1) }
    return GnssExtra(field("PDOP")?.toDoubleOrNull(), field("SAT")?.toIntOrNull(), field("ANT")?.toDoubleOrNull())
}

/** Alta o edición de un punto GNSS (ya validado por el diálogo). */
data class GnssPointForm(
    val code: String,
    val tramo: String,
    val description: String,
    val fix: FixType,
    val east: Double,
    val north: Double,
    val elevation: Double?,
    val pdop: Double?,
    val satellites: Int?,
    val antennaHeight: Double?,
    val photoPath: String?,
    val photoCaption: String,
)

enum class AngleKind { AZIMUT, RUMBO, LECTURA_HORIZONTAL }
enum class VerticalKind { CENITAL, VERTICAL }

/** Datos del alta de un punto en modo estación total (ya validados por el diálogo). */
data class StationPointForm(
    val code: String,
    val stationCode: String,
    val tramo: String,
    val description: String,
    val angleKind: AngleKind,
    val angleDeg: Double?,
    val bearing: String,
    val verticalKind: VerticalKind,
    val verticalDeg: Double?,
    val horizontalDistance: Boolean,
    val distance: Double,
    val instrumentHeight: Double?,
    val prismHeight: Double?,
    val photoPath: String?,
    val photoCaption: String,
)

/** Siguiente identificador libre: A, B, C… Z y después P-1, P-2… (el usuario puede cambiarlo). */
fun suggestNextPointCode(existing: Collection<String>): String {
    val used = existing.map { it.trim().uppercase() }.toSet()
    for (c in 'A'..'Z') if (c.toString() !in used) return c.toString()
    var n = 1
    while ("P-$n" in used) n++
    return "P-$n"
}

@HiltViewModel
class LibretaViewModel @Inject constructor(
    private val pointRepository: PointRepository,
    private val planimetryRepository: PlanimetryRepository,
    private val stationSetupRepository: StationSetupRepository,
    private val levelingRepository: LevelingRepository,
    private val projectRepository: ProjectRepository,
    private val currentProjectHolder: CurrentProjectHolder,
) : ViewModel() {

    private data class Base(
        val project: ProjectEntity?,
        val points: List<PointEntity>,
        val observations: List<PlanimetryObservationEntity>,
        val setups: List<StationSetupEntity>,
        val runs: List<LevelingRunEntity>,
    )

    val uiState: StateFlow<LibretaUiState> = currentProjectHolder.currentProjectId
        .flatMapLatest { projectId ->
            if (projectId == null) {
                flowOf(LibretaUiState(isLoading = false))
            } else {
                combine(
                    projectRepository.observeById(projectId),
                    pointRepository.observeByProject(projectId),
                    planimetryRepository.observeByProject(projectId),
                    stationSetupRepository.observeByProject(projectId),
                    levelingRepository.observeRunsByProject(projectId),
                ) { project, points, observations, setups, runs -> Base(project, points, observations, setups, runs) }
                    .flatMapLatest { base ->
                        val run = base.runs.firstOrNull()
                        if (run == null) flowOf(buildState(base, null, emptyList()))
                        else levelingRepository.observeObservations(run.id).map { buildState(base, run, it) }
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibretaUiState())

    /** Une datos de campo + cálculo de cotas (puro, sin escribir nada) en el estado que pinta la pantalla. */
    private fun buildState(base: Base, run: LevelingRunEntity?, readings: List<LevelingObservationEntity>): LibretaUiState {
        val referencePoint = run?.let { r -> base.points.find { it.id == r.startBmPointId } }
        val referenceElevation = referencePoint?.fixedElevation
        var rows = readings.map { LevelRowView(it, null, null) }
        var warnings = emptyList<String>()
        var checkOk: Boolean? = null
        if (referenceElevation != null && readings.isNotEmpty()) {
            val result = computeDifferentialLeveling(
                readings.map { LevelingObservationCalcInput(it.id, it.sequenceOrder, it.pointId, it.readingType, it.readingValue, it.isTurningPoint) },
                referenceElevation,
            )
            val byObservation = result.rows.associateBy { it.observationId }
            rows = readings.map { r ->
                val row = byObservation[r.id]
                LevelRowView(r, row?.heightOfInstrument, row?.elevation?.takeIf { !it.isNaN() })
            }
            warnings = result.warnings
            checkOk = result.arithmeticCheckOk
        }
        return LibretaUiState(
            project = base.project,
            mode = when (base.project?.instrumentType) {
                InstrumentType.NIVEL_OPTICO -> LibretaMode.NIVEL
                InstrumentType.TEODOLITO -> LibretaMode.TEODOLITO
                InstrumentType.GNSS_RTK -> LibretaMode.GNSS
                else -> LibretaMode.ESTACION_TOTAL
            },
            gnssPoints = base.points.filter { it.dataSource == DataSource.MEDIDO_GNSS },
            points = base.points,
            observations = base.observations,
            latestSetup = base.setups.maxByOrNull { it.createdAt },
            levelRun = run,
            levelReferenceCode = referencePoint?.code,
            levelReferenceElevation = referenceElevation,
            levelRows = rows,
            levelWarnings = warnings,
            levelCheckOk = checkOk,
            isLoading = false,
        )
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearError() { _error.value = null }
    fun clearMessage() { _message.value = null }

    private val projectId: Long? get() = currentProjectHolder.currentProjectId.value
    private fun now() = Instant.now().toString()

    // ---------------------------------------------------------------------------------------
    // Instrumento
    // ---------------------------------------------------------------------------------------

    /** Cambia el instrumento del proyecto (la libreta muestra los campos de ese equipo). */
    fun setMode(mode: LibretaMode) = viewModelScope.launch {
        val project = uiState.value.project ?: return@launch
        val type = when (mode) {
            LibretaMode.NIVEL -> InstrumentType.NIVEL_OPTICO
            LibretaMode.TEODOLITO -> InstrumentType.TEODOLITO
            LibretaMode.GNSS -> InstrumentType.GNSS_RTK
            LibretaMode.ESTACION_TOTAL -> InstrumentType.ESTACION_TOTAL
        }
        if (project.instrumentType != type) projectRepository.update(project.copy(instrumentType = type))
    }

    /**
     * "Nuevo vacío": empieza una libreta (proyecto) nueva y vacía, conservando solo el tipo de instrumento.
     * El proyecto actual no se borra: queda guardado en el teléfono.
     */
    fun newBlankProject(onDone: () -> Unit = {}) = viewModelScope.launch {
        val current = uiState.value.project
        val id = projectRepository.createEmpty(
            ProjectEntity(
                name = "",
                date = java.time.LocalDate.now().toString(),
                instrumentType = current?.instrumentType ?: InstrumentType.ESTACION_TOTAL,
                createdAt = now(),
            ),
        )
        currentProjectHolder.select(id)
        _error.value = null
        _message.value = "Libreta nueva y vacía. El proyecto anterior queda guardado en el teléfono."
        onDone()
    }

    // ---------------------------------------------------------------------------------------
    // Estación total
    // ---------------------------------------------------------------------------------------

    /** Guarda la estación ocupada solo si cambió respecto a la última (el guardado es automático al escribir). */
    fun saveStationSetup(occupiedPointId: Long, ai: Double, backsightLabel: String, deg: Int, min: Int, sec: Double) = viewModelScope.launch {
        val projectId = projectId ?: return@launch
        val latest = uiState.value.latestSetup
        val decimal = dmsToDecimal(Dms(deg, min, sec))
        if (latest != null && latest.occupiedPointId == occupiedPointId && latest.instrumentHeightAi == ai &&
            latest.backsightPointLabel == backsightLabel && latest.baseAzimuthDecimal == decimal
        ) return@launch
        stationSetupRepository.create(
            StationSetupEntity(
                projectId = projectId,
                occupiedPointId = occupiedPointId,
                instrumentHeightAi = ai,
                backsightPointLabel = backsightLabel,
                baseAzimuthDeg = deg,
                baseAzimuthMin = min,
                baseAzimuthSec = sec,
                baseAzimuthDecimal = decimal,
                createdAt = now(),
            ),
        )
    }

    /** Fila rápida: la observación entre dos puntos que ya existen. */
    fun addObservation(
        occupiedPointId: Long,
        targetPointId: Long,
        tramo: String,
        azimuthDeg: Double?,
        bearing: String,
        horizontalDistance: Double?,
        slopeDistance: Double?,
        zenithAngle: Double?,
        instrumentHeight: Double?,
        prismHeight: Double?,
        description: String,
        horizontalAngle: Double? = null,
    ) = viewModelScope.launch {
        val projectId = projectId ?: return@launch
        if (occupiedPointId == targetPointId) {
            _error.value = "La estación ocupada y el punto visado no pueden ser el mismo punto."
            return@launch
        }
        _error.value = null
        planimetryRepository.create(
            PlanimetryObservationEntity(
                projectId = projectId,
                occupiedPointId = occupiedPointId,
                targetPointId = targetPointId,
                method = if (horizontalAngle != null && azimuthDeg == null && bearing.isBlank()) PlanimetryMethod.ANGULO_DISTANCIA else PlanimetryMethod.ACIMUT_DISTANCIA,
                horizontalDistance = horizontalDistance,
                slopeDistance = slopeDistance,
                zenithAngle = zenithAngle,
                horizontalAngle = horizontalAngle,
                azimuth = azimuthDeg,
                bearing = bearing.ifBlank { null },
                instrumentHeight = instrumentHeight,
                prismHeight = prismHeight,
                tramo = tramo.ifBlank { null },
                rawNotes = description.ifBlank { null },
                createdAt = now(),
            ),
        )
    }

    /**
     * "+ Añadir punto": crea el punto visado (con descripción y foto, que alimentan el panel
     * fotográfico del informe) y su observación desde la estación ocupada. [onDone] recibe el
     * mensaje de error, o null si todo se guardó.
     */
    fun addStationPoint(form: StationPointForm, onDone: (String?) -> Unit) = viewModelScope.launch {
        val projectId = projectId
        if (projectId == null) { onDone("Cree o cargue un proyecto antes de añadir puntos."); return@launch }
        val code = form.code.trim()
        if (code.isBlank()) { onDone("El identificador del punto es obligatorio."); return@launch }
        if (findPoint(projectId, code) != null) { onDone("Ya existe un punto \"$code\" en este proyecto."); return@launch }
        if (form.stationCode.isBlank()) { onDone("La estación ocupada es obligatoria."); return@launch }
        if (form.stationCode.trim().equals(code, ignoreCase = true)) { onDone("La estación ocupada y el punto visado no pueden ser el mismo."); return@launch }

        val timestamp = now()
        val station = findOrCreatePoint(projectId, form.stationCode.trim())
        val targetId = pointRepository.create(
            PointEntity(
                projectId = projectId,
                code = code,
                description = form.description.ifBlank { null },
                tramoSector = form.tramo.ifBlank { null },
                sketchPhotoPath = form.photoPath,
                photoCaption = form.photoCaption.ifBlank { null },
                createdAt = timestamp,
            ),
        )
        planimetryRepository.create(
            PlanimetryObservationEntity(
                projectId = projectId,
                occupiedPointId = station.id,
                targetPointId = targetId,
                method = if (form.angleKind == AngleKind.LECTURA_HORIZONTAL) PlanimetryMethod.ANGULO_DISTANCIA else PlanimetryMethod.ACIMUT_DISTANCIA,
                horizontalDistance = if (form.horizontalDistance) form.distance else null,
                slopeDistance = if (form.horizontalDistance) null else form.distance,
                zenithAngle = if (form.verticalKind == VerticalKind.CENITAL) form.verticalDeg else null,
                verticalAngle = if (form.verticalKind == VerticalKind.VERTICAL) form.verticalDeg else null,
                horizontalAngle = if (form.angleKind == AngleKind.LECTURA_HORIZONTAL) form.angleDeg else null,
                azimuth = if (form.angleKind == AngleKind.AZIMUT) form.angleDeg else null,
                bearing = if (form.angleKind == AngleKind.RUMBO) form.bearing.ifBlank { null } else null,
                instrumentHeight = form.instrumentHeight,
                prismHeight = form.prismHeight,
                tramo = form.tramo.ifBlank { null },
                rawNotes = form.description.ifBlank { null },
                createdAt = timestamp,
            ),
        )
        onDone(null)
    }

    /** Edita una observación (y, si se indica, el código del punto visado). [onDone] recibe el error o null. */
    fun updateObservation(
        obs: PlanimetryObservationEntity,
        tramo: String,
        azimuth: Double?,
        bearing: String,
        horizontalAngle: Double?,
        horizontalDistance: Double?,
        slopeDistance: Double?,
        zenithAngle: Double?,
        verticalAngle: Double?,
        instrumentHeight: Double?,
        prismHeight: Double?,
        description: String,
        newTargetCode: String? = null,
        onDone: (String?) -> Unit = {},
    ) = viewModelScope.launch {
        val projectId = projectId ?: return@launch
        if (newTargetCode != null) {
            val target = pointRepository.getById(obs.targetPointId)
            val code = newTargetCode.trim()
            if (target != null && code.isNotBlank() && code != target.code) {
                if (findPoint(projectId, code) != null) { onDone("Ya existe un punto \"$code\" en este proyecto."); return@launch }
                pointRepository.update(target.copy(code = code))
            }
        }
        planimetryRepository.update(
            obs.copy(
                tramo = tramo.ifBlank { null },
                azimuth = azimuth,
                bearing = bearing.ifBlank { null },
                horizontalAngle = horizontalAngle,
                horizontalDistance = horizontalDistance,
                slopeDistance = slopeDistance,
                zenithAngle = zenithAngle,
                verticalAngle = verticalAngle,
                instrumentHeight = instrumentHeight,
                prismHeight = prismHeight,
                rawNotes = description.ifBlank { null },
            ),
        )
        onDone(null)
    }

    /** Duplica la observación hacia un punto nuevo con el siguiente identificador libre (copia descripción y tramo). */
    fun duplicateObservation(obs: PlanimetryObservationEntity) = viewModelScope.launch {
        val projectId = projectId ?: return@launch
        val points = uiState.value.points
        val source = points.find { it.id == obs.targetPointId }
        val code = suggestNextPointCode(points.map { it.code })
        val newId = pointRepository.create(
            PointEntity(projectId = projectId, code = code, description = source?.description, tramoSector = source?.tramoSector, createdAt = now()),
        )
        planimetryRepository.create(obs.copy(id = 0, targetPointId = newId, photoPath = null, source = "duplicado", createdAt = now(), updatedAt = null, deletedAt = null))
        _message.value = "Observación duplicada como punto $code."
    }

    fun deleteObservation(id: Long) = viewModelScope.launch { planimetryRepository.softDelete(id) }

    /** Foto del punto (cámara o galería): se guarda en el punto para que salga en el panel fotográfico del informe. */
    fun setPointPhoto(pointId: Long, path: String?) = viewModelScope.launch { pointRepository.setPhoto(pointId, path) }

    /** Borra todas las observaciones de la libreta del modo activo (borrado lógico). */
    fun clearFieldBook() = viewModelScope.launch {
        val state = uiState.value
        if (state.mode == LibretaMode.NIVEL) {
            state.levelRows.forEach { levelingRepository.softDeleteObservation(it.reading.id) }
            state.levelRun?.let { recomputeLevel(it.id) }
        } else if (state.mode == LibretaMode.GNSS) {
            // Un punto GNSS que otra observación usa como estación no se borra (rompería esa observación).
            var kept = 0
            state.gnssPoints.forEach { p -> if (pointRepository.countReferences(p.id) > 0) kept++ else pointRepository.softDelete(p.id) }
            if (kept > 0) { _message.value = "Libreta limpiada. Se conservaron $kept punto(s) usados en otras observaciones."; return@launch }
        } else {
            state.observations.forEach { planimetryRepository.softDelete(it.id) }
        }
        _message.value = "Libreta limpiada."
    }

    /**
     * Importa observaciones desde CSV (con o sin encabezado, separador coma o punto y coma):
     * estacion, visado, tramo, azimut, dist_horiz, dist_incl, cenital, ai, ap, descripcion.
     * Los puntos que no existan se crean como puntos no-control.
     */
    fun importCsv(text: String) = viewModelScope.launch {
        val projectId = projectId
        if (projectId == null) { _error.value = "Cree o cargue un proyecto antes de importar."; return@launch }
        val rows = parseCsv(text)
        if (rows == null) { _error.value = "El archivo CSV está vacío."; return@launch }
        val dataRows = if (rows.first().getOrNull(3)?.toDecimalOrNull() == null && rows.first().getOrNull(4)?.toDecimalOrNull() == null) rows.drop(1) else rows

        var imported = 0
        var skipped = 0
        for (cols in dataRows) {
            val from = cols.getOrNull(0).orEmpty()
            val to = cols.getOrNull(1).orEmpty()
            if (from.isBlank() || to.isBlank() || from == to) { skipped++; continue }
            val occupied = findOrCreatePoint(projectId, from)
            val target = findOrCreatePoint(projectId, to)
            planimetryRepository.create(
                PlanimetryObservationEntity(
                    projectId = projectId,
                    occupiedPointId = occupied.id,
                    targetPointId = target.id,
                    method = PlanimetryMethod.ACIMUT_DISTANCIA,
                    tramo = cols.getOrNull(2)?.ifBlank { null },
                    azimuth = cols.getOrNull(3)?.toDecimalOrNull(),
                    horizontalDistance = cols.getOrNull(4)?.toDecimalOrNull(),
                    slopeDistance = cols.getOrNull(5)?.toDecimalOrNull(),
                    zenithAngle = cols.getOrNull(6)?.toDecimalOrNull(),
                    instrumentHeight = cols.getOrNull(7)?.toDecimalOrNull(),
                    prismHeight = cols.getOrNull(8)?.toDecimalOrNull(),
                    rawNotes = cols.getOrNull(9)?.ifBlank { null },
                    source = "csv",
                    createdAt = now(),
                ),
            )
            imported++
        }
        _message.value = "CSV importado: $imported fila(s)" + if (skipped > 0) ", $skipped omitida(s) por datos incompletos." else "."
    }

    // ---------------------------------------------------------------------------------------
    // Nivel de ingeniero (reutiliza las nivelaciones del proyecto: lo registrado aquí queda
    // disponible para los cálculos y para la pestaña Altimetría)
    // ---------------------------------------------------------------------------------------

    /**
     * Fija la cota de referencia (BM). Sin nivelación activa crea una; con la misma referencia solo
     * actualiza su cota; con otra referencia abre una nivelación nueva (la anterior se conserva).
     */
    fun setLevelReference(code: String, elevation: Double, onDone: (String?) -> Unit) = viewModelScope.launch {
        val projectId = projectId
        if (projectId == null) { onDone("Cree o cargue un proyecto antes."); return@launch }
        val cleanCode = code.trim()
        if (cleanCode.isBlank()) { onDone("El código del BM es obligatorio."); return@launch }

        val existing = findPoint(projectId, cleanCode)
        val bm = if (existing == null) {
            val id = pointRepository.create(
                PointEntity(
                    projectId = projectId, code = cleanCode, isControl = true, role = PointRole.CONTROL,
                    dataSource = DataSource.CONTROL_OFICIAL, fixedElevation = elevation, createdAt = now(),
                ),
            )
            pointRepository.getById(id)!!
        } else {
            if (existing.fixedElevation != elevation || !existing.isControl) {
                pointRepository.update(existing.copy(isControl = true, fixedElevation = elevation))
            }
            existing
        }

        val run = uiState.value.levelRun
        if (run == null || run.startBmPointId != bm.id) {
            levelingRepository.createRun(
                LevelingRunEntity(projectId = projectId, name = "Libreta de nivelación — ${bm.code}", startBmPointId = bm.id, createdAt = now()),
            )
            _message.value = if (run == null) "Referencia $cleanCode = ${"%.3f".format(elevation)} m establecida." else "Nueva nivelación iniciada desde $cleanCode."
        } else {
            recomputeLevel(run.id)
            _message.value = "Cota de $cleanCode actualizada a ${"%.3f".format(elevation)} m."
        }
        onDone(null)
    }

    /** Añade una lectura (VA/VI/VD) y recalcula las cotas. [onDone] recibe el error o null. */
    fun addLevelReading(
        code: String,
        type: ReadingType,
        value: Double,
        isTurningPoint: Boolean,
        description: String,
        photoPath: String?,
        photoCaption: String,
        onDone: (String?) -> Unit,
    ) = viewModelScope.launch {
        val projectId = projectId
        if (projectId == null) { onDone("Cree o cargue un proyecto antes."); return@launch }
        val run = uiState.value.levelRun
        if (run == null || uiState.value.levelReferenceElevation == null) {
            onDone("Defina primero la cota de referencia (BM) de la nivelación.")
            return@launch
        }
        val cleanCode = code.trim()
        if (cleanCode.isBlank()) { onDone("El punto o estación es obligatorio."); return@launch }
        if (uiState.value.levelRows.isEmpty() && type != ReadingType.BS) {
            onDone("La primera lectura debe ser una Vista Atrás (VA) sobre el BM de referencia.")
            return@launch
        }

        val point = findOrCreatePoint(projectId, cleanCode)
        if ((description.isNotBlank() && point.description.isNullOrBlank()) || photoPath != null || photoCaption.isNotBlank()) {
            pointRepository.update(
                point.copy(
                    description = if (point.description.isNullOrBlank()) description.ifBlank { null } else point.description,
                    photoCaption = photoCaption.ifBlank { point.photoCaption },
                ),
            )
            if (photoPath != null) pointRepository.setPhoto(point.id, photoPath)
        }
        val order = (uiState.value.levelRows.maxOfOrNull { it.reading.sequenceOrder } ?: 0) + 1
        levelingRepository.createObservation(
            LevelingObservationEntity(
                levelingRunId = run.id, sequenceOrder = order, pointId = point.id, readingType = type, readingValue = value,
                isTurningPoint = isTurningPoint, rawNotes = description.ifBlank { null }, createdAt = now(),
            ),
        )
        recomputeLevel(run.id)
        onDone(null)
    }

    fun updateLevelReading(obs: LevelingObservationEntity, code: String, type: ReadingType, value: Double, isTurningPoint: Boolean, notes: String, onDone: (String?) -> Unit = {}) =
        viewModelScope.launch {
            val projectId = projectId ?: return@launch
            val cleanCode = code.trim()
            if (cleanCode.isBlank()) { onDone("El punto o estación es obligatorio."); return@launch }
            val pointId = findOrCreatePoint(projectId, cleanCode).id
            levelingRepository.updateObservation(
                obs.copy(pointId = pointId, readingType = type, readingValue = value, isTurningPoint = isTurningPoint, rawNotes = notes.ifBlank { null }),
            )
            recomputeLevel(obs.levelingRunId)
            onDone(null)
        }

    fun duplicateLevelReading(obs: LevelingObservationEntity) = viewModelScope.launch {
        val order = (uiState.value.levelRows.maxOfOrNull { it.reading.sequenceOrder } ?: 0) + 1
        levelingRepository.createObservation(obs.copy(id = 0, sequenceOrder = order, photoPath = null, source = "duplicado", createdAt = now(), updatedAt = null, deletedAt = null))
        recomputeLevel(obs.levelingRunId)
        _message.value = "Lectura duplicada al final de la libreta."
    }

    fun deleteLevelReading(obs: LevelingObservationEntity) = viewModelScope.launch {
        levelingRepository.softDeleteObservation(obs.id)
        recomputeLevel(obs.levelingRunId)
    }

    /** Importa lecturas de nivel desde CSV: punto, tipo (VA/VI/VD o BS/IS/FS), lectura, cambio (sí/no). */
    fun importLevelCsv(text: String) = viewModelScope.launch {
        val projectId = projectId
        val run = uiState.value.levelRun
        if (projectId == null || run == null || uiState.value.levelReferenceElevation == null) {
            _error.value = "Defina primero la cota de referencia (BM) para importar lecturas."
            return@launch
        }
        val rows = parseCsv(text)
        if (rows == null) { _error.value = "El archivo CSV está vacío."; return@launch }
        var order = uiState.value.levelRows.maxOfOrNull { it.reading.sequenceOrder } ?: 0
        var imported = 0
        var skipped = 0
        for (cols in rows) {
            val type = parseReadingType(cols.getOrNull(1).orEmpty())
            val value = cols.getOrNull(2)?.replace(',', '.')?.toDecimalOrNull()
            val code = cols.getOrNull(0).orEmpty().trim()
            if (type == null || value == null || code.isBlank()) { skipped++; continue }
            val point = findOrCreatePoint(projectId, code)
            order++
            levelingRepository.createObservation(
                LevelingObservationEntity(
                    levelingRunId = run.id, sequenceOrder = order, pointId = point.id, readingType = type, readingValue = value,
                    isTurningPoint = cols.getOrNull(3).orEmpty().trim().lowercase() in setOf("sí", "si", "s", "true", "1"),
                    source = "csv", createdAt = now(),
                ),
            )
            imported++
        }
        recomputeLevel(run.id)
        _message.value = "CSV importado: $imported lectura(s)" + if (skipped > 0) ", $skipped omitida(s) (el encabezado o filas inválidas)." else "."
    }

    // ---------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------
    // GPS diferencial (GNSS): cada punto medido trae sus coordenadas directamente
    // ---------------------------------------------------------------------------------------

    /** Guarda un punto medido con GNSS. Es un punto con coordenadas fijas de origen "medido GNSS": entra a los cálculos, el plano y los informes. */
    fun addGnssPoint(form: GnssPointForm, onDone: (String?) -> Unit) = viewModelScope.launch {
        val projectId = projectId
        if (projectId == null) { onDone("Cree o cargue un proyecto antes de añadir puntos."); return@launch }
        if (form.code.isBlank()) { onDone("El identificador del punto es obligatorio."); return@launch }
        if (findPoint(projectId, form.code) != null) { onDone("Ya existe un punto \"${form.code}\" en este proyecto."); return@launch }
        pointRepository.create(
            PointEntity(
                projectId = projectId,
                code = form.code,
                description = form.description.ifBlank { null },
                observations = encodeGnssExtra(GnssExtra(form.pdop, form.satellites, form.antennaHeight)).ifBlank { null },
                tramoSector = form.tramo.ifBlank { null },
                isControl = false,
                role = PointRole.RELLENO,
                dataSource = DataSource.MEDIDO_GNSS,
                fixType = form.fix,
                fixedEast = form.east,
                fixedNorth = form.north,
                fixedElevation = form.elevation,
                sketchPhotoPath = form.photoPath,
                photoCaption = form.photoCaption.ifBlank { null },
                createdAt = now(),
            ),
        )
        onDone(null)
    }

    /** Edita un punto GNSS ya guardado (el repositorio deja constancia de cada cambio en el log de auditoría). */
    fun updateGnssPoint(point: PointEntity, form: GnssPointForm, onDone: (String?) -> Unit = {}) = viewModelScope.launch {
        val projectId = projectId ?: return@launch
        val code = form.code.trim()
        if (code.isBlank()) { onDone("El identificador del punto es obligatorio."); return@launch }
        val other = findPoint(projectId, code)
        if (other != null && other.id != point.id) { onDone("Ya existe un punto \"$code\" en este proyecto."); return@launch }
        pointRepository.update(
            point.copy(
                code = code,
                description = form.description.ifBlank { null },
                observations = encodeGnssExtra(GnssExtra(form.pdop, form.satellites, form.antennaHeight)).ifBlank { null },
                tramoSector = form.tramo.ifBlank { null },
                fixType = form.fix,
                fixedEast = form.east,
                fixedNorth = form.north,
                fixedElevation = form.elevation,
                photoCaption = form.photoCaption.ifBlank { point.photoCaption },
            ),
        )
        onDone(null)
    }

    fun duplicateGnssPoint(point: PointEntity) = viewModelScope.launch {
        val projectId = projectId ?: return@launch
        val code = suggestNextPointCode(uiState.value.points.map { it.code })
        pointRepository.create(point.copy(id = 0, code = code, sketchPhotoPath = null, photoCaption = null, reviewed = false, reviewedAt = null, reviewedBy = null, createdAt = now(), updatedAt = null, deletedAt = null, projectId = projectId))
        _message.value = "Punto duplicado como $code."
    }

    fun deleteGnssPoint(point: PointEntity) = viewModelScope.launch {
        val refs = pointRepository.countReferences(point.id)
        if (refs > 0) {
            _error.value = "El punto ${point.code} se usa en $refs observación(es); elimínalas antes de borrarlo."
            return@launch
        }
        pointRepository.softDelete(point.id)
    }

    /** Importa puntos GNSS desde CSV: punto, este, norte, cota, solución (fixed/float/dgps/autonomo), pdop, satélites, antena, descripción. */
    fun importGnssCsv(text: String) = viewModelScope.launch {
        val projectId = projectId
        if (projectId == null) { _error.value = "Cree o cargue un proyecto antes de importar."; return@launch }
        val rows = parseCsv(text)
        if (rows == null) { _error.value = "El archivo CSV está vacío."; return@launch }
        var imported = 0
        var skipped = 0
        for (cols in rows) {
            val code = cols.getOrNull(0).orEmpty().trim()
            val east = cols.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
            val north = cols.getOrNull(2)?.replace(",", ".")?.toDoubleOrNull()
            if (code.isBlank() || east == null || north == null || findPoint(projectId, code) != null) { skipped++; continue }
            val fix = when (cols.getOrNull(4).orEmpty().trim().lowercase()) {
                "float", "rtk float" -> FixType.FLOAT
                "dgps" -> FixType.DGPS
                "autonomo", "autónomo", "single" -> FixType.AUTONOMO
                else -> FixType.FIXED
            }
            pointRepository.create(
                PointEntity(
                    projectId = projectId, code = code, description = cols.getOrNull(8)?.ifBlank { null },
                    observations = encodeGnssExtra(
                        GnssExtra(cols.getOrNull(5)?.replace(",", ".")?.toDoubleOrNull(), cols.getOrNull(6)?.toIntOrNull(), cols.getOrNull(7)?.replace(",", ".")?.toDoubleOrNull()),
                    ).ifBlank { null },
                    role = PointRole.RELLENO, dataSource = DataSource.MEDIDO_GNSS, fixType = fix,
                    fixedEast = east, fixedNorth = north, fixedElevation = cols.getOrNull(3)?.replace(",", ".")?.toDoubleOrNull(),
                    createdAt = now(),
                ),
            )
            imported++
        }
        _message.value = "CSV importado: $imported punto(s)" + if (skipped > 0) ", $skipped omitido(s) (encabezado, datos inválidos o código repetido)." else "."
    }

    /** Busca el punto por código sin distinguir mayúsculas: "bm-01" y "BM-01" son el mismo punto. */
    private suspend fun findPoint(projectId: Long, code: String): PointEntity? =
        pointRepository.getByCode(projectId, code) ?: uiState.value.points.find { it.code.equals(code, ignoreCase = true) }

    private suspend fun findOrCreatePoint(projectId: Long, code: String): PointEntity {
        findPoint(projectId, code)?.let { return it }
        val id = pointRepository.create(PointEntity(projectId = projectId, code = code, createdAt = now()))
        return pointRepository.getById(id)!!
    }

    /** Guarda las cotas calculadas para los cálculos posteriores; con datos insuficientes simplemente no calcula. */
    private suspend fun recomputeLevel(runId: Long) {
        try {
            levelingRepository.computeLevelingRun(runId)
        } catch (e: LevelingComputeException) {
            // Aún no hay datos suficientes (sin lecturas o sin cota de referencia): la tabla muestra "—".
        }
    }

    private fun parseCsv(text: String): List<List<String>>? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        val sep = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) ';' else ','
        return lines.map { line -> line.split(sep).map { it.trim().trim('"') } }
    }

    private fun parseReadingType(text: String): ReadingType? = when (text.trim().uppercase()) {
        "VA", "BS" -> ReadingType.BS
        "VI", "IS" -> ReadingType.IS
        "VD", "FS" -> ReadingType.FS
        else -> null
    }
}
