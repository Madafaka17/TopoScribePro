package com.toposcribe.app.ui.screens.planimetria

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.PlanimetryComputeResult
import com.toposcribe.app.data.repository.PlanimetryRepository
import com.toposcribe.app.data.repository.PointRepository
import com.toposcribe.app.data.repository.TraverseComputeException
import com.toposcribe.app.data.repository.TraverseComputeResult
import com.toposcribe.app.data.repository.TraverseRepository
import com.toposcribe.calc.TraverseType
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.TraverseEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlanimetriaUiState(
    val points: List<PointEntity> = emptyList(),
    val traverses: List<TraverseEntity> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class PlanimetriaViewModel @Inject constructor(
    private val pointRepository: PointRepository,
    private val traverseRepository: TraverseRepository,
    private val planimetryRepository: PlanimetryRepository,
    private val currentProjectHolder: CurrentProjectHolder,
) : ViewModel() {

    val uiState: StateFlow<PlanimetriaUiState> = currentProjectHolder.currentProjectId
        .flatMapLatest { projectId ->
            if (projectId == null) {
                flowOf(PlanimetriaUiState(isLoading = false))
            } else {
                kotlinx.coroutines.flow.combine(
                    pointRepository.observeByProject(projectId),
                    traverseRepository.observeByProject(projectId),
                ) { points, traverses -> PlanimetriaUiState(points = points, traverses = traverses, isLoading = false) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlanimetriaUiState())

    private val _radiacionResult = MutableStateFlow<PlanimetryComputeResult?>(null)
    val radiacionResult: StateFlow<PlanimetryComputeResult?> = _radiacionResult.asStateFlow()

    private val _traverseResult = MutableStateFlow<TraverseComputeResult?>(null)
    val traverseResult: StateFlow<TraverseComputeResult?> = _traverseResult.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()

    fun createTraverse(name: String, type: TraverseType, startPointId: Long?, endPointId: Long?, startAzimuth: Double?) = viewModelScope.launch {
        val projectId = currentProjectHolder.currentProjectId.value ?: return@launch
        traverseRepository.create(
            TraverseEntity(
                projectId = projectId,
                name = name.ifBlank { "Poligonal nueva" },
                type = type,
                startPointId = startPointId,
                endPointId = endPointId,
                startAzimuth = startAzimuth,
                createdAt = Instant.now().toString(),
            ),
        )
    }

    fun computeRadiacion() = viewModelScope.launch {
        val projectId = currentProjectHolder.currentProjectId.value ?: return@launch
        _error.value = null
        _radiacionResult.value = planimetryRepository.computePlanimetry(projectId)
    }

    fun computeTraverse(traverseId: Long) = viewModelScope.launch {
        _error.value = null
        _info.value = null
        val projectId = currentProjectHolder.currentProjectId.value
        if (projectId != null) {
            val adopted = planimetryRepository.adoptFreeObservations(projectId, traverseId)
            if (adopted > 0) _info.value = "Se tomaron $adopted observaciones de la libreta (con ángulo interior) como tramos de esta poligonal."
        }
        try {
            _traverseResult.value = traverseRepository.computeTraverse(traverseId)
        } catch (e: TraverseComputeException) {
            _error.value = e.message
        }
    }
}
