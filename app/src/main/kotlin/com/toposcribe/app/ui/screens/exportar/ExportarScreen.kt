package com.toposcribe.app.ui.screens.exportar

import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import com.toposcribe.app.R
import com.toposcribe.app.ui.components.loadPhotoThumbnail
import com.toposcribe.app.ui.theme.MonoFieldStyle
import com.toposcribe.data.local.entity.PointEntity
import java.time.LocalDate
import com.toposcribe.data.local.entity.CoordinateMode
import com.toposcribe.data.local.entity.InstrumentType
import com.toposcribe.data.local.entity.ProjectEntity
import com.toposcribe.io.buildCsv
import com.toposcribe.io.buildProjectDxf
import com.toposcribe.io.buildReportPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExportarScreen(modifier: Modifier = Modifier, viewModel: ExportarViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var statusMessage by remember { mutableStateOf<String?>(null) }

    val createCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { writeCsv(context, uri, viewModel) { statusMessage = it } }
    }
    val createDxfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/dxf")) { uri ->
        if (uri != null) scope.launch { writeDxf(context, uri, viewModel) { statusMessage = it } }
    }
    val createPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch { writePdf(context, uri, viewModel) { statusMessage = it } }
    }

    LazyColumn(modifier = modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Control de Calidad Previo a la Exportación", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    state.qcChecks.forEach { check ->
                        Row {
                            Icon(
                                if (check.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                contentDescription = null,
                                tint = if (check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(check.label, style = MaterialTheme.typography.labelMedium)
                                Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Exportar", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Button(onClick = { createCsvLauncher.launch("resultados.csv") }) { Text("Descargar Puntos (CSV)") }
                    Button(onClick = { createDxfLauncher.launch("plano.dxf") }) { Text("Descargar Plano Vectorial (DXF)") }
                    Text(
                        "CSV: código, descripción, este, norte, cota, tipo y origen (compatible con Civil 3D y QGIS). DXF: formato ASCII AutoCAD con capas de puntos y polígono.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { createPdfLauncher.launch("informe.pdf") }) { Text("Imprimir / Exportar a PDF (Expediente)") }
                    statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        item { ExpedienteCard(state) }
    }
}

/** Expediente oficial (vista previa del PDF): encabezado, Memoria Descriptiva, Memoria de Cálculo y Panel Fotográfico. */
@Composable
private fun ExpedienteCard(state: ExportarUiState) {
    val project = state.project
    var viewerPoint by remember { mutableStateOf<PointEntity?>(null) }
    viewerPoint?.let { point -> PhotoViewerDialog(point) { viewerPoint = null } }

    val allOk = state.qcChecks.isNotEmpty() && state.qcChecks.all { it.ok }
    val today = remember { LocalDate.now().toString() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Encabezado formal
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("REPÚBLICA DE PERÚ • SISTEMA NACIONAL DE TOPOGRAFÍA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Text("MEMORIA TÉCNICA DE LEVANTAMIENTO TOPOGRÁFICO", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text("Certificación de Datos Geodésicos, Libreta de Campo y Fotocontrol", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MemoryRow("Expediente", project?.let { "TOPO-${today.take(4)}-${"%02d".format(it.id)}" })
                MemoryRow("Fecha de emisión", today)
                MemoryRow("Estado", if (project == null) null else if (allOk) "AUDITADO / VÁLIDO" else "CON OBSERVACIONES (ver control de calidad)")
            }
        }

        if (project == null) {
            Card(Modifier.fillMaxWidth()) {
                Text("Cree o cargue un proyecto para ver su expediente.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        // Sección 1
        val system = if (project.coordinateMode == CoordinateMode.UTM) {
            "UTM zona ${project.utmZone ?: "--"}${project.utmHemisphere?.name ?: ""}${project.utmDatum?.let { " ($it)" } ?: ""}"
        } else {
            "Plano Topográfico Local (PTL)"
        }
        SectionCard("SECCIÓN 1", "MEMORIA DESCRIPTIVA") {
            MemoryRow("Nombre de la obra / proyecto", project.name)
            MemoryRow("Ubicación / paraje", project.location)
            MemoryRow("Finalidad del levantamiento", project.purpose)
            MemoryRow("Responsable técnico (CIP)", project.responsible)
            MemoryRow("Instrumental y modelo empleado", project.instrument ?: instrumentLabel(project.instrumentType))
            MemoryRow("Sistema de referencia y coordenadas", system)
            MemoryRow("Método de trabajo aplicado", project.fieldProcedure)
            if (project.isDemo) {
                Text("DATOS DE DEMOSTRACIÓN — el expediente no representa un levantamiento real.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        // Sección 2
        SectionCard("SECCIÓN 2", "MEMORIA DE CÁLCULO") {
            Text("FÓRMULAS TOPOGRÁFICAS APLICADAS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            Text(
                "• Proyecciones: ΔE = d · sin(Az)  |  ΔN = d · cos(Az)\n" +
                    "• Corrección Bowditch: Corr_E = −(ΣΔE / Perímetro) · d_tramo\n" +
                    "• Altimetría: AI = Cota_BM + VA; Cota_Pto = AI − VD",
                style = MonoFieldStyle,
            )
            val levelingCheck = state.qcChecks.firstOrNull { it.label.startsWith("Altimetría") }
            if (levelingCheck != null) {
                Text("COMPROBACIÓN ARITMÉTICA DE NIVELACIÓN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Text(
                    levelingCheck.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (levelingCheck.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text("TABLA DE COORDENADAS OFICIALES AJUSTADAS (BOWDITCH)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            val columns = listOf("Vértice" to 80.dp, "Este (X) [m]" to 110.dp, "Norte (Y) [m]" to 110.dp, "Cota (Z) [m]" to 100.dp, "Método" to 170.dp, "Descripción del Punto" to 180.dp)
            val scroll = rememberScrollState()
            Row(Modifier.horizontalScroll(scroll)) {
                columns.forEach { (title, w) -> Text(title, Modifier.width(w).padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (state.results.isEmpty()) Text("Sin puntos registrados.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.results.forEach { r ->
                Row(Modifier.horizontalScroll(scroll)) {
                    @Composable
                    fun cell(text: String, i: Int, mono: Boolean = true) =
                        Text(text, Modifier.width(columns[i].second).padding(4.dp), style = if (mono) MonoFieldStyle else MaterialTheme.typography.bodySmall)
                    cell(r.code, 0)
                    cell(r.east?.let { "%.3f".format(it) } ?: "--", 1)
                    cell(r.north?.let { "%.3f".format(it) } ?: "--", 2)
                    cell(r.elevation?.let { "%.3f".format(it) } ?: "--", 3)
                    cell(r.origin, 4, mono = false)
                    cell(r.description ?: "—", 5, mono = false)
                }
            }
        }

        // Sección 4 (la 3, Planos, se ve en la pestaña Plano CAD y sale en el PDF)
        SectionCard("SECCIÓN 4", "PANEL FOTOGRÁFICO DE PUNTOS Y VÉRTICES") {
            Text("${state.photoPoints.size} FICHAS REGISTRADAS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Fotocontrol de monumentación y referencias físicas de vértices ordenados secuencialmente.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.photoPoints.isEmpty()) {
                Text("Ningún punto tiene fotografía todavía. Añádela desde Control/BM o al crear un punto.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.photoPoints.forEach { point ->
                    val thumb = remember(point.sketchPhotoPath) { loadPhotoThumbnail(point.sketchPhotoPath, 400) }
                    Column(Modifier.width(150.dp).clickable { viewerPoint = point }) {
                        if (thumb != null) {
                            Image(bitmap = thumb, contentDescription = point.code, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(100.dp))
                        } else {
                            Text("[foto no disponible]", style = MaterialTheme.typography.bodySmall, modifier = Modifier.height(100.dp))
                        }
                        Text(point.code, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(point.photoCaption ?: point.description ?: "Sin descripción registrada.", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(badge: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 2.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

/** Visor de fotografía de vértice: imagen grande, código, tramo y pie de foto. */
@Composable
private fun PhotoViewerDialog(point: PointEntity, onDismiss: () -> Unit) {
    val image = remember(point.sketchPhotoPath) { loadPhotoThumbnail(point.sketchPhotoPath, 1600) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fotografía de Vértice") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (image != null) Image(bitmap = image, contentDescription = point.code, modifier = Modifier.fillMaxWidth())
                else Text("La imagen ya no está disponible en el dispositivo.", color = MaterialTheme.colorScheme.error)
                Text("Vértice ${point.code}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(point.tramoSector ?: "[Sin tramo]", style = MaterialTheme.typography.labelSmall)
                Text(point.photoCaption ?: "Sin descripción registrada.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun MemoryRow(label: String, value: String?) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.ifBlank { null } ?: "--", style = MaterialTheme.typography.bodyMedium)
    }
}


private fun instrumentLabel(type: InstrumentType): String = when (type) {
    InstrumentType.ESTACION_TOTAL -> "Estación Total"
    InstrumentType.TEODOLITO -> "Teodolito"
    InstrumentType.GNSS_RTK -> "GNSS / RTK"
    InstrumentType.NIVEL_OPTICO -> "Nivel Óptico / Digital"
}

private suspend fun writeCsv(context: android.content.Context, uri: Uri, viewModel: ExportarViewModel, onStatus: (String) -> Unit) {
    val results = viewModel.getResultsSnapshot()
    withContext(Dispatchers.IO) {
        val headers = listOf("Código", "Descripción", "Este", "Norte", "Cota", "Tipo", "Origen")
        val rows = results.map { r ->
            listOf(
                r.code,
                r.description ?: "",
                r.east?.let { "%.3f".format(it) } ?: "",
                r.north?.let { "%.3f".format(it) } ?: "",
                r.elevation?.let { "%.3f".format(it) } ?: "",
                r.role.name,
                r.origin,
            )
        }
        val csv = buildCsv(headers, rows)
        context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
    }
    onStatus("CSV exportado correctamente.")
}

private suspend fun writeDxf(context: android.content.Context, uri: Uri, viewModel: ExportarViewModel, onStatus: (String) -> Unit) {
    val planData = viewModel.getPlanData() ?: return
    withContext(Dispatchers.IO) {
        val dxf = buildProjectDxf(planData, "m")
        context.contentResolver.openOutputStream(uri)?.use { it.write(dxf.toByteArray(Charsets.UTF_8)) }
    }
    onStatus("DXF exportado correctamente.")
}

/** Generar el PDF (decodifica fotos, dibuja páginas) y escribirlo a disco son operaciones
 * potencialmente lentas -- se hacen en Dispatchers.IO, nunca en el hilo principal. */
private suspend fun writePdf(context: android.content.Context, uri: Uri, viewModel: ExportarViewModel, onStatus: (String) -> Unit) {
    val reportData = viewModel.getReportData() ?: return
    withContext(Dispatchers.IO) {
        val regular = context.resources.getFont(R.font.inter_variable)
        val mono = context.resources.getFont(R.font.jetbrains_mono_variable)
        val bold = Typeface.create(regular, Typeface.BOLD)
        val bytes = buildReportPdf(reportData, regular, bold, mono)
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }
    onStatus("PDF exportado correctamente.")
}
