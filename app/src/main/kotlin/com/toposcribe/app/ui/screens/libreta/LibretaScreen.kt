package com.toposcribe.app.ui.screens.libreta

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toposcribe.app.data.repository.PointResult
import com.toposcribe.app.data.repository.procedureLabel
import com.toposcribe.app.ui.components.ConfirmDialog
import com.toposcribe.app.ui.components.LocalNotify
import com.toposcribe.app.ui.components.NoProjectMessage
import com.toposcribe.app.ui.components.PointPhotoSection
import com.toposcribe.app.ui.components.SectionCard
import com.toposcribe.app.ui.components.StateBanner
import com.toposcribe.calc.ResultState
import com.toposcribe.app.ui.theme.MonoFieldStyle
import com.toposcribe.calc.ReadingType
import com.toposcribe.calc.formatDegreesAsDms
import com.toposcribe.data.local.entity.LevelingObservationEntity
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.StationSetupEntity
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun f3(v: Double?) = if (v == null) "—" else String.format(Locale.US, "%.3f", v)
private fun typeLabel(t: ReadingType) = when (t) { ReadingType.BS -> "VA"; ReadingType.IS -> "VI"; ReadingType.FS -> "VD" }

/** Siguiente código libre: A, B, … Z y después P-1, P-2… (editable por el usuario). */
fun suggestNextPointCode(existing: Collection<String>): String {
    val used = existing.map { it.trim().uppercase() }.toSet()
    for (c in 'A'..'Z') if (c.toString() !in used) return c.toString()
    var n = 1
    while ("P-$n" in used) n++
    return "P-$n"
}

private sealed interface Dlg {
    data class Point(val point: PointEntity?) : Dlg
    data class PointCard(val pointId: Long) : Dlg
    data class DeletePoint(val point: PointEntity) : Dlg
    data class Run(val edit: Boolean) : Dlg
    data object DeleteRun : Dlg
    data class Level(val obs: LevelingObservationEntity?) : Dlg
    data class DeleteLevel(val obs: LevelingObservationEntity) : Dlg
    data class LevelHistory(val obs: LevelingObservationEntity) : Dlg
    data class Setup(val setup: StationSetupEntity?) : Dlg
    data class Radiation(val obs: PlanimetryObservationEntity?) : Dlg
    data class DeleteRadiation(val obs: PlanimetryObservationEntity) : Dlg
    data class RadiationHistory(val obs: PlanimetryObservationEntity) : Dlg
}

@Composable
fun LibretaScreen(modifier: Modifier = Modifier, vm: LibretaViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val notify = LocalNotify.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    fun close() { dlg = null; dialogError = null }
    fun finish(err: String?, ok: String? = null) { if (err == null) { close(); ok?.let(notify) } else dialogError = err }

    val levelCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { writeText(context, uri, vm.levelingCsv()); notify("Libreta de nivelación exportada (CSV).") }
    }
    val radiationCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { writeText(context, uri, vm.radiationCsv()); notify("Libreta de radiación exportada (CSV).") }
    }

    if (ui.project == null) { if (!ui.loading) NoProjectMessage(modifier); return }
    val codes = ui.points.map { it.code }
    val pointById = ui.points.associateBy { it.id }
    val hasCoords: (PointEntity) -> Boolean = { p -> ui.results[p.id]?.hasCoordinates == true || ui.radiation?.stationCoordinates?.containsKey(p.id) == true }

    // ------------------------------- diálogos -------------------------------
    when (val d = dlg) {
        is Dlg.Point -> PointDialog(d.point, suggestNextPointCode(codes), dialogError, ::close) { f -> vm.savePoint(d.point, f) { finish(it, if (d.point == null) "Punto «${f.code.trim()}» añadido." else "Punto actualizado.") } }
        is Dlg.PointCard -> pointById[d.pointId]?.let { p ->
            AlertDialog(
                onDismissRequest = ::close,
                title = { Text("Ficha del punto ${p.code}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.description ?: "Sin descripción", style = MaterialTheme.typography.bodyMedium)
                        Text("Procedimiento: ${procedureLabel(p.instrumentRef)}", style = MaterialTheme.typography.bodySmall)
                        p.observations?.let { Text("Observaciones: $it", style = MaterialTheme.typography.bodySmall) }
                        PointPhotoSection(p, { vm.setPhoto(p.id, it); notify("Fotografía guardada en el punto ${p.code}.") }, { vm.removePhoto(p.id); notify("Fotografía retirada.") }, { vm.saveCaption(p.id, it); notify("Pie de foto guardado.") }, notify)
                    }
                },
                confirmButton = { TextButton(onClick = ::close) { Text("Cerrar") } },
            )
        }
        is Dlg.DeletePoint -> ConfirmDialog("Eliminar punto", "¿Eliminar el punto «${d.point.code}»? Se borrará también su fotografía. Si lo usa alguna lectura, nivelación o línea, no se eliminará.", onConfirm = {
            vm.deletePoint(d.point) { err -> if (err == null) { close(); notify("Punto eliminado.") } else { close(); notify(err) } }
        }, onDismiss = ::close)
        is Dlg.Run -> RunDialog(if (d.edit) ui.level?.run else null, ui.points.filter { it.fixedElevation != null }, dialogError, ::close) { name, s, e, k ->
            if (d.edit) ui.level?.run?.let { run -> vm.updateRun(run, name, e, k) { finish(it, "Nivelación actualizada.") } } else vm.createRun(name, s, e, k) { finish(it, "Nivelación creada.") }
        }
        Dlg.DeleteRun -> ui.level?.run?.let { run ->
            ConfirmDialog("Eliminar nivelación", "¿Eliminar la nivelación «${run.name}» con TODAS sus lecturas y las cotas que calculó? Esta acción no se puede deshacer.", onConfirm = { vm.deleteRun(run); close(); notify("Nivelación eliminada.") }, onDismiss = ::close)
        }
        is Dlg.Level -> {
            val ev = ui.level
            val last = ev?.rows?.lastOrNull()?.observation
            val suggestedType = when { last == null -> ReadingType.BS; last.readingType == ReadingType.FS && last.isTurningPoint -> ReadingType.BS; else -> ReadingType.IS }
            val suggestedCode = when {
                last == null -> ev?.startBmCode.orEmpty()
                last.readingType == ReadingType.FS && last.isTurningPoint -> ev.rows.last().pointCode
                else -> suggestNextPointCode(codes)
            }
            LevelReadingDialog(d.obs, d.obs?.let { pointById[it.pointId]?.code }.orEmpty(), ui.points, suggestedCode, suggestedType, dialogError, ::close) { code, desc, type, value, turning, partial, notes ->
                if (d.obs == null) vm.addLevelReading(code, desc, type, value, turning, partial, notes) { finish(it, "Lectura añadida.") }
                else vm.updateLevelReading(d.obs, code, type, value, turning, partial, notes) { finish(it, "Lectura corregida: el valor original queda en el historial.") }
            }
        }
        is Dlg.DeleteLevel -> ConfirmDialog("Eliminar lectura", "¿Eliminar la lectura ${d.obs.sequenceOrder} (${typeLabel(d.obs.readingType)} ${f3(d.obs.readingValue)})? El resultado de la altimetría quedará desactualizado hasta recalcular.", onConfirm = { vm.deleteLevelReading(d.obs); close(); notify("Lectura eliminada.") }, onDismiss = ::close)
        is Dlg.LevelHistory -> HistoryDialog("Historial de la lectura ${d.obs.sequenceOrder}", { vm.levelHistory(d.obs.id) }, ::close)
        is Dlg.Setup -> SetupDialog(d.setup, ui.points, hasCoords, dialogError, ::close) { f -> vm.saveSetup(d.setup, f) { finish(it, "Estación guardada.") } }
        is Dlg.Radiation -> {
            val lastObs = ui.radiation?.rows?.lastOrNull()?.observation
            RadiationDialog(d.obs, d.obs?.let { pointById[it.targetPointId]?.code }.orEmpty(), ui.setups, ui.points, suggestNextPointCode(codes), lastObs?.stationSetupId ?: ui.setups.lastOrNull()?.id, dialogError, ::close) { form, err ->
                if (form == null) dialogError = err
                else if (d.obs == null) vm.addRadiation(form) { finish(it, "Lectura añadida.") }
                else vm.updateRadiation(d.obs, form) { finish(it, "Lectura corregida: el valor original queda en el historial.") }
            }
        }
        is Dlg.DeleteRadiation -> ConfirmDialog("Eliminar lectura", "¿Eliminar la lectura a «${pointById[d.obs.targetPointId]?.code}»? La planimetría quedará desactualizada hasta recalcular.", onConfirm = { vm.deleteRadiation(d.obs); close(); notify("Lectura eliminada.") }, onDismiss = ::close)
        is Dlg.RadiationHistory -> HistoryDialog("Historial de la lectura", { vm.radiationHistory(d.obs.id) }, ::close)
        null -> Unit
    }

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
        item {
            SectionCard("Libreta de campo") {
                Text("Registre aquí los datos reales del levantamiento. Un proyecto nuevo comienza vacío.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { dialogError = null; dlg = Dlg.Point(null) }, modifier = Modifier.testTag("btn_anadir_punto")) { Text("Añadir punto") }
                    Button(onClick = {
                        dialogError = null
                        dlg = if (ui.mode == LibretaMode.NIVEL) { if (ui.level == null) Dlg.Run(false) else Dlg.Level(null) } else { if (ui.setups.isEmpty()) Dlg.Setup(null) else Dlg.Radiation(null) }
                    }, modifier = Modifier.testTag("btn_anadir_lectura")) { Text("Añadir lectura") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibretaMode.entries.forEach { m -> FilterChip(selected = ui.mode == m, onClick = { vm.setMode(m) }, label = { Text(m.label) }) }
                }
                ui.level?.let { if (it.state != ResultState.ACTUALIZADO && it.rows.isNotEmpty()) StateBanner(it.state, "Altimetría") }
                ui.radiation?.let { if (it.state != ResultState.ACTUALIZADO && it.rows.isNotEmpty()) StateBanner(it.state, "Planimetría") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { levelCsv.launch("libreta_nivelacion.csv") }) { Text("Libreta nivel (CSV)") }
                    OutlinedButton(onClick = { radiationCsv.launch("libreta_radiacion.csv") }) { Text("Libreta radiación (CSV)") }
                }
            }
        }

        item { Text("Puntos (${ui.points.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        if (ui.points.isEmpty()) item { Text("Aún no hay puntos. Use «Añadir punto» para registrar el BM, la estación o los puntos del levantamiento.", style = MaterialTheme.typography.bodySmall) }
        items(ui.points, key = { it.id }) { p -> PointCardRow(p, ui.results[p.id], onCard = { dlg = Dlg.PointCard(p.id) }, onEdit = { dialogError = null; dlg = Dlg.Point(p) }, onDelete = { dlg = Dlg.DeletePoint(p) }) }

        if (ui.mode == LibretaMode.NIVEL) levelSection(ui, onRun = { edit -> dialogError = null; dlg = Dlg.Run(edit) }, onSelect = vm::selectRun, onDeleteRun = { dlg = Dlg.DeleteRun },
            onEdit = { dialogError = null; dlg = Dlg.Level(it) }, onDelete = { dlg = Dlg.DeleteLevel(it) }, onHistory = { dlg = Dlg.LevelHistory(it) })
        else radiationSection(ui, onSetup = { s -> dialogError = null; dlg = Dlg.Setup(s) }, onEdit = { dialogError = null; dlg = Dlg.Radiation(it) }, onDelete = { dlg = Dlg.DeleteRadiation(it) }, onHistory = { dlg = Dlg.RadiationHistory(it) })
    }
}

private suspend fun writeText(context: android.content.Context, uri: android.net.Uri, text: String?) = withContext(Dispatchers.IO) {
    if (text != null) context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
}

@Composable
private fun PointCardRow(p: PointEntity, r: PointResult?, onCard: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.code, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("punto_${p.code}"))
                if (!p.instrumentRef.isNullOrBlank()) Text("  ·  ${procedureLabel(p.instrumentRef)}", style = MaterialTheme.typography.bodySmall)
                if (p.sketchPhotoPath != null) Text("  · con foto", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            Text(p.description?.takeIf { it.isNotBlank() } ?: "Sin descripción", style = MaterialTheme.typography.bodySmall)
            val known = buildList {
                if (r?.east != null && r.north != null) add("E ${f3(r.east)}  N ${f3(r.north)}")
                if (r?.elevation != null) add("Cota ${f3(r.elevation)} (${r.elevationSource.labelEs})")
            }
            if (known.isNotEmpty()) Text(known.joinToString("   "), style = MonoFieldStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)))
            Row { TextButton(onClick = onCard) { Text("Ficha / foto") }; TextButton(onClick = onEdit) { Text("Editar") }; TextButton(onClick = onDelete) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.levelSection(
    ui: LibretaUi, onRun: (Boolean) -> Unit, onSelect: (Long) -> Unit, onDeleteRun: () -> Unit,
    onEdit: (LevelingObservationEntity) -> Unit, onDelete: (LevelingObservationEntity) -> Unit, onHistory: (LevelingObservationEntity) -> Unit,
) {
    item { Text("Libreta de nivelación", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
    val ev = ui.level
    if (ev == null) {
        item { Text("No hay nivelaciones. Pulse «Añadir lectura» para crear la primera (BM inicial con cota conocida).", style = MaterialTheme.typography.bodySmall) }
        return
    }
    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (ui.runs.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ui.runs.forEach { r -> FilterChip(selected = r.id == ev.run.id, onClick = { onSelect(r.id) }, label = { Text(r.name, maxLines = 1) }) } }
                Text(ev.run.name, style = MaterialTheme.typography.titleMedium)
                Text("BM inicial ${ev.startBmCode}: cota ${f3(ev.startElevation)}", style = MaterialTheme.typography.bodySmall)
                Text(if (ev.endBmCode != null || ev.knownEndElevation != null) "BM final ${ev.endBmCode ?: "(cota de cierre)"}: cota ${f3(ev.knownEndElevation)}" else "Sin BM final de cota conocida", style = MaterialTheme.typography.bodySmall)
                Row { TextButton(onClick = { onRun(true) }) { Text("Editar nivelación") }; TextButton(onClick = { onRun(false) }) { Text("Nueva nivelación") }; TextButton(onClick = onDeleteRun) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
    if (ev.rows.isEmpty()) item { Text("Sin lecturas. Empiece con la vista atrás (VA) sobre el BM ${ev.startBmCode}.", style = MaterialTheme.typography.bodySmall) }
    items(ev.rows, key = { it.observation.id }) { row ->
        val o = row.observation
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("#${o.sequenceOrder}  Est. ${row.calc?.stationNumber ?: "—"}  ·  ${row.pointCode}  ·  ${typeLabel(o.readingType)} ${f3(o.readingValue)}" + (if (o.isTurningPoint) "  · punto de cambio" else ""), style = MonoFieldStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp)))
                if (o.readingType != ReadingType.BS) Text("Distancia parcial: ${if (o.partialDistance == null) "falta" else f3(o.partialDistance)}", style = MaterialTheme.typography.bodySmall)
                o.rawNotes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                row.correction?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
                Row { TextButton(onClick = { onEdit(o) }) { Text("Editar") }; TextButton(onClick = { onHistory(o) }) { Text("Historial") }; TextButton(onClick = { onDelete(o) }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.radiationSection(
    ui: LibretaUi, onSetup: (StationSetupEntity?) -> Unit, onEdit: (PlanimetryObservationEntity) -> Unit, onDelete: (PlanimetryObservationEntity) -> Unit, onHistory: (PlanimetryObservationEntity) -> Unit,
) {
    item { Text("Libreta de radiación (teodolito)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Estaciones ocupadas (${ui.setups.size})", style = MaterialTheme.typography.labelLarge)
            if (ui.setups.isEmpty()) Text("Defina la estación ocupada con coordenadas (conocidas o locales declaradas), referencia de orientación y convenio angular antes de registrar lecturas.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onSetup(null) }) { Text("Nueva estación / cambio de estación") }
        }
    }
    items(ui.setups, key = { "s${it.id}" }) { s ->
        val station = ui.points.firstOrNull { it.id == s.occupiedPointId }
        val coords = ui.radiation?.stationCoordinates?.get(s.occupiedPointId)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Estación ${station?.code ?: "?"}  ·  AI ${f3(s.instrumentHeightAi)}", style = MaterialTheme.typography.titleSmall)
                Text(if (coords != null) "E ${f3(coords.first)}  N ${f3(coords.second)}" else "Coordenadas de la estación: NO conocidas", style = MonoFieldStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)))
                Text("Referencia «${s.backsightPointLabel}»: azimut ${formatDegreesAsDms(s.baseAzimuthDecimal, 2)}, lectura ${formatDegreesAsDms(s.backsightReadingDeg, 2)} · ${s.angleConvention.labelEs.substringBefore(" (")}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onSetup(s) }) { Text("Editar estación") }
            }
        }
    }
    item { Text("Lecturas (${ui.radiation?.rows?.size ?: 0})", style = MaterialTheme.typography.labelLarge) }
    val rows = ui.radiation?.rows.orEmpty()
    if (rows.isEmpty()) item { Text("Sin lecturas de radiación.", style = MaterialTheme.typography.bodySmall) }
    items(rows, key = { "o${it.observation.id}" }) { r ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${r.stationCode} → ${r.targetCode}${r.targetDescription?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}", style = MaterialTheme.typography.titleSmall)
                Text(r.originalReading, style = MonoFieldStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)))
                r.observation.rawNotes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                r.legacyNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
                r.correction?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
                if (r.legacyNote == null) Row {
                    TextButton(onClick = { onEdit(r.observation) }) { Text("Editar") }; TextButton(onClick = { onHistory(r.observation) }) { Text("Historial") }
                    TextButton(onClick = { onDelete(r.observation) }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
