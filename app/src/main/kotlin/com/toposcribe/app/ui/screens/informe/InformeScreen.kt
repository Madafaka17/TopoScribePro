package com.toposcribe.app.ui.screens.informe

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toposcribe.app.ui.components.ConfirmDialog
import com.toposcribe.app.ui.components.Dropdown
import com.toposcribe.app.ui.components.FormDialog
import com.toposcribe.app.ui.components.LocalNotify
import com.toposcribe.app.ui.components.NoProjectMessage
import com.toposcribe.app.ui.components.PointPhotoSection
import com.toposcribe.app.ui.components.SectionCard
import com.toposcribe.app.ui.components.TextFieldRow
import com.toposcribe.calc.AnnexFileKind
import com.toposcribe.calc.AnnexRules
import com.toposcribe.data.local.entity.AnnexEntity
import com.toposcribe.data.local.entity.AnnexKind
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.io.PdfFiles
import com.toposcribe.io.decodeOriented
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SECTIONS = listOf("Memoria descriptiva", "Libreta de campo", "Altimetría", "Planimetría", "Plano y curvas de nivel", "Panel fotográfico", "Observaciones")

@Composable
fun InformeScreen(modifier: Modifier = Modifier, vm: InformeViewModel = hiltViewModel(), hasProject: Boolean) {
    if (!hasProject) { NoProjectMessage(modifier); return }
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier) {
        TabRow(selectedTabIndex = tab) {
            listOf("Informe", "Panel fotográfico", "Anexos").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }, modifier = Modifier.testTag("subtab_$t")) }
        }
        when (tab) {
            0 -> InformeTab(vm)
            1 -> PanelTab(vm)
            else -> AnexosTab(vm)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Informe: lista de pendientes, generar PDF, vista previa y exportación
// -------------------------------------------------------------------------------------------------

@Composable
private fun InformeTab(vm: InformeViewModel) {
    val ui by vm.ui.collectAsState()
    val notify = LocalNotify.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.refreshPending() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> if (uri != null) vm.saveTo(uri, notify) }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { val text = vm.resultsCsv(); withContext(Dispatchers.IO) { if (text != null) context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }; notify("Resultados exportados (CSV).") }
    }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
        item {
            SectionCard("Informe de levantamiento topográfico (PDF)") {
                Text("Orden: portada · memoria descriptiva · libretas originales · cálculo de altimetría · cálculo de planimetría · tabla final y perfil · plano y curvas · panel fotográfico · anexos · observaciones y limitaciones.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.generate() }, enabled = !ui.generating, modifier = Modifier.testTag("btn_generar_informe")) { Text(if (ui.generating) "Generando…" else "Generar vista previa") }
                    OutlinedButton(onClick = { save.launch("informe_topografico.pdf") }, enabled = ui.pdfFile != null, modifier = Modifier.testTag("btn_exportar_pdf")) { Text("Exportar PDF") }
                }
                OutlinedButton(onClick = {
                    vm.shareUri()?.let { u ->
                        val i = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        try { context.startActivity(Intent.createChooser(i, "Compartir informe")) } catch (e: ActivityNotFoundException) { notify("No hay aplicaciones para compartir.") }
                    }
                }, enabled = ui.pdfFile != null) { Text("Compartir PDF") }
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (ui.pdfFile != null) Text("Informe generado ${ui.generatedAt}: ${ui.pageCount} página(s).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        item {
            SectionCard("Exportar datos (CSV / Excel)") {
                Text("La libreta original (datos ingresados) se exporta desde «Libreta de campo». Aquí se exportan los resultados (valores calculados), en archivo aparte.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { csv.launch("resultados.csv") }) { Text("Resultados calculados (CSV)") }
            }
        }
        item {
            SectionCard("Datos pendientes y limitaciones (${ui.pending?.size ?: "…"})") {
                val p = ui.pending
                if (p == null) Text("Revisando…", style = MaterialTheme.typography.bodySmall)
                else if (p.isEmpty()) Text("No se detectaron datos pendientes.", style = MaterialTheme.typography.bodySmall)
                else p.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        val f = ui.pdfFile
        if (f != null) {
            item {
                SectionCard("Anexos en el PDF") {
                    if (ui.annexStatuses.isEmpty()) Text("Sin anexos.", style = MaterialTheme.typography.bodySmall)
                    ui.annexStatuses.forEach { s -> Text("Anexo ${s.annex.number} — ${s.annex.title}: ${s.note}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { Text("Vista previa", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items((0 until ui.pageCount).toList(), key = { "${f.lastModified()}-$it" }) { index -> PdfPagePreview(f, index, ui.pageCount) }
        }
    }
}

@Composable
private fun PdfPagePreview(file: java.io.File, index: Int, total: Int) {
    val image by produceState<ImageBitmap?>(null, file.lastModified(), index) {
        value = withContext(Dispatchers.IO) { PdfFiles.renderPage(file, index, 1000)?.asImageBitmap() }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(6.dp)) {
            val img = image
            if (img != null) Image(img, contentDescription = "Página ${index + 1} del informe", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().testTag("pagina_${index + 1}"))
            else Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color(0xFFEFEFEF)), contentAlignment = Alignment.Center) { Text("Cargando página ${index + 1}…") }
            Text("Página ${index + 1} de $total", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Panel fotográfico: orden de código; cada punto con foto o «Sin fotografía registrada»
// -------------------------------------------------------------------------------------------------

@Composable
private fun PanelTab(vm: InformeViewModel) {
    val points by vm.panelPoints.collectAsState()
    val notify = LocalNotify.current
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
        item { Text("Panel fotográfico por punto (orden de código). Cada punto muestra su código, descripción, fotografía y pie de foto. Es el mismo contenido que sale en el PDF.", style = MaterialTheme.typography.bodySmall) }
        if (points.isEmpty()) item { Text("No hay puntos registrados.", style = MaterialTheme.typography.bodySmall) }
        items(points, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.code, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(p.description?.takeIf { it.isNotBlank() } ?: "Sin descripción", style = MaterialTheme.typography.bodySmall)
                    PointPhotoSection(p, { vm.setPhoto(p.id, it); notify("Fotografía guardada en el punto ${p.code}.") }, { vm.removePhoto(p.id); notify("Fotografía retirada.") }, { vm.saveCaption(p.id, it); notify("Pie de foto guardado.") }, notify)
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Anexos
// -------------------------------------------------------------------------------------------------

private sealed interface AnnexDlg {
    data object Add : AnnexDlg
    data class Edit(val a: AnnexEntity) : AnnexDlg
    data class Delete(val a: AnnexEntity) : AnnexDlg
    data class View(val a: AnnexEntity) : AnnexDlg
}

@Composable
private fun AnexosTab(vm: InformeViewModel) {
    val annexes by vm.annexList.collectAsState()
    val points by vm.pointsForAnnex.collectAsState()
    val notify = LocalNotify.current
    var dlg by remember { mutableStateOf<AnnexDlg?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var replaceTarget by remember { mutableStateOf<AnnexEntity?>(null) }
    var downloadTarget by remember { mutableStateOf<AnnexEntity?>(null) }
    fun close() { dlg = null; error = null }

    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val t = replaceTarget
        if (uri != null && t != null) vm.replaceAnnex(t.id, uri) { err -> notify(err ?: "Anexo reemplazado.") }
        replaceTarget = null
    }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val t = downloadTarget
        if (uri != null && t != null) vm.downloadAnnex(t.id, uri, notify)
        downloadTarget = null
    }

    when (val d = dlg) {
        AnnexDlg.Add -> AnnexDialog(null, points, error, ::close) { uri, title, desc, kind, pid, sec ->
            if (uri == null) error = "Elija el archivo del anexo." else vm.addAnnex(uri, title, desc, kind, pid, sec) { err -> if (err == null) { close(); notify("Anexo agregado.") } else error = err }
        }
        is AnnexDlg.Edit -> AnnexDialog(d.a, points, error, ::close) { _, title, desc, kind, pid, sec -> vm.editAnnex(d.a.id, title, desc, kind, pid, sec) { err -> if (err == null) { close(); notify("Anexo actualizado.") } else error = err } }
        is AnnexDlg.Delete -> ConfirmDialog("Eliminar anexo", "¿Eliminar el anexo «${d.a.title}» (${d.a.originalName})? Se borrará también el archivo guardado en el dispositivo.", onConfirm = { vm.deleteAnnex(d.a.id); close(); notify("Anexo eliminado.") }, onDismiss = ::close)
        is AnnexDlg.View -> AnnexViewer(d.a, vm, onDismiss = ::close, notify = notify)
        null -> Unit
    }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
        item {
            SectionCard("Anexos del levantamiento") {
                Text("Croquis, fichas de campo, documentos PDF, fotografías generales y otros respaldos. Son independientes de las fotografías por punto. Imágenes y PDF se incorporan al informe; otros formatos quedan en el respaldo del proyecto y el índice lo indica.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { error = null; dlg = AnnexDlg.Add }, modifier = Modifier.testTag("btn_agregar_anexo")) { Text("Agregar anexo") }
            }
        }
        if (annexes.isEmpty()) item { Text("Este proyecto no tiene anexos.", style = MaterialTheme.typography.bodySmall) }
        items(annexes, key = { it.id }) { a ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Anexo ${a.sortOrder} — ${a.title}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("${a.kind.labelEs} · ${a.originalName} · ${AnnexRules.typeLabel(a.originalName)} · ${"%.1f".format(a.sizeBytes / 1024.0)} KB", style = MaterialTheme.typography.bodySmall)
                    Text("Incorporado el ${a.addedAt.take(10)}" + (a.pointId?.let { id -> points.firstOrNull { it.id == id }?.let { " · punto ${it.code}" } } ?: "") + (a.reportSection?.let { " · sección $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    a.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { dlg = AnnexDlg.View(a) }) { Text("Ver") }
                        TextButton(onClick = { error = null; dlg = AnnexDlg.Edit(a) }) { Text("Editar descripción") }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { vm.moveAnnex(a.id, true) }) { Text("↑ Subir") }
                        TextButton(onClick = { vm.moveAnnex(a.id, false) }) { Text("↓ Bajar") }
                        TextButton(onClick = { replaceTarget = a; replacePicker.launch(arrayOf("*/*")) }) { Text("Reemplazar") }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { downloadTarget = a; downloadPicker.launch(a.originalName) }) { Text("Descargar") }
                        TextButton(onClick = { dlg = AnnexDlg.Delete(a) }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnexDialog(existing: AnnexEntity?, points: List<PointEntity>, error: String?, onDismiss: () -> Unit, onConfirm: (Uri?, String, String, AnnexKind, Long?, String) -> Unit) {
    var uri by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: AnnexKind.CROQUIS) }
    var point by remember { mutableStateOf(points.firstOrNull { it.id == existing?.pointId }) }
    var section by remember { mutableStateOf(existing?.reportSection) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            uri = u
            pickedName = runCatching { context.contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null } }.getOrNull() ?: u.lastPathSegment
            if (title.isBlank()) title = pickedName?.substringBeforeLast('.').orEmpty()
        }
    }
    FormDialog(if (existing == null) "Agregar anexo" else "Editar anexo", error = error, onDismiss = onDismiss, onConfirm = { onConfirm(uri, title, description, kind, point?.id, section.orEmpty()) }) {
        if (existing == null) {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().testTag("btn_elegir_archivo")) { Text(pickedName ?: "Elegir archivo…") }
            Text("Permitidos: ${AnnexRules.allowedExtensions().joinToString(", ")} · máximo ${AnnexRules.MAX_BYTES / 1024 / 1024} MB.", style = MaterialTheme.typography.bodySmall)
        } else Text("Archivo: ${existing.originalName}", style = MaterialTheme.typography.bodySmall)
        TextFieldRow("Título", title, { title = it })
        TextFieldRow("Descripción", description, { description = it }, singleLine = false)
        Dropdown("Tipo de anexo", AnnexKind.entries.toList(), kind, { it.labelEs }, { kind = it ?: AnnexKind.OTRO })
        Dropdown("Punto relacionado (opcional)", points, point, { it.code }, { point = it }, allowNone = "(ninguno)")
        Dropdown("Sección del informe (opcional)", SECTIONS, section, { it }, { section = it }, allowNone = "(ninguna)")
    }
}

@Composable
private fun AnnexViewer(a: AnnexEntity, vm: InformeViewModel, onDismiss: () -> Unit, notify: (String) -> Unit) {
    val context = LocalContext.current
    val file = remember(a.storedFile) { vm.annexFile(a) }
    val kind = AnnexRules.kindOf(a.mimeType)
    if (kind == AnnexFileKind.OTHER) {
        LaunchedEffect(a.id) {
            val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(vm.annexUri(a), a.mimeType); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            try { context.startActivity(intent) } catch (e: ActivityNotFoundException) { notify("No hay una aplicación para abrir este tipo de archivo; use «Descargar».") }
            onDismiss()
        }
        return
    }
    var page by remember { mutableIntStateOf(0) }
    val pages = remember(file) { if (kind == AnnexFileKind.PDF) PdfFiles.pageCount(file) ?: 0 else 1 }
    val image by produceState<ImageBitmap?>(null, file, page) {
        value = withContext(Dispatchers.IO) {
            if (kind == AnnexFileKind.PDF) PdfFiles.renderPage(file, page, 1100)?.asImageBitmap() else decodeOriented(file, 2000)?.asImageBitmap()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Anexo ${a.sortOrder} — ${a.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val img = image
                if (img != null) Image(img, contentDescription = a.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                else if (pages == 0) Text("No se pudo leer el archivo (dañado o protegido).", color = MaterialTheme.colorScheme.error)
                else Text("Cargando…")
                if (kind == AnnexFileKind.PDF && pages > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Text("◀") }
                    Text("Página ${page + 1} de $pages")
                    TextButton(onClick = { if (page < pages - 1) page++ }, enabled = page < pages - 1) { Text("▶") }
                }
                a.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
