package com.toposcribe.app.ui.screens.exportar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.LevelingRepository
import com.toposcribe.app.data.repository.PlanRepository
import com.toposcribe.app.data.repository.ProjectRepository
import com.toposcribe.app.data.repository.ReportRepository
import com.toposcribe.app.data.repository.ResultRow
import com.toposcribe.app.data.repository.ResultsRepository
import com.toposcribe.app.data.repository.TraverseRepository
import com.toposcribe.calc.PlanData
import com.toposcribe.calc.ReportData
import com.toposcribe.data.local.entity.ProjectEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class QcCheck(val label: String, val ok: Boolean, val detail: String)

data class ExportarUiState(
    val results: List<ResultRow> = emptyList(),
    val qcChecks: List<QcCheck> = emptyList(),
    val project: ProjectEntity? = null,
    val photoPoints: List<com.toposcribe.data.local.entity.PointEntity> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class ExportarViewModel @Inject constructor(
    private val resultsRepository: ResultsRepository,
    private val planRepository: PlanRepository,
    private val reportRepository: ReportRepository,
    private val projectRepository: ProjectRepository,
    private val traverseRepository: TraverseRepository,
    private val levelingRepository: LevelingRepository,
    private val pointRepository: com.toposcribe.app.data.repository.PointRepository,
    private val currentProjectHolder: CurrentProjectHolder,
) : ViewModel() {

    val uiState: StateFlow<ExportarUiState> = currentProjectHolder.currentProjectId
        .flatMapLatest { projectId ->
            if (projectId == null) {
                flowOf(ExportarUiState(isLoading = false))
            } else {
                kotlinx.coroutines.flow.flow {
                    val results = resultsRepository.getProjectResults(projectId)
                    val project = projectRepository.getById(projectId)
                    val duplicateCodes = results.groupingBy { it.code }.eachCount().filter { it.value > 1 }
                    val withResult = results.count { it.hasResult }

                    // Cierre lineal real: último ajuste de cada poligonal contra la tolerancia 1/N del proyecto.
                    val tolerance = project?.instrumentProfile?.linearToleranceDenominator ?: 5000.0
                    val traverses = traverseRepository.observeByProject(projectId).first()
                    val closures = traverses.mapNotNull { t -> traverseRepository.listAdjustments(t.id).firstOrNull()?.let { t to it } }
                    val failing = closures.filter { (_, adj) -> adj.fL > 0.0 && adj.perimeter / adj.fL < tolerance }
                    val closureCheck = when {
                        traverses.isEmpty() -> QcCheck("Cierre angular y lineal", false, "No hay poligonales creadas.")
                        closures.isEmpty() -> QcCheck("Cierre angular y lineal", false, "Ninguna poligonal tiene cierre calculado todavía.")
                        failing.isEmpty() -> QcCheck("Cierre angular y lineal", true, "Tolerancia 1/${tolerance.toLong()} cumplida en ${closures.size} poligonal(es).")
                        else -> QcCheck("Cierre angular y lineal", false, "Fuera de tolerancia 1/${tolerance.toLong()}: ${failing.joinToString { it.first.name }}")
                    }

                    val runs = levelingRepository.observeRunsByProject(projectId).first()
                    val runChecks = runs.map { it to levelingRepository.checkRunArithmetic(it) }
                    val badRuns = runChecks.filter { it.second == false }
                    val levelingCheck = when {
                        runs.isEmpty() -> QcCheck("Altimetría comprobada", false, "No hay nivelaciones creadas.")
                        badRuns.isNotEmpty() -> QcCheck("Altimetría comprobada", false, "ΣVA − ΣVD ≠ ΔCota en: ${badRuns.joinToString { it.first.name }}")
                        runChecks.all { it.second == null } -> QcCheck("Altimetría comprobada", false, "Las nivelaciones no tienen cota de partida u observaciones.")
                        else -> QcCheck("Altimetría comprobada", true, "ΣVA − ΣVD = ΔCota en ${runChecks.count { it.second == true }} nivelación(es).")
                    }

                    emit(
                        ExportarUiState(
                            results = results,
                            qcChecks = listOf(
                                QcCheck("Puntos duplicados", duplicateCodes.isEmpty(), if (duplicateCodes.isEmpty()) "Identificadores validados." else "Códigos repetidos: ${duplicateCodes.keys.joinToString()}"),
                                closureCheck,
                                levelingCheck,
                                QcCheck("Puntos con resultado calculado", withResult > 0, "$withResult de ${results.size} puntos tienen coordenadas o cota calculadas."),
                            ),
                            project = project,
                            photoPoints = pointRepository.observeByProject(projectId).first().filter { it.sketchPhotoPath != null },
                            isLoading = false,
                        ),
                    )
                }
            }
        }
        // Sin tiempo de espera ni caché: al volver a la pestaña se recalcula siempre (con 5 s de margen se mostraban datos viejos).
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), ExportarUiState())

    suspend fun getPlanData(): PlanData? {
        val projectId = currentProjectHolder.currentProjectId.value ?: return null
        return planRepository.getPlanData(projectId)
    }

    suspend fun getReportData(): ReportData? {
        val projectId = currentProjectHolder.currentProjectId.value ?: return null
        return reportRepository.buildReportData(projectId)
    }

    fun getResultsSnapshot(): List<ResultRow> = uiState.value.results
}
