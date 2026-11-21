package com.toposcribe.app.ui.screens.planimetria

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toposcribe.app.data.repository.PointResult
import com.toposcribe.app.data.repository.RadiationRow
import com.toposcribe.app.ui.components.ConfirmDialog
import com.toposcribe.app.ui.components.Dropdown
import com.toposcribe.app.ui.components.FormDialog
import com.toposcribe.app.ui.components.LocalNotify
import com.toposcribe.app.ui.components.NoProjectMessage
import com.toposcribe.app.ui.components.SectionCard
import com.toposcribe.app.ui.components.StateBanner
import com.toposcribe.app.ui.components.TextFieldRow
import com.toposcribe.app.ui.theme.MonoFieldStyle
import com.toposcribe.calc.formatDegreesAsDms
import com.toposcribe.calc.planAzimuth
import com.toposcribe.calc.planDistance
import com.toposcribe.data.local.entity.AlignmentEntity
import java.util.Locale

private fun f3(v: Double?) = if (v == null) "—" else String.format(Locale.US, "%.3f", v)
private val small = MonoFieldStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp))

@Composable
fun PlanimetriaScreen(modifier: Modifier = Modifier, vm: PlanimetriaViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val notify = LocalNotify.current
    if (ui.projectId == null) { NoProjectMessage(modifier); return }
    var ficha by remember { mutableStateOf<RadiationRow?>(null) }
    var lineDialog by remember { mutableStateOf<AlignmentEntity?>(null) }
    var newLine by remember { mutableStateOf(false) }
    var deleteLine by remember { mutableStateOf<AlignmentEntity?>(null) }
    var lineError by remember { mutableStateOf<String?>(null) }
    val withCoords = ui.results.filter { it.hasCoordinates }
    val eval = ui.evaluation
    val rows = eval?.rows.orEmpty()

    ficha?.let { FichaDialog(it) { ficha = null } }
    if (newLine || lineDialog != null) {
        LineDialog(lineDialog, withCoords, lineError, onDismiss = { newLine = false; lineDialog = null; lineError = null }) { name, ids, closed ->
            vm.saveLine(lineDialog?.id, name, ids, closed) { err -> if (err == null) { newLine = false; lineDialog = null; lineError = null; notify("Línea guardada.") } else lineError = err }
        }
    }
    deleteLine?.let { l -> ConfirmDialog("Eliminar línea", "¿Eliminar la línea «${l.name}»? Los puntos no se borran.", onConfirm = { vm.deleteLine(l); deleteLine = null }, onDismiss = { deleteLine = null }) }

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
        item {
            SectionCard("Planimetría por radiación (teodolito)") {
                Text("Solo se usa el método de radiación: cada punto se calcula desde una estación ocupada con coordenadas conocidas o locales declaradas, una referencia de orientación y un convenio angular.", style = MaterialTheme.typography.bodySmall)
                if (eval != null) StateBanner(eval.state, "Planimetría")
                Button(onClick = { vm.compute(notify) }, enabled = rows.any { it.legacyNote == null }, modifier = Modifier.fillMaxWidth().testTag("btn_calcular_radiacion")) { Text("Calcular y guardar radiación") }
            }
        }
        if (rows.isEmpty()) item { Text("No hay lecturas de radiación. Regístrelas en «Libreta de campo» (modo Teodolito).", style = MaterialTheme.typography.bodySmall) }
        items(rows, key = { it.observation.id }) { r ->
            val s = r.step
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${r.stationCode} → ${r.targetCode}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("Lectura original: ${r.originalReading}", style = small)
                    Text("Orientación: " + (r.setup?.let { "ref. «${it.backsightPointLabel}» Az ${formatDegreesAsDms(it.baseAzimuthDecimal, 2)}, Hz ${formatDegreesAsDms(it.backsightReadingDeg, 2)}, ${it.angleConvention.name.lowercase()}" } ?: "azimut directo"), style = small)
                    if (s != null) {
                        Text("Azimut ${formatDegreesAsDms(s.azimuthDeg, 2)}   d = ${f3(s.horizontalDistance)}", style = small)
                        Text("ΔE ${f3(s.deltaE)}   ΔN ${f3(s.deltaN)}", style = small)
                        Text("Este ${f3(s.east)}   Norte ${f3(s.north)}", style = small, color = MaterialTheme.colorScheme.secondary)
                        TextButton(onClick = { ficha = r }) { Text("Ficha: fórmulas con valores") }
                    } else {
                        r.legacyNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                        r.errors.forEach { Text("No calculado: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        item {
            SectionCard("Unir puntos (líneas en el orden elegido)") {
                Text("Solo se pueden unir puntos con coordenadas calculadas. Las líneas aparecen en el Plano CAD y en el DXF.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { newLine = true; lineError = null }, enabled = withCoords.size >= 2, modifier = Modifier.testTag("btn_nueva_linea")) { Text("Nueva línea") }
            }
        }
        items(ui.lines, key = { "l${it.id}" }) { l ->
            val byId = withCoords.associateBy { it.point.id }
            val vertices = l.pointSequence.mapNotNull { byId[it] }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${l.name}${if (l.closed) " (cerrada)" else ""}: " + l.pointSequence.joinToString(" → ") { id -> ui.results.firstOrNull { it.point.id == id }?.point?.code ?: "?" }, style = MaterialTheme.typography.titleSmall)
                    var total = 0.0
                    val pairs = vertices.zipWithNext() + if (l.closed && vertices.size > 2) listOf(vertices.last() to vertices.first()) else emptyList()
                    pairs.forEach { (a, b) ->
                        val d = planDistance(a.east!!, a.north!!, b.east!!, b.north!!); total += d
                        Text("${a.point.code} → ${b.point.code}: ${f3(d)} ${ui.units}   Az ${formatDegreesAsDms(planAzimuth(a.east, a.north, b.east, b.north), 2)}", style = small)
                    }
                    Text("Longitud total: ${f3(total)} ${ui.units}", style = small, color = MaterialTheme.colorScheme.secondary)
                    Row { TextButton(onClick = { lineDialog = l; lineError = null }) { Text("Editar") }; TextButton(onClick = { deleteLine = l }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } }
                }
            }
        }
        item { DistanceTool(withCoords, ui.units) }
    }
}

@Composable
private fun DistanceTool(points: List<PointResult>, units: String) {
    var a by remember { mutableStateOf<PointResult?>(null) }
    var b by remember { mutableStateOf<PointResult?>(null) }
    SectionCard("Distancia entre puntos calculados") {
        Dropdown("Punto A", points, a, { it.point.code }, { a = it })
        Dropdown("Punto B", points, b, { it.point.code }, { b = it })
        val pa = a; val pb = b
        if (pa != null && pb != null) {
            Text("Distancia horizontal A–B: ${f3(planDistance(pa.east!!, pa.north!!, pb.east!!, pb.north!!))} $units", style = MaterialTheme.typography.bodyMedium)
            Text("Azimut A→B: ${formatDegreesAsDms(planAzimuth(pa.east, pa.north, pb.east!!, pb.north!!), 2)}", style = MaterialTheme.typography.bodySmall)
        } else Text("Elija dos puntos con coordenadas.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FichaDialog(row: RadiationRow, onDismiss: () -> Unit) {
    val s = row.step ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ficha del punto ${row.targetCode}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Estación ocupada: ${row.stationCode}", style = MaterialTheme.typography.bodyMedium)
                Text("Lectura original: ${row.originalReading}", style = small)
                Text("Orientación: " + (row.setup?.let { "ref. «${it.backsightPointLabel}», azimut ${formatDegreesAsDms(it.baseAzimuthDecimal, 2)}, lectura ${formatDegreesAsDms(it.backsightReadingDeg, 2)}, convenio ${it.angleConvention.name.lowercase()}" } ?: "azimut ingresado directamente"), style = small)
                Text("Azimut usado: ${formatDegreesAsDms(s.azimuthDeg, 2)}   ·   Distancia horizontal usada: ${f3(s.horizontalDistance)}", style = small)
                Text("Proyecciones: ΔE = ${f3(s.deltaE)}   ΔN = ${f3(s.deltaN)}", style = small)
                Text("Coordenadas: E = ${f3(s.east)}   N = ${f3(s.north)}", style = small)
                Text("Fórmulas con valores sustituidos", style = MaterialTheme.typography.labelLarge)
                listOf(s.azimuthFormula, s.distanceFormula, s.deltaEFormula, s.deltaNFormula, s.eastFormula, s.northFormula).forEach { Text(it, style = small) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun LineDialog(existing: AlignmentEntity?, points: List<PointResult>, error: String?, onDismiss: () -> Unit, onConfirm: (String, List<Long>, Boolean) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var closed by remember { mutableStateOf(existing?.closed ?: false) }
    val ids = remember { mutableStateListOf<Long>().apply { existing?.pointSequence?.let { addAll(it) } } }
    var toAdd by remember { mutableStateOf<PointResult?>(null) }
    FormDialog(if (existing == null) "Nueva línea" else "Editar línea", error = error, onDismiss = onDismiss, onConfirm = { onConfirm(name, ids.toList(), closed) }) {
        TextFieldRow("Nombre de la línea", name, { name = it })
        Dropdown("Añadir punto al final", points, toAdd, { it.point.code }, { p -> if (p != null) ids.add(p.point.id); toAdd = null })
        ids.forEachIndexed { i, id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${points.firstOrNull { it.point.id == id }?.point?.code ?: "?"}", Modifier.weight(1f))
                TextButton(onClick = { if (i > 0) { ids.removeAt(i); ids.add(i - 1, id) } }, enabled = i > 0) { Text("↑") }
                TextButton(onClick = { if (i < ids.size - 1) { ids.removeAt(i); ids.add(i + 1, id) } }, enabled = i < ids.size - 1) { Text("↓") }
                TextButton(onClick = { ids.removeAt(i) }) { Text("✕", color = MaterialTheme.colorScheme.error) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = closed, onCheckedChange = { closed = it }); Text("Cerrar la línea (unir el último punto con el primero)") }
    }
}
