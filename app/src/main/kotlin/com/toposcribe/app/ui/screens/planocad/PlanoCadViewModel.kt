package com.toposcribe.app.ui.screens.planocad

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.LineRepository
import com.toposcribe.app.data.repository.PlanRepository
import com.toposcribe.app.data.repository.PointRepository
import com.toposcribe.app.data.repository.ProjectRepository
import com.toposcribe.app.data.repository.RadiationRepository
import com.toposcribe.calc.DxfVerification
import com.toposcribe.calc.PlanLayer
import com.toposcribe.calc.PlanModel
import com.toposcribe.calc.ResultState
import com.toposcribe.calc.buildProjectDxf
import com.toposcribe.calc.verifyDxf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.withContext

data class PlanoUi(val projectId: Long? = null, val plan: PlanModel? = null, val layers: Set<PlanLayer> = emptySet(), val planState: ResultState = ResultState.SIN_CALCULAR)

/** DXF en Windows-1252 (R12): así AutoCAD muestra bien acentos y «º» de los códigos. */
private val DXF_CHARSET = charset("windows-1252")

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanoCadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: CurrentProjectHolder,
    private val plans: PlanRepository,
    private val points: PointRepository,
    private val lines: LineRepository,
    private val radiation: RadiationRepository,
    private val projects: ProjectRepository,
) : ViewModel() {

    private val disabled = MutableStateFlow<Set<PlanLayer>>(emptySet())
    private val tick = MutableStateFlow(0)

    val ui: StateFlow<PlanoUi> = holder.currentProjectId.flatMapLatest { pid ->
        if (pid == null) flowOf(PlanoUi()) else {
            combine(points.observeByProject(pid), lines.observeByProject(pid), radiation.observeObservations(pid), projects.observeById(pid), disabled) { _, _, _, _, off -> off }
                .combine(tick) { off, _ -> off }
                .map { off ->
                    val plan = plans.getPlanModel(pid)
                    PlanoUi(pid, plan, plan.availableLayers() - off, radiation.evaluate(pid).state)
                }
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlanoUi())

    fun toggle(layer: PlanLayer) { disabled.value = if (layer in disabled.value) disabled.value - layer else disabled.value + layer }
    fun refresh() { tick.value += 1 }

    /**
     * Exporta el DXF de las capas visibles, lo vuelve a leer desde el archivo escrito y verifica
     * automáticamente entidades y coordenadas contra el plano mostrado.
     */
    fun exportDxf(uri: Uri, onDone: (DxfVerification?, String) -> Unit) = viewModelScope.launch {
        val state = ui.value
        val plan = state.plan
        if (plan == null || plan.points.isEmpty()) { onDone(null, "No hay puntos con coordenadas: no hay plano que exportar."); return@launch }
        val layers = state.layers
        try {
            val verification = withContext(Dispatchers.IO) {
                val text = buildProjectDxf(plan, layers)
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(DXF_CHARSET)) } ?: error("No se pudo escribir en la ubicación elegida.")
                val readBack = context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), DXF_CHARSET) } ?: error("No se pudo releer el archivo escrito.")
                verifyDxf(readBack, plan, layers)
            }
            onDone(verification, if (verification.ok) "DXF exportado y verificado: entidades y coordenadas coinciden con el plano." else "DXF exportado, pero la verificación encontró diferencias.")
        } catch (e: Exception) {
            onDone(null, "No se pudo exportar el DXF: ${e.message}")
        }
    }
}
