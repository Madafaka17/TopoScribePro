package com.toposcribe.app.ui.screens.planimetria

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.LineRepository
import com.toposcribe.app.data.repository.Outcome
import com.toposcribe.app.data.repository.PointResult
import com.toposcribe.app.data.repository.PointRepository
import com.toposcribe.app.data.repository.ProjectRepository
import com.toposcribe.app.data.repository.RadiationEvaluation
import com.toposcribe.app.data.repository.RadiationRepository
import com.toposcribe.app.data.repository.ResultsRepository
import com.toposcribe.app.data.repository.errorOrNull
import com.toposcribe.data.local.entity.AlignmentEntity
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

data class PlanimetriaUi(
    val projectId: Long? = null,
    val units: String = "m",
    val evaluation: RadiationEvaluation? = null,
    val results: List<PointResult> = emptyList(),
    val lines: List<AlignmentEntity> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanimetriaViewModel @Inject constructor(
    private val holder: CurrentProjectHolder,
    private val radiation: RadiationRepository,
    private val results: ResultsRepository,
    private val lines: LineRepository,
    private val points: PointRepository,
    private val projects: ProjectRepository,
) : ViewModel() {

    private val tick = MutableStateFlow(0)

    val ui: StateFlow<PlanimetriaUi> = holder.currentProjectId.flatMapLatest { pid ->
        if (pid == null) flowOf(PlanimetriaUi()) else {
            combine(radiation.observeObservations(pid), points.observeByProject(pid), lines.observeByProject(pid), tick, projects.observeById(pid)) { _, _, l, _, p -> l to p }
                .map { (l, p) -> PlanimetriaUi(pid, p?.unitsDistance ?: "m", radiation.evaluate(pid), results.getProjectResults(pid), l) }
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlanimetriaUi())

    fun compute(notify: (String) -> Unit) = viewModelScope.launch {
        val pid = holder.currentProjectId.value ?: return@launch
        when (val r = radiation.compute(pid)) {
            is Outcome.Ok -> { tick.value += 1; val ok = r.value.rows.count { it.step != null }; val bad = r.value.rows.count { it.step == null && it.legacyNote == null }; notify("Radiación calculada y guardada: $ok punto(s)" + if (bad > 0) "; $bad sin calcular (vea los motivos)." else ".") }
            is Outcome.Fail -> notify(r.message)
        }
    }

    fun saveLine(id: Long?, name: String, pointIds: List<Long>, closed: Boolean, onDone: (String?) -> Unit) = viewModelScope.launch {
        val pid = holder.currentProjectId.value ?: return@launch
        onDone(lines.save(pid, id, name, pointIds, closed).errorOrNull())
    }

    fun deleteLine(line: AlignmentEntity) = viewModelScope.launch { lines.delete(line) }
}
