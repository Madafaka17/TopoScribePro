package com.toposcribe.app.ui.screens.controlbm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.PointRepository
import com.toposcribe.data.local.entity.DataSource
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.PointRole
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

data class ControlBmUiState(val points: List<PointEntity> = emptyList(), val isLoading: Boolean = true)

@HiltViewModel
class ControlBmViewModel @Inject constructor(
    private val pointRepository: PointRepository,
    private val currentProjectHolder: CurrentProjectHolder,
    private val projectRepository: com.toposcribe.app.data.repository.ProjectRepository,
) : ViewModel() {

    val uiState: StateFlow<ControlBmUiState> = currentProjectHolder.currentProjectId
        .flatMapLatest { projectId ->
            if (projectId == null) {
                flowOf(ControlBmUiState(isLoading = false))
            } else {
                pointRepository.observeControlPoints(projectId).map { ControlBmUiState(points = it, isLoading = false) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ControlBmUiState())

    /** Sistema de referencia del proyecto activo (columna "Sistema" de la tabla de control). */
    val systemLabel: StateFlow<String> = currentProjectHolder.currentProjectId
        .flatMapLatest { id -> if (id == null) flowOf(null) else projectRepository.observeById(id) }
        .map { p -> if (p?.coordinateMode == com.toposcribe.data.local.entity.CoordinateMode.UTM) "UTM" else "Local PTL" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Local PTL")

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    fun addControlPoint(code: String, tramo: String, east: Double?, north: Double?, elevation: Double?, description: String, onAdded: () -> Unit = {}) = viewModelScope.launch {
        val projectId = currentProjectHolder.currentProjectId.value ?: return@launch
        if (code.isBlank()) {
            _error.value = "El código del punto es obligatorio."
            return@launch
        }
        if (pointRepository.getByCode(projectId, code) != null) {
            _error.value = "Ya existe un punto con el código \"$code\" en este proyecto."
            return@launch
        }
        pointRepository.create(
            PointEntity(
                projectId = projectId,
                code = code,
                description = description.ifBlank { null },
                tramoSector = tramo.ifBlank { null },
                isControl = true,
                role = PointRole.CONTROL,
                dataSource = DataSource.CONTROL_OFICIAL,
                fixedEast = east,
                fixedNorth = north,
                fixedElevation = elevation,
                createdAt = Instant.now().toString(),
            ),
        )
        _error.value = null
        onAdded()
    }

    /** Edita un punto de control ya guardado (el repositorio deja constancia de cada cambio en el log de auditoría). */
    fun updatePoint(point: PointEntity, code: String, tramo: String, east: Double?, north: Double?, elevation: Double?, description: String) = viewModelScope.launch {
        val projectId = currentProjectHolder.currentProjectId.value ?: return@launch
        val newCode = code.trim()
        if (newCode.isBlank()) {
            _error.value = "El código del punto es obligatorio."
            return@launch
        }
        if (!newCode.equals(point.code, ignoreCase = false) && pointRepository.getByCode(projectId, newCode) != null) {
            _error.value = "Ya existe un punto con el código \"$newCode\" en este proyecto."
            return@launch
        }
        _error.value = null
        pointRepository.update(
            point.copy(code = newCode, tramoSector = tramo.ifBlank { null }, fixedEast = east, fixedNorth = north, fixedElevation = elevation, description = description.ifBlank { null }),
        )
    }

    fun setPhoto(pointId: Long, path: String?) = viewModelScope.launch { pointRepository.setPhoto(pointId, path) }

    fun deletePoint(point: PointEntity) = viewModelScope.launch {
        pointRepository.softDelete(point.id)
    }
}
