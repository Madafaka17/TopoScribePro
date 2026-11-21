package com.toposcribe.app.ui.screens.informe

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.R
import com.toposcribe.app.data.repository.AnnexRepository
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.ExportRepository
import com.toposcribe.app.data.repository.NaturalCodeComparator
import com.toposcribe.app.data.repository.Outcome
import com.toposcribe.app.data.repository.PointRepository
import com.toposcribe.app.data.repository.ProjectRepository
import com.toposcribe.app.data.repository.ReportRepository
import com.toposcribe.app.data.repository.errorOrNull
import com.toposcribe.data.local.entity.AnnexEntity
import com.toposcribe.data.local.entity.AnnexKind
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.io.AnnexEmbedStatus
import com.toposcribe.io.buildReportPdf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InformeUi(
    val projectId: Long? = null,
    val pending: List<String>? = null,
    val generating: Boolean = false,
    val pdfFile: File? = null,
    val pageCount: Int = 0,
    val annexStatuses: List<AnnexEmbedStatus> = emptyList(),
    val generatedAt: String? = null,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InformeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: CurrentProjectHolder,
    private val reports: ReportRepository,
    private val exports: ExportRepository,
    private val points: PointRepository,
    private val annexes: AnnexRepository,
    private val projects: ProjectRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(InformeUi())
    val ui: StateFlow<InformeUi> = _ui.asStateFlow()

    /** Puntos en orden de código para el panel fotográfico. */
    val panelPoints: StateFlow<List<PointEntity>> = holder.currentProjectId.flatMapLatest { pid ->
        if (pid == null) flowOf(emptyList()) else points.observeByProject(pid).map { l -> l.sortedWith(compareBy(NaturalCodeComparator) { it.code }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val annexList: StateFlow<List<AnnexEntity>> = holder.currentProjectId.flatMapLatest { pid ->
        if (pid == null) flowOf(emptyList()) else annexes.observe(pid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pointsForAnnex: StateFlow<List<PointEntity>> = panelPoints

    private fun reportFile(): File = File(context.cacheDir, "reports").apply { mkdirs() }.let { File(it, "informe.pdf") }

    /** Revisa qué datos faltan sin generar el PDF. */
    fun refreshPending() = viewModelScope.launch {
        val pid = holder.currentProjectId.value ?: return@launch
        val data = withContext(Dispatchers.IO) { reports.build(pid) }
        _ui.value = _ui.value.copy(projectId = pid, pending = data.pendingItems)
    }

    fun generate() = viewModelScope.launch {
        val pid = holder.currentProjectId.value ?: return@launch
        _ui.value = _ui.value.copy(projectId = pid, generating = true, error = null)
        try {
            val result = withContext(Dispatchers.Default) {
                val data = reports.build(pid)
                val regular = context.resources.getFont(R.font.inter_variable)
                val mono = context.resources.getFont(R.font.jetbrains_mono_variable)
                val bold = Typeface.create(regular, Typeface.BOLD)
                val pdf = buildReportPdf(data, regular, bold, mono)
                val file = reportFile()
                file.writeBytes(pdf.bytes)
                Triple(pdf, data, file)
            }
            _ui.value = InformeUi(pid, result.second.pendingItems, false, result.third, result.first.pageCount, result.first.annexStatuses, result.second.generatedAt, null)
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(generating = false, error = "No se pudo generar el informe: ${e.message ?: e::class.simpleName}")
        } catch (e: OutOfMemoryError) {
            _ui.value = _ui.value.copy(generating = false, error = "No hay memoria suficiente para generar el informe (fotografías o anexos muy grandes).")
        }
    }

    fun saveTo(uri: Uri, onDone: (String) -> Unit) = viewModelScope.launch {
        val f = _ui.value.pdfFile
        if (f == null || !f.isFile) { onDone("Genere primero el informe."); return@launch }
        val ok = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } != null }.getOrDefault(false)
        }
        onDone(if (ok) "Informe PDF guardado (${_ui.value.pageCount} páginas)." else "No se pudo guardar el PDF en la ubicación elegida.")
    }

    fun shareUri(): Uri? = _ui.value.pdfFile?.takeIf { it.isFile }?.let { androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }

    suspend fun resultsCsv(): String? = withContext(Dispatchers.IO) { holder.currentProjectId.value?.let { exports.resultsCsv(it) } }

    // ---- panel fotográfico ----
    fun setPhoto(id: Long, path: String) = viewModelScope.launch { points.setPhoto(id, path) }
    fun removePhoto(id: Long) = viewModelScope.launch { points.removePhoto(id) }
    fun saveCaption(id: Long, caption: String) = viewModelScope.launch { points.setCaption(id, caption) }

    // ---- anexos ----
    fun addAnnex(uri: Uri, title: String, description: String, kind: AnnexKind, pointId: Long?, section: String, onDone: (String?) -> Unit) = viewModelScope.launch {
        val pid = holder.currentProjectId.value ?: return@launch onDone("Abra un proyecto primero.")
        onDone(annexes.add(pid, uri, title, description, kind, pointId, section).errorOrNull())
    }

    fun replaceAnnex(id: Long, uri: Uri, onDone: (String?) -> Unit) = viewModelScope.launch { onDone(annexes.replaceFile(id, uri).errorOrNull()) }
    fun editAnnex(id: Long, title: String, description: String, kind: AnnexKind, pointId: Long?, section: String, onDone: (String?) -> Unit) = viewModelScope.launch { onDone(annexes.updateInfo(id, title, description, kind, pointId, section).errorOrNull()) }
    fun moveAnnex(id: Long, up: Boolean) = viewModelScope.launch { annexes.move(id, up) }
    fun deleteAnnex(id: Long) = viewModelScope.launch { annexes.delete(id) }
    fun downloadAnnex(id: Long, uri: Uri, onDone: (String) -> Unit) = viewModelScope.launch { onDone(when (val r = annexes.exportTo(id, uri)) { is Outcome.Ok -> "Anexo guardado."; is Outcome.Fail -> r.message }) }
    fun annexFile(a: AnnexEntity): File = annexes.file(a)
    fun annexUri(a: AnnexEntity): Uri = annexes.shareUri(a)
}
