package com.toposcribe.app.ui.screens.curvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.ContourAnalysis
import com.toposcribe.app.data.repository.ContourRepository
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.PlanRepository
import com.toposcribe.app.data.repository.PointRepository
import com.toposcribe.app.data.repository.ProjectRepository
import com.toposcribe.calc.PlanModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

data class CurvasUi(
    val projectId: Long? = null, val units: String = "m", val savedEquidistance: Double? = null,
    val analysis: ContourAnalysis? = null, val plan: PlanModel? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CurvasViewModel @Inject constructor(
    private val holder: CurrentProjectHolder,
    private val contours: ContourRepository,
    private val plans: PlanRepository,
    private val points: PointRepository,
    private val projects: ProjectRepository,
) : ViewModel() {

    private val equidistance = MutableStateFlow<Double?>(null)
    private val tick = MutableStateFlow(0)

    val ui: StateFlow<CurvasUi> = holder.currentProjectId.flatMapLatest { pid ->
        if (pid == null) flowOf(CurvasUi()) else {
            combine(projects.observeById(pid), points.observeByProject(pid), equidistance, tick) { p, _, eq, _ -> p to eq }.map { (p, eq) ->
                val effective = eq ?: p?.contourEquidistance ?: 1.0
                val analysis = contours.analyze(pid, effective)
                val plan = plans.getPlanModel(pid, includeContours = false).copy(contours = analysis.result?.takeIf { it.applicable })
                CurvasUi(pid, p?.unitsDistance ?: "m", p?.contourEquidistance, analysis, plan)
            }
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CurvasUi())

    fun setEquidistance(value: Double?) { equidistance.value = value }

    fun save(notify: (String) -> Unit) = viewModelScope.launch {
        val pid = holder.currentProjectId.value ?: return@launch
        val a = ui.value.analysis
        if (a?.result?.applicable != true) { notify("No hay curvas válidas que guardar."); return@launch }
        contours.saveEquidistance(pid, a.equidistance)
        notify("Curvas generadas con equidistancia ${a.equidistance}: ya aparecen en el plano, el DXF y el informe.")
    }

    fun clear(notify: (String) -> Unit) = viewModelScope.launch {
        val pid = holder.currentProjectId.value ?: return@launch
        contours.saveEquidistance(pid, null)
        notify("Curvas quitadas del plano y del informe.")
    }
}
