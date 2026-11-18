package com.toposcribe.app.ui.screens.planocad

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.AlignmentRepository
import com.toposcribe.app.data.repository.ContourRepository
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.PlanRepository
import com.toposcribe.app.data.repository.ProfileRepository
import com.toposcribe.calc.ContourResult
import com.toposcribe.calc.PlanData
import com.toposcribe.calc.ProfileSample
import com.toposcribe.data.local.entity.AlignmentEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlanoCadUiState(
    val planData: PlanData = PlanData(emptyList(), emptyList(), 0),
    val alignments: List<AlignmentEntity> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class PlanoCadViewModel @Inject constructor(
    private val planRepository: PlanRepository,
    private val contourRepository: ContourRepository,
    private val profileRepository: ProfileRepository,
    private val alignmentRepository: AlignmentRepository,
    private val currentProjectHolder: CurrentProjectHolder,
) : ViewModel() {

    private val _planData = MutableStateFlow(PlanData(emptyList(), emptyList(), 0))
    private val _isLoading = MutableStateFlow(true)
    private val _contours = MutableStateFlow<ContourResult?>(null)
    private val _profileSamples = MutableStateFlow<List<ProfileSample>>(emptyList())
    private val _profileError = MutableStateFlow<String?>(null)

    private val alignmentsFlow = currentProjectHolder.currentProjectId.flatMapLatest { projectId ->
        if (projectId == null) flowOf(emptyList()) else alignmentRepository.observeByProject(projectId)
    }

    val uiState: StateFlow<PlanoCadUiState> = kotlinx.coroutines.flow.combine(_planData, alignmentsFlow, _isLoading) { plan, aligns, loading ->
        PlanoCadUiState(plan, aligns, loading)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlanoCadUiState())

    val contours: StateFlow<ContourResult?> = _contours.asStateFlow()
    val profileSamples: StateFlow<List<ProfileSample>> = _profileSamples.asStateFlow()
    val profileError: StateFlow<String?> = _profileError.asStateFlow()

    init {
        viewModelScope.launch {
            currentProjectHolder.currentProjectId.collect { projectId -> refresh(projectId) }
        }
    }

    /** Recarga el plano con los datos actuales del proyecto activo (se llama cada vez que se abre la pestaña). */
    fun reload() = refresh(currentProjectHolder.currentProjectId.value)

    fun refresh(projectId: Long?) = viewModelScope.launch {
        if (projectId == null) {
            _planData.value = PlanData(emptyList(), emptyList(), 0)
            _isLoading.value = false
            return@launch
        }
        _isLoading.value = true
        _planData.value = planRepository.getPlanData(projectId)
        _isLoading.value = false
    }

    fun loadContours(equidistance: Double) = viewModelScope.launch {
        val projectId = currentProjectHolder.currentProjectId.value ?: return@launch
        _contours.value = contourRepository.getContours(projectId, equidistance)
    }

    fun loadProfile(alignmentId: Long, offsetTolerance: Double) = viewModelScope.launch {
        val projectId = currentProjectHolder.currentProjectId.value ?: return@launch
        _profileError.value = null
        try {
            val result = profileRepository.getProfile(projectId, alignmentId, offsetTolerance)
            if (result.applicable) {
                _profileSamples.value = result.samples
            } else {
                _profileSamples.value = emptyList()
                _profileError.value = result.reason
            }
        } catch (e: Exception) {
            _profileError.value = e.message
        }
    }

    fun createAlignment(name: String, pointIds: List<Long>) = viewModelScope.launch {
        val projectId = currentProjectHolder.currentProjectId.value ?: return@launch
        alignmentRepository.create(projectId, name, pointIds)
    }
}
