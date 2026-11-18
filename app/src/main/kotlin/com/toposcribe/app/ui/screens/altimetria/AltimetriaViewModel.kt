package com.toposcribe.app.ui.screens.altimetria

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.LevelingComputeException
import com.toposcribe.app.data.repository.LevelingComputeResult
import com.toposcribe.app.data.repository.LevelingRepository
import com.toposcribe.app.data.repository.LevelingTrigRepository
import com.toposcribe.app.data.repository.PlanimetryComputeResult
import com.toposcribe.app.data.repository.PointRepository
import com.toposcribe.calc.ReadingType
import com.toposcribe.data.local.entity.LevelingObservationEntity
import com.toposcribe.data.local.entity.LevelingRunEntity
import com.toposcribe.data.local.entity.LevelingTrigObservationEntity
import com.toposcribe.data.local.entity.PointEntity
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

data class AltimetriaUiState(
    val points: List<PointEntity> = emptyList(),
    val runs: List<LevelingRunEntity> = emptyList(),
    val observations: List<LevelingObservationEntity> = emptyList(),
    val trigObservations: List<LevelingTrigObservationEntity> = emptyList(),
    val isLoading: Boolean = true,
    /** Nivelación que se está viendo: es a la que van las lecturas nuevas (antes iban siempre a la primera de la lista). */
    val activeRunId: Long? = null,
)

@HiltViewModel
class AltimetriaViewModel @Inject constructor(
    private val pointRepository: PointRepository,
    private val levelingRepository: LevelingRepository,
    private val levelingTrigRepository: LevelingTrigRepository,
    private val currentProjectHolder: CurrentProjectHolder,
) : ViewModel() {

    private val _activeRunId = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<AltimetriaUiState> = currentProjectHolder.currentProjectId
        .flatMapLatest { projectId ->
            if (projectId == null) {
                flowOf(AltimetriaUiState(isLoading = false))
            } else {
                combine(
                    pointRepository.observeByProject(projectId),
                    levelingRepository.observeRunsByProject(projectId),
                    levelingTrigRepository.observeByProject(projectId),
                    _activeRunId,
                ) { points, runs, trigObs, activeRunId ->
                    // Si la nivelación elegida ya no pertenece a este proyecto (se cambió de proyecto) se usa la más reciente.
                    Triple(points, runs, trigObs) to (activeRunId?.takeIf { id -> runs.any { it.id == id } } ?: runs.firstOrNull()?.id)
                }.flatMapLatest { (triple, runId) ->
                    val (points, runs, trigObs) = triple
                    if (runId == null) {
                        flowOf(AltimetriaUiState(points, runs, emptyList(), trigObs, false, null))
                    } else {
                        levelingRepository.observeObservations(runId).map { obs ->
                            AltimetriaUiState(points, runs, obs, trigObs, false, runId)
                        }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AltimetriaUiState())

    private val _levelingResult = MutableStateFlow<LevelingComputeResult?>(null)
    val levelingResult: StateFlow<LevelingComputeResult?> = _levelingResult.asStateFlow()

    private val _trigResult = MutableStateFlow<PlanimetryComputeResult?>(null)
    val trigResult: StateFlow<PlanimetryComputeResult?> = _trigResult.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun selectRun(runId: Long) {
        _activeRunId.value = runId
        _levelingResult.value = null // el resultado mostrado era de otra nivelación
    }

    fun createRun(name: String, startBmPointId: Long, endBmPointId: Long?, endKnownElevation: Double?) = viewModelScope.launch {
        val projectId = currentProjectHolder.currentProjectId.value ?: return@launch
        val id = levelingRepository.createRun(
            LevelingRunEntity(projectId = projectId, name = name.ifBlank { "Nivelación nueva" }, startBmPointId = startBmPointId, endBmPointId = endBmPointId, endKnownElevation = endKnownElevation, createdAt = Instant.now().toString()),
        )
        _activeRunId.value = id
    }

    /** Añade una lectura al final de la nivelación activa (el orden se calcula aquí: máximo + 1, no "cantidad + 1", que se repetía tras borrar filas). */
    fun addObservation(pointId: Long, readingType: ReadingType, readingValue: Double, isTurningPoint: Boolean) = viewModelScope.launch {
        val runId = uiState.value.activeRunId
        if (runId == null) {
            _error.value = "Crea o elige una nivelación antes de agregar lecturas."
            return@launch
        }
        _error.value = null
        val order = (uiState.value.observations.maxOfOrNull { it.sequenceOrder } ?: 0) + 1
        levelingRepository.createObservation(
            LevelingObservationEntity(levelingRunId = runId, sequenceOrder = order, pointId = pointId, readingType = readingType, readingValue = readingValue, isTurningPoint = isTurningPoint, createdAt = Instant.now().toString()),
        )
        recomputeQuietly(runId)
    }

    /** Edita una lectura ya guardada: tipo (BS/IS/FS), valor y cambio de estación. */
    fun updateObservation(obs: LevelingObservationEntity, type: ReadingType, value: Double, isTurningPoint: Boolean) = viewModelScope.launch {
        levelingRepository.updateObservation(obs.copy(readingType = type, readingValue = value, isTurningPoint = isTurningPoint))
        recomputeQuietly(obs.levelingRunId)
    }

    fun deleteObservation(id: Long) = viewModelScope.launch {
        val runId = uiState.value.activeRunId
        levelingRepository.softDeleteObservation(id)
        if (runId != null) recomputeQuietly(runId)
    }

    /** Tras cambiar lecturas, vuelve a calcular para que cotas y comprobación no muestren datos viejos. Sin datos suficientes no hace nada. */
    private suspend fun recomputeQuietly(runId: Long) {
        try {
            _levelingResult.value = levelingRepository.computeLevelingRun(runId)
        } catch (e: LevelingComputeException) {
            _levelingResult.value = null
        }
    }

    fun computeRun(runId: Long) = viewModelScope.launch {
        _error.value = null
        try {
            _levelingResult.value = levelingRepository.computeLevelingRun(runId)
        } catch (e: LevelingComputeException) {
            _error.value = e.message
        }
    }

    fun addTrigObservation(occupiedPointId: Long, targetPointId: Long, slopeDistance: Double?, horizontalDistance: Double?, zenithAngle: Double?, verticalAngle: Double?, instrumentHeight: Double, targetHeight: Double) = viewModelScope.launch {
        val projectId = currentProjectHolder.currentProjectId.value ?: return@launch
        levelingTrigRepository.create(
            LevelingTrigObservationEntity(projectId = projectId, occupiedPointId = occupiedPointId, targetPointId = targetPointId, slopeDistance = slopeDistance, horizontalDistance = horizontalDistance, zenithAngle = zenithAngle, verticalAngle = verticalAngle, instrumentHeight = instrumentHeight, targetHeight = targetHeight, createdAt = Instant.now().toString()),
        )
    }

    fun computeTrig() = viewModelScope.launch {
        val projectId = currentProjectHolder.currentProjectId.value ?: return@launch
        _trigResult.value = levelingTrigRepository.computeLevelingTrig(projectId)
    }
}
