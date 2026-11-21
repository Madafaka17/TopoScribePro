package com.toposcribe.app.ui.screens.proyecto

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toposcribe.app.ui.components.ConfirmDialog
import com.toposcribe.app.ui.components.Dropdown
import com.toposcribe.app.ui.components.ErrorText
import com.toposcribe.app.ui.components.FormDialog
import com.toposcribe.app.ui.components.LocalNotify
import com.toposcribe.app.ui.components.SectionCard
import com.toposcribe.app.ui.components.TextFieldRow
import com.toposcribe.data.local.entity.CoordinateMode
import com.toposcribe.data.local.entity.Hemisphere
import com.toposcribe.data.local.entity.InstrumentType
import com.toposcribe.data.local.entity.ProjectEntity

@Composable
fun ProyectoScreen(modifier: Modifier = Modifier, vm: ProyectoViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val notify = LocalNotify.current
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newError by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<ProjectEntity?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportBackup(uri, notify) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importBackup(uri, notify) }

    if (showNew) {
        FormDialog("Nuevo proyecto", "Crear", newError, onDismiss = { showNew = false; newError = null }, onConfirm = {
            vm.create(newName) { err -> if (err == null) { showNew = false; newName = ""; newError = null; notify("Proyecto creado (vacío).") } else newError = err }
        }) {
            Text("El proyecto nuevo comienza vacío: sin mediciones, fotografías ni anexos.", style = MaterialTheme.typography.bodySmall)
            TextFieldRow("Nombre del proyecto", newName, { newName = it })
        }
    }
    toDelete?.let { p ->
        ConfirmDialog("Eliminar proyecto", "¿Eliminar el proyecto «${p.name.ifBlank { "(sin nombre)" }}» con todos sus puntos, lecturas, fotografías y anexos? Exporte antes un respaldo si desea conservarlo. Esta acción no se puede deshacer.", onConfirm = { vm.delete(p); toDelete = null; notify("Proyecto eliminado.") }, onDismiss = { toDelete = null })
    }

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
        item {
            SectionCard("Proyectos") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showNew = true }, modifier = Modifier.testTag("btn_nuevo_proyecto")) { Text("Nuevo proyecto") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, modifier = Modifier.testTag("btn_importar")) { Text("Importar respaldo") }
                }
                if (ui.projects.isEmpty() && !ui.loading) Text("Todavía no hay proyectos. Cree uno nuevo o importe un respaldo.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(ui.projects, key = { it.id }) { p ->
            val selected = p.id == ui.current?.id
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name.ifBlank { "(sin nombre)" }, style = MaterialTheme.typography.titleSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(listOfNotNull(p.date, p.location).joinToString(" · ").ifBlank { "Sin fecha ni ubicación" }, style = MaterialTheme.typography.bodySmall)
                        if (selected) Text("Proyecto abierto", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Row {
                        if (!selected) TextButton(onClick = { vm.open(p.id) }) { Text("Abrir") }
                        TextButton(onClick = { toDelete = p }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        val current = ui.current
        if (current != null) {
            item { ProjectForm(current, vm, notify) }
            item {
                SectionCard("Respaldo completo del proyecto") {
                    Text("Un único archivo .zip con todos los datos del proyecto, las fotografías por punto y los anexos (el contenido de los archivos, no solo sus nombres). Sirve para restaurar en otro dispositivo.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { exportLauncher.launch("respaldo_${current.name.ifBlank { "proyecto" }.replace(Regex("[^A-Za-z0-9_-]+"), "_")}.zip") }, modifier = Modifier.testTag("btn_exportar_respaldo")) { Text("Exportar respaldo") }
                }
            }
        }
    }
}

@Composable
private fun ProjectForm(p: ProjectEntity, vm: ProyectoViewModel, notify: (String) -> Unit) {
    var name by remember(p.id, p.updatedAt) { mutableStateOf(p.name) }
    var purpose by remember(p.id, p.updatedAt) { mutableStateOf(p.purpose.orEmpty()) }
    var location by remember(p.id, p.updatedAt) { mutableStateOf(p.location.orEmpty()) }
    var date by remember(p.id, p.updatedAt) { mutableStateOf(p.date.orEmpty()) }
    var responsible by remember(p.id, p.updatedAt) { mutableStateOf(p.responsible.orEmpty()) }
    var terrain by remember(p.id, p.updatedAt) { mutableStateOf(p.siteCharacteristics.orEmpty()) }
    var level by remember(p.id, p.updatedAt) { mutableStateOf(p.levelInstrument.orEmpty()) }
    var theodolite by remember(p.id, p.updatedAt) { mutableStateOf(p.theodoliteInstrument.orEmpty()) }
    var units by remember(p.id, p.updatedAt) { mutableStateOf(p.unitsDistance) }
    var angleUnits by remember(p.id, p.updatedAt) { mutableStateOf(p.unitsAngle) }
    var mode by remember(p.id, p.updatedAt) { mutableStateOf(p.coordinateMode) }
    var system by remember(p.id, p.updatedAt) { mutableStateOf(p.coordinateSystem.orEmpty()) }
    var datum by remember(p.id, p.updatedAt) { mutableStateOf(p.utmDatum.orEmpty()) }
    var zone by remember(p.id, p.updatedAt) { mutableStateOf(p.utmZone?.toString().orEmpty()) }
    var hemi by remember(p.id, p.updatedAt) { mutableStateOf(p.utmHemisphere) }
    var obs by remember(p.id, p.updatedAt) { mutableStateOf(p.observations.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    val legacy = p.instrumentType == InstrumentType.ESTACION_TOTAL || p.instrumentType == InstrumentType.GNSS_RTK || !p.instrument.isNullOrBlank()
    SectionCard("Datos del proyecto") {
        if (legacy) Text(
            "Este proyecto se creó con una versión anterior de la aplicación (instrumento registrado: ${p.instrument ?: p.instrumentType.name}). Sus datos históricos se conservan; las opciones de estación total, GNSS/RTK y poligonales ya no se ofrecen para capturar.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
        )
        TextFieldRow("Nombre del proyecto *", name, { name = it }, modifier = Modifier.fillMaxWidth().testTag("campo_nombre"))
        TextFieldRow("Objetivo del levantamiento", purpose, { purpose = it }, singleLine = false)
        TextFieldRow("Ubicación", location, { location = it })
        TextFieldRow("Fecha (AAAA-MM-DD)", date, { date = it })
        TextFieldRow("Responsable(s)", responsible, { responsible = it })
        TextFieldRow("Descripción del terreno", terrain, { terrain = it }, singleLine = false)
        TextFieldRow("Nivel de ingeniero (marca / modelo / serie)", level, { level = it })
        TextFieldRow("Teodolito (marca / modelo / serie)", theodolite, { theodolite = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown("Unidades de distancia", listOf("m" to "Metros (m)", "ft" to "Pies (ft)"), listOf("m" to "Metros (m)", "ft" to "Pies (ft)").first { it.first == units || (units !in listOf("m", "ft") && it.first == "m") }, { it.second }, { units = it?.first ?: "m" }, Modifier.weight(1f))
            Dropdown("Formato angular", listOf("DMS" to "G° M' S\"", "DEC" to "Decimal"), listOf("DMS" to "G° M' S\"", "DEC" to "Decimal").first { it.first == angleUnits || (angleUnits !in listOf("DMS", "DEC") && it.first == "DMS") }, { it.second }, { angleUnits = it?.first ?: "DMS" }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == CoordinateMode.LOCAL, onClick = { mode = CoordinateMode.LOCAL }, label = { Text("Coordenadas locales") })
            FilterChip(selected = mode == CoordinateMode.UTM, onClick = { mode = CoordinateMode.UTM }, label = { Text("UTM") })
        }
        if (mode == CoordinateMode.LOCAL) TextFieldRow("Sistema de coordenadas (descripción)", system, { system = it })
        else {
            TextFieldRow("Datum (p. ej. WGS84, PSAD56)", datum, { datum = it })
            TextFieldRow("Zona UTM (1-60)", zone, { zone = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Hemisphere.entries.forEach { h -> FilterChip(selected = hemi == h, onClick = { hemi = h }, label = { Text(if (h == Hemisphere.N) "Norte" else "Sur") }) } }
        }
        TextFieldRow("Observaciones", obs, { obs = it }, singleLine = false)
        ErrorText(error)
        Button(onClick = {
            vm.save(p, ProjectForm(name, purpose, location, date, responsible, terrain, level, theodolite, units, angleUnits, mode, system, datum, zone, hemi, obs)) { err -> error = err; if (err == null) notify("Proyecto guardado en el dispositivo.") }
        }, modifier = Modifier.testTag("btn_guardar_proyecto")) { Text("Guardar proyecto") }
    }
}
