package com.toposcribe.app.ui.screens.instrumento

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.data.local.entity.CoordinateMode
import com.toposcribe.data.local.entity.InstrumentProfile
import com.toposcribe.data.local.entity.InstrumentType
import com.toposcribe.data.local.entity.ProjectEntity
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InstrumentoUiState(
    val projects: List<ProjectEntity> = emptyList(),
    val currentProject: ProjectEntity? = null,
    val isLoading: Boolean = true,
)

@HiltViewModel
class InstrumentoViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val currentProjectHolder: CurrentProjectHolder,
) : ViewModel() {

    val uiState: StateFlow<InstrumentoUiState> = combine(
        projectRepository.observeAll(),
        currentProjectHolder.currentProjectId,
    ) { projects, currentId ->
        InstrumentoUiState(
            projects = projects,
            currentProject = projects.find { it.id == currentId },
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InstrumentoUiState())

    fun selectProject(id: Long) = currentProjectHolder.select(id)

    /** Proyecto de trabajo inicial sin nombre (el formulario arranca vacío, como en el diseño). */
    fun createBlank() = viewModelScope.launch {
        val id = projectRepository.createEmpty(ProjectEntity(name = "", date = java.time.LocalDate.now().toString(), createdAt = Instant.now().toString()))
        currentProjectHolder.select(id)
    }

    /** Elimina el proyecto indicado; si era el activo, la pantalla abre el más reciente o crea uno vacío. */
    fun deleteProject(project: ProjectEntity) = viewModelScope.launch {
        val wasActive = uiState.value.currentProject?.id == project.id
        projectRepository.delete(project)
        if (wasActive) currentProjectHolder.select(null)
    }

    fun updateMetadata(
        name: String,
        purpose: String,
        location: String,
        siteCharacteristics: String,
        date: String,
        responsible: String,
        fieldProcedure: String,
        observations: String,
    ) = updateCurrent { it.copy(name = name, purpose = purpose, location = location, siteCharacteristics = siteCharacteristics, date = date, responsible = responsible, fieldProcedure = fieldProcedure, observations = observations) }

    fun updateInstrument(instrument: String, instrumentType: InstrumentType, prismConstantMm: Double) =
        updateCurrent { it.copy(instrument = instrument, instrumentType = instrumentType, prismConstantM = prismConstantMm / 1000.0) }

    fun updateInstrumentProfile(profile: InstrumentProfile) = updateCurrent { it.copy(instrumentProfile = profile) }

    /** Una sola escritura para tipo + detalle + constante + perfil, para no pisar un update con otro. */
    fun updateInstrumentFull(instrument: String, instrumentType: InstrumentType, prismConstantMm: Double, profile: InstrumentProfile) =
        updateCurrent {
            it.copy(instrument = instrument, instrumentType = instrumentType, prismConstantM = prismConstantMm / 1000.0, instrumentProfile = profile)
        }

    fun updateCoordinateSystem(mode: CoordinateMode, coordinateSystem: String, utmDatum: String, utmZone: Int?, utmHemisphere: com.toposcribe.data.local.entity.Hemisphere?) =
        updateCurrent { it.copy(coordinateMode = mode, coordinateSystem = coordinateSystem, utmDatum = utmDatum, utmZone = utmZone, utmHemisphere = utmHemisphere) }

    private fun updateCurrent(transform: (ProjectEntity) -> ProjectEntity) = viewModelScope.launch {
        val project = uiState.value.currentProject ?: return@launch
        projectRepository.update(transform(project))
    }
}
