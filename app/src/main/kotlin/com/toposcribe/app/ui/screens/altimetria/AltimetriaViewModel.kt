package com.toposcribe.app.ui.screens.altimetria

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.LevelingEvaluation
import com.toposcribe.app.data.repository.LevelingRepository
import com.toposcribe.app.data.repository.Outcome
import com.toposcribe.app.data.repository.PointRepository
import com.toposcribe.app.data.repository.ProjectRepository
import com.toposcribe.data.local.entity.LevelingRunEntity
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

data class AltimetriaUi(val projectId: Long? = null, val units: String = "m", val runs: List<LevelingRunEntity> = emptyList(), val eval: LevelingEvaluation? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AltimetriaViewModel @Inject constructor(
    private val holder: CurrentProjectHolder,
    private val leveling: LevelingRepository,
    private val points: PointRepository,
    private val projects: ProjectRepository,
) : ViewModel() {

    private val selected = MutableStateFlow<Long?>(null)
    private val tick = MutableStateFlow(0)

    val ui: StateFlow<AltimetriaUi> = holder.currentProjectId.flatMapLatest { pid ->
        if (pid == null) flowOf(AltimetriaUi()) else {
            combine(leveling.observeRuns(pid), points.observeByProject(pid), selected, tick, projects.observeById(pid)) { runs, _, sel, _, project -> Triple(runs, sel, project) }
                .flatMapLatest { (runs, sel, project) ->
                    val runId = runs.firstOrNull { it.id == sel }?.id ?: runs.firstOrNull()?.id
                    val obs = if (runId == null) flowOf(emptyList()) else leveling.observeObservations(runId)
                    obs.map { AltimetriaUi(pid, project?.unitsDistance ?: "m", runs, runId?.let { leveling.evaluate(it) }) }
                }
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AltimetriaUi())

    fun select(id: Long) { selected.value = id }

    fun compute(runId: Long, notify: (String) -> Unit) = viewModelScope.launch {
        when (val r = leveling.compute(runId)) {
            is Outcome.Ok -> { tick.value += 1; notify("Altimetría calculada y guardada: ${r.value.rows.count { it.calc != null }} lectura(s).") }
            is Outcome.Fail -> notify(r.message)
        }
    }
}
