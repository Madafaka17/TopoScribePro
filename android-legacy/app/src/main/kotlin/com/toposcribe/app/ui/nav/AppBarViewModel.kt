package com.toposcribe.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.PlanimetryRepository
import com.toposcribe.app.data.repository.PointRepository
import com.toposcribe.app.data.repository.ProjectRepository
import com.toposcribe.app.ui.components.NewPointForm
import com.toposcribe.data.local.entity.CoordinateMode
import com.toposcribe.data.local.entity.DataSource
import com.toposcribe.data.local.entity.GnssSolutionStatus
import com.toposcribe.data.local.entity.InstrumentType
import com.toposcribe.data.local.entity.PlanimetryMethod
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.PointRole
import com.toposcribe.data.local.entity.ProjectEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppBarViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val currentProjectHolder: CurrentProjectHolder,
    private val pointRepository: PointRepository,
    private val planimetryRepository: PlanimetryRepository,
) : ViewModel() {

    private val _addPointError = MutableStateFlow<String?>(null)
    val addPointError: StateFlow<String?> = _addPointError.asStateFlow()

    fun clearAddPointError() {
        _addPointError.value = null
    }

    /** Códigos de los puntos del proyecto activo: el modal avisa en vivo si el código ya existe. */
    val pointCodes: StateFlow<Set<String>> = currentProjectHolder.currentProjectId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else pointRepository.observeByProject(id) }
        .map { points -> points.map { it.code }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * Crea el punto desde el modal global. En modo libreta crea además la observación desde la
     * estación base (si esa estación no existe como punto, se crea sin coordenadas conocidas).
     * [onDone] se invoca solo si todo se guardó.
     */
    fun addPoint(form: NewPointForm, onDone: () -> Unit) = viewModelScope.launch {
        val projectId = currentProjectHolder.currentProjectId.value
        if (projectId == null) {
            _addPointError.value = "Cree o cargue un proyecto antes de añadir puntos."
            return@launch
        }
        if (form.code.isBlank()) {
            _addPointError.value = "El código del punto es obligatorio."
            return@launch
        }
        if (pointRepository.getByCode(projectId, form.code) != null) {
            _addPointError.value = "Ya existe un punto con el código \"${form.code}\" en este proyecto."
            return@launch
        }
        if (!form.isControl) {
            if (form.stationCode.isBlank()) {
                _addPointError.value = "La estación base es obligatoria."
                return@launch
            }
            if (form.stationCode.equals(form.code, ignoreCase = true)) {
                _addPointError.value = "La estación base y el punto visado no pueden ser el mismo."
                return@launch
            }
            if (form.distance == null) {
                _addPointError.value = "La distancia es obligatoria."
                return@launch
            }
            if (form.useBearing && form.bearing.isBlank() || !form.useBearing && form.azimuthDeg == null) {
                _addPointError.value = "Indique la lectura horizontal (azimut o rumbo)."
                return@launch
            }
        } else if (form.east == null || form.north == null) {
            _addPointError.value = "Un punto fijo necesita coordenadas Este y Norte."
            return@launch
        }

        val now = Instant.now().toString()
        val targetId = pointRepository.create(
            PointEntity(
                projectId = projectId,
                code = form.code,
                description = form.description.ifBlank { null },
                tramoSector = form.tramo.ifBlank { null },
                isControl = form.isControl,
                role = if (form.isControl) PointRole.CONTROL else PointRole.OTRO,
                dataSource = if (form.isControl) DataSource.CONTROL_OFICIAL else DataSource.OTRO,
                fixedEast = if (form.isControl) form.east else null,
                fixedNorth = if (form.isControl) form.north else null,
                fixedElevation = if (form.isControl) form.elevation else null,
                sketchPhotoPath = form.photoPath,
                photoCaption = form.photoCaption.ifBlank { null },
                createdAt = now,
            ),
        )

        if (!form.isControl) {
            val station = pointRepository.getByCode(projectId, form.stationCode)
                ?: pointRepository.getById(pointRepository.create(PointEntity(projectId = projectId, code = form.stationCode, createdAt = now)))!!
            planimetryRepository.create(
                PlanimetryObservationEntity(
                    projectId = projectId,
                    occupiedPointId = station.id,
                    targetPointId = targetId,
                    method = PlanimetryMethod.ACIMUT_DISTANCIA,
                    horizontalDistance = if (form.horizontalDistance) form.distance else null,
                    slopeDistance = if (form.horizontalDistance) null else form.distance,
                    zenithAngle = form.zenithDeg,
                    azimuth = form.azimuthDeg,
                    bearing = form.bearing.ifBlank { null },
                    instrumentHeight = form.instrumentHeight,
                    prismHeight = form.prismHeight,
                    tramo = form.tramo.ifBlank { null },
                    rawNotes = form.description.ifBlank { null },
                    createdAt = now,
                ),
            )
        }
        _addPointError.value = null
        onDone()
    }

    val currentProject: StateFlow<ProjectEntity?> = currentProjectHolder.currentProjectId
        .flatMapLatest { id -> if (id == null) flowOf(null) else projectRepository.observeById(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun deleteCurrentDemo() = viewModelScope.launch {
        val project = currentProject.value ?: return@launch
        if (!project.isDemo) return@launch
        projectRepository.delete(project)
        currentProjectHolder.select(null)
    }
}

fun appBarTitle(project: ProjectEntity?): String = project?.name?.takeIf { it.isNotBlank() }?.uppercase() ?: "PROYECTO VACÍO | MODO MANUAL"

/** Badge por tipo de instrumento (y estado de solución en GNSS), como en el encabezado del diseño. */
fun appBarBadge(project: ProjectEntity?): String = when (project?.instrumentType) {
    InstrumentType.GNSS_RTK -> when (project.instrumentProfile.gnssSolutionStatus) {
        GnssSolutionStatus.RTK_FIXED -> "RTK FIXED (±0.01m)"
        GnssSolutionStatus.RTK_FLOAT -> "RTK FLOAT (±0.20m)"
        else -> "SIN CONEXIÓN SENSOR"
    }
    InstrumentType.NIVEL_OPTICO -> "NIVEL GEOMÉTRICO"
    InstrumentType.TEODOLITO -> "TEODOLITO"
    else -> "ÓPTICO / POLIGONAL"
}

fun appBarIcon(project: ProjectEntity?): ImageVector = when (project?.instrumentType) {
    InstrumentType.GNSS_RTK -> Icons.Filled.SatelliteAlt
    InstrumentType.NIVEL_OPTICO -> Icons.Filled.Straighten
    else -> Icons.Filled.Architecture
}

fun appBarSubtitle(project: ProjectEntity?): String = when {
    project == null -> "Sistema: Local (PTL)"
    project.coordinateMode == CoordinateMode.UTM -> "Sistema: WGS84 UTM ${project.utmZone ?: ""}${project.utmHemisphere?.name ?: ""}"
    else -> "Sistema: Local (PTL)"
}
