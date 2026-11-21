package com.toposcribe.app.ui.screens.proyecto

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.BackupRepository
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.Outcome
import com.toposcribe.app.data.repository.ProjectRepository
import com.toposcribe.data.local.entity.CoordinateMode
import com.toposcribe.data.local.entity.Hemisphere
import com.toposcribe.data.local.entity.InstrumentType
import com.toposcribe.data.local.entity.ProjectEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProyectoUi(val projects: List<ProjectEntity> = emptyList(), val current: ProjectEntity? = null, val loading: Boolean = true)

data class ProjectForm(
    val name: String, val purpose: String, val location: String, val date: String, val responsible: String, val terrain: String,
    val levelInstrument: String, val theodoliteInstrument: String, val unitsDistance: String, val unitsAngle: String,
    val mode: CoordinateMode, val coordinateSystem: String, val utmDatum: String, val utmZone: String, val utmHemisphere: Hemisphere?, val observations: String,
)

@HiltViewModel
class ProyectoViewModel @Inject constructor(
    private val repo: ProjectRepository,
    private val holder: CurrentProjectHolder,
    private val backup: BackupRepository,
) : ViewModel() {

    val ui: StateFlow<ProyectoUi> = combine(repo.observeAll(), holder.currentProjectId) { all, id ->
        ProyectoUi(all, all.firstOrNull { it.id == id }, false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProyectoUi())

    fun open(id: Long) = holder.select(id)

    /** Crea un proyecto NUEVO y vacío (sin mediciones, fotografías ni anexos). */
    fun create(name: String, onDone: (String?) -> Unit) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isEmpty()) { onDone("El nombre del proyecto es obligatorio."); return@launch }
        val id = repo.createEmpty(ProjectEntity(name = clean, date = LocalDate.now().toString(), instrumentType = InstrumentType.TEODOLITO, createdAt = Instant.now().toString()))
        holder.select(id)
        onDone(null)
    }

    fun save(project: ProjectEntity, f: ProjectForm, onDone: (String?) -> Unit) = viewModelScope.launch {
        if (f.name.isBlank()) { onDone("El nombre del proyecto es obligatorio."); return@launch }
        val date = f.date.trim()
        if (date.isNotEmpty() && runCatching { LocalDate.parse(date) }.isFailure) { onDone("La fecha debe tener el formato AAAA-MM-DD (por ejemplo 2026-11-20)."); return@launch }
        val zone = f.utmZone.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: run { onDone("La zona UTM debe ser un número de 1 a 60."); return@launch } }
        if (zone != null && zone !in 1..60) { onDone("La zona UTM debe estar entre 1 y 60."); return@launch }
        repo.update(
            project.copy(
                name = f.name.trim(), purpose = f.purpose.ifBlank { null }, location = f.location.ifBlank { null }, date = date.ifBlank { null }, responsible = f.responsible.ifBlank { null },
                siteCharacteristics = f.terrain.ifBlank { null }, levelInstrument = f.levelInstrument.ifBlank { null }, theodoliteInstrument = f.theodoliteInstrument.ifBlank { null },
                unitsDistance = f.unitsDistance, unitsAngle = f.unitsAngle, coordinateMode = f.mode, coordinateSystem = f.coordinateSystem.ifBlank { null },
                utmDatum = f.utmDatum.ifBlank { null }, utmZone = zone, utmHemisphere = f.utmHemisphere, observations = f.observations.ifBlank { null },
            ),
        )
        onDone(null)
    }

    fun delete(project: ProjectEntity) = viewModelScope.launch {
        val wasCurrent = holder.currentProjectId.value == project.id
        repo.delete(project)
        if (wasCurrent) holder.select(ui.value.projects.firstOrNull { it.id != project.id }?.id)
    }

    fun exportBackup(uri: Uri, onDone: (String) -> Unit) = viewModelScope.launch {
        val id = holder.currentProjectId.value ?: return@launch onDone("No hay un proyecto abierto.")
        when (val r = backup.export(id, uri)) {
            is Outcome.Ok -> onDone("Respaldo exportado: ${r.value.manifest.counts["points"]} punto(s), ${r.value.manifest.counts["annexes"]} anexo(s) y ${r.value.manifest.counts["files"]} archivo(s) incluidos." +
                if (r.value.warnings.isNotEmpty()) " Advertencias: " + r.value.warnings.joinToString(" ") else "")
            is Outcome.Fail -> onDone("No se pudo exportar: ${r.message}")
        }
    }

    fun importBackup(uri: Uri, onDone: (String) -> Unit) = viewModelScope.launch {
        when (val r = backup.import(uri)) {
            is Outcome.Ok -> { holder.select(r.value.projectId); onDone("Proyecto «${r.value.projectName}» restaurado (${r.value.counts["points"]} punto(s), ${r.value.counts["annexes"]} anexo(s), ${r.value.counts["files"]} archivo(s))." + if (r.value.warnings.isNotEmpty()) " " + r.value.warnings.joinToString(" ") else "") }
            is Outcome.Fail -> onDone("No se pudo restaurar: ${r.message}")
        }
    }
}
