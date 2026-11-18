package com.toposcribe.app.ui.screens.planimetria

import com.toposcribe.app.ui.components.isInvalidNumber
import com.toposcribe.app.ui.components.toDecimalOrNull
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toposcribe.app.data.repository.TraverseAngularClosure
import com.toposcribe.app.data.repository.TraverseComputeResult
import com.toposcribe.app.data.repository.TraverseLinearClosure
import com.toposcribe.app.ui.theme.MonoFieldStyle
import com.toposcribe.calc.TraverseType
import com.toposcribe.calc.decimalToDms
import com.toposcribe.calc.formatDms
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.TraverseEntity
import kotlin.math.atan2
import kotlin.math.hypot

@Composable
fun PlanimetriaScreen(modifier: Modifier = Modifier, viewModel: PlanimetriaViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val radiacionResult by viewModel.radiacionResult.collectAsState()
    val traverseResult by viewModel.traverseResult.collectAsState()
    val error by viewModel.error.collectAsState()
    val info by viewModel.info.collectAsState()

    var applyBowditch by remember { mutableStateOf(true) }

    LazyColumn(modifier = modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cálculo Planimétrico Riguroso Paso a Paso", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Demostración numérica de proyecciones ortogonales y compensación de Bowditch. Calcula coordenadas por radiación (azimut/rumbo + distancia) propagando desde los puntos de control y ya calculados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = viewModel::computeRadiacion) { Text("Calcular Planimetría (radiación)") }
                    radiacionResult?.let { result ->
                        Text("Puntos calculados en esta corrida: ${result.computedCount}", style = MaterialTheme.typography.bodyMedium)
                        result.warnings.forEach { w ->
                            Text("⚠ ${w.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        val result = traverseResult
        val codeById = state.points.associate { it.id to it.code }
        val rows = if (result != null) buildLegRows(result, codeById, applyBowditch) else emptyList()

        item { FormulasCard(result, rows.firstOrNull()) }

        item { NewTraverseCard(points = state.points, onCreate = viewModel::createTraverse) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Poligonales", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (state.traverses.isEmpty()) {
                        Text("Todavía no hay poligonales creadas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.traverses.forEach { t ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${t.name} (${traverseTypeLabel(t.type)})")
                            Button(onClick = { viewModel.computeTraverse(t.id) }) { Text("Calcular") }
                        }
                    }
                    if (info != null) Text(info ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    if (error != null) Text(error ?: "", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        item { ClosureMetricsCard(result?.angularClosure, result?.linearClosure, calculated = result != null) }

        if (result != null) {
            item { BowditchTable(rows, applyBowditch, bowditchAvailable = result.stations.any { it.adjustedEast != null }) { applyBowditch = it } }
        }
    }
}

@Composable
private fun FormulasCard(result: TraverseComputeResult?, first: LegRow?) {
    val proj = if (first != null) {
        "ΔE = %.3f · sin(%s) = %+.3f m   |   ΔN = %.3f · cos(%s) = %+.3f m".format(
            first.distance, formatDms(decimalToDms(first.azimuth)), first.dE, first.distance, formatDms(decimalToDms(first.azimuth)), first.dN,
        )
    } else {
        "Ej: ΔE = 10.000 · sin(90°) = +10.000 m"
    }
    val coord = if (first != null && result != null) {
        "E = %.3f + %+.3f = %.3f m   |   N = %.3f + %+.3f = %.3f m".format(
            result.startEast, first.dE, result.startEast + first.dE, result.startNorth, first.dN, result.startNorth + first.dN,
        )
    } else {
        "E = 500.000 + 10.000 = 510.000 m"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fórmulas topográficas aplicadas con valores sustituidos", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            FormulaBlock("1. Reducción de distancia", "d = D_inc · sin(Z)", "Ej: 100.000 · sin(90°) = 100.000 m")
            FormulaBlock("2. Proyecciones ortogonales", "ΔE = d · sin(Az)  |  ΔN = d · cos(Az)", proj + if (first != null) "  (primer tramo: ${first.code})" else "")
            FormulaBlock("3. Coordenadas finales", "E = E_orig + ΔE  |  N = N_orig + ΔN", coord)
        }
    }
}

@Composable
private fun FormulaBlock(title: String, formula: String, substituted: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        Text(formula, style = MonoFieldStyle)
        Text(substituted, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NewTraverseCard(points: List<PointEntity>, onCreate: (String, TraverseType, Long?, Long?, Double?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TraverseType.CERRADA_POLIGONO) }
    var startPoint by remember { mutableStateOf<PointEntity?>(null) }
    var endPoint by remember { mutableStateOf<PointEntity?>(null) }
    var startAzimuth by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nueva poligonal", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
            TraverseTypeDropdown(selected = type, onSelect = { type = it })
            PointDropdown("Punto de partida", points, startPoint) { startPoint = it }
            if (type == TraverseType.ABIERTA_AMARRADA) {
                PointDropdown("Punto de cierre (amarre)", points, endPoint) { endPoint = it }
            }
            OutlinedTextField(
                value = startAzimuth, onValueChange = { startAzimuth = it }, label = { Text("Azimut de arranque (°)") }, textStyle = MonoFieldStyle,
                singleLine = true, isError = startAzimuth.isInvalidNumber(), modifier = Modifier.fillMaxWidth(),
            )
            var formError by remember { mutableStateOf<String?>(null) }
            if (formError != null) Text(formError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val azimuth = startAzimuth.toDecimalOrNull()
                formError = when {
                    startPoint == null -> "Elige el punto de partida."
                    type == TraverseType.ABIERTA_AMARRADA && endPoint == null -> "Elige el punto de cierre (amarre)."
                    azimuth == null -> "Indica el azimut de arranque (número, con coma o punto)."
                    azimuth < 0.0 || azimuth >= 360.0 -> "El azimut debe estar entre 0° y 360°."
                    else -> null
                }
                if (formError == null) {
                    onCreate(name, type, startPoint?.id, endPoint?.id, azimuth)
                    name = ""; startAzimuth = ""
                }
            }) { Text("Crear poligonal") }
        }
    }
}

/** Las 5 métricas de cierre del diseño (P, ex, ey, eL, precisión relativa); "--" mientras no se calcule. */
@Composable
private fun ClosureMetricsCard(angular: TraverseAngularClosure?, linear: TraverseLinearClosure?, calculated: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Métricas de cierre", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Perímetro Total (P)", linear?.let { "${fmt(it.perimeter)} m" } ?: "-- m")
                Metric("Error en X (ex)", linear?.let { "${fmt(it.fE)} m" } ?: "-- m")
                Metric("Error en Y (ey)", linear?.let { "${fmt(it.fN)} m" } ?: "-- m")
                Metric("Error Lineal (eL)", linear?.let { "${fmt(it.fL)} m" } ?: "-- m")
                Metric("Precisión Relativa", linear?.relativePrecisionLabel ?: "--", MaterialTheme.colorScheme.secondary)
            }
            if (angular != null) {
                Text("Cierre angular: ${fmt(angular.misclosureDeg, 5)}°  (corrección/ángulo: ${fmt(angular.correctionPerAngleDeg, 5)}°)", style = MonoFieldStyle)
            }
            if (calculated && linear == null) {
                Text("No se pudo calcular el cierre lineal: falta un punto de cierre conocido.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoFieldStyle, color = valueColor)
    }
}

/** Fila de la tabla comparativa, derivada de las coordenadas de estación (no se recalcula nada nuevo). */
private data class LegRow(
    val code: String,
    val distance: Double,
    val azimuth: Double,
    val dE: Double,
    val corrE: Double?,
    val dEc: Double?,
    val dN: Double,
    val corrN: Double?,
    val dNc: Double?,
    val east: Double,
    val north: Double,
)

private fun buildLegRows(result: TraverseComputeResult, codeById: Map<Long, String>, applyBowditch: Boolean): List<LegRow> {
    var prevRawE = result.startEast
    var prevRawN = result.startNorth
    var prevAdjE = result.startEast
    var prevAdjN = result.startNorth
    return result.stations.map { s ->
        val dE = s.rawEast - prevRawE
        val dN = s.rawNorth - prevRawN
        var azimuth = Math.toDegrees(atan2(dE, dN))
        if (azimuth < 0) azimuth += 360.0

        val adjE = s.adjustedEast
        val adjN = s.adjustedNorth
        val useAdjusted = applyBowditch && adjE != null && adjN != null
        val dEc = if (useAdjusted) adjE!! - prevAdjE else null
        val dNc = if (useAdjusted) adjN!! - prevAdjN else null

        val row = LegRow(
            code = codeById[s.pointId] ?: "?",
            distance = hypot(dE, dN),
            azimuth = azimuth,
            dE = dE,
            corrE = dEc?.let { it - dE },
            dEc = dEc,
            dN = dN,
            corrN = dNc?.let { it - dN },
            dNc = dNc,
            east = if (useAdjusted) adjE!! else s.rawEast,
            north = if (useAdjusted) adjN!! else s.rawNorth,
        )
        prevRawE = s.rawEast
        prevRawN = s.rawNorth
        prevAdjE = adjE ?: s.rawEast
        prevAdjN = adjN ?: s.rawNorth
        row
    }
}

@Composable
private fun BowditchTable(rows: List<LegRow>, applyBowditch: Boolean, bowditchAvailable: Boolean, onToggle: (Boolean) -> Unit) {
    val columns = listOf(
        "Tramo" to 70.dp, "d Horiz [m]" to 90.dp, "Azimut" to 110.dp,
        "ΔE orig" to 90.dp, "Corr E" to 80.dp, "ΔE corr" to 90.dp,
        "ΔN orig" to 90.dp, "Corr N" to 80.dp, "ΔN corr" to 90.dp,
        "Este (X)" to 100.dp, "Norte (Y)" to 100.dp, "Punto" to 70.dp,
    )
    val w = columns.map { it.second }
    Card(Modifier.fillMaxWidth()) {
        val scroll = rememberScrollState()
        Column(Modifier.padding(12.dp)) {
            Text("Tabla Comparativa: Original vs Compensado Bowditch", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = applyBowditch, onCheckedChange = onToggle)
                Text("Aplicar Bowditch", style = MaterialTheme.typography.bodySmall)
            }
            if (applyBowditch && !bowditchAvailable) {
                Text(
                    "Bowditch no es aplicable a esta poligonal (sin cierre conocido): se muestran las coordenadas brutas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Row(Modifier.horizontalScroll(scroll)) {
                columns.forEach { (title, width) -> HeaderCell(title, width) }
            }
            rows.forEachIndexed { index, r ->
                Row(Modifier.horizontalScroll(scroll)) {
                    Cell("T${index + 1}", w[0], mono = true)
                    Cell(fmt(r.distance), w[1], mono = true)
                    Cell(formatDms(decimalToDms(r.azimuth)), w[2], mono = true)
                    Cell(fmt(r.dE), w[3], mono = true)
                    Cell(r.corrE?.let { "%+.3f".format(it) } ?: "—", w[4], mono = true)
                    Cell(r.dEc?.let { fmt(it) } ?: "—", w[5], mono = true)
                    Cell(fmt(r.dN), w[6], mono = true)
                    Cell(r.corrN?.let { "%+.3f".format(it) } ?: "—", w[7], mono = true)
                    Cell(r.dNc?.let { fmt(it) } ?: "—", w[8], mono = true)
                    Cell(fmt(r.east), w[9], mono = true)
                    Cell(fmt(r.north), w[10], mono = true)
                    Cell(r.code, w[11], mono = true)
                }
            }
        }
    }
}

private fun traverseTypeLabel(type: TraverseType): String = when (type) {
    TraverseType.CERRADA_POLIGONO -> "Cerrada"
    TraverseType.ABIERTA_AMARRADA -> "Abierta amarrada"
    TraverseType.ABIERTA_LIBRE -> "Abierta libre"
}

@Composable
private fun TraverseTypeDropdown(selected: TraverseType, onSelect: (TraverseType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(value = traverseTypeLabel(selected), onValueChange = {}, readOnly = true, label = { Text("Tipo de poligonal") }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TraverseType.entries.forEach { t ->
                DropdownMenuItem(text = { Text(traverseTypeLabel(t)) }, onClick = { onSelect(t); expanded = false })
            }
        }
    }
}

@Composable
private fun PointDropdown(label: String, points: List<PointEntity>, selected: PointEntity?, onSelect: (PointEntity) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(value = selected?.code ?: "Seleccione...", onValueChange = {}, readOnly = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            points.forEach { p -> DropdownMenuItem(text = { Text(p.code) }, onClick = { onSelect(p); expanded = false }) }
        }
    }
}

private fun fmt(value: Double, digits: Int = 3): String = "%.${digits}f".format(value)

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Text(text, modifier = Modifier.width(width).padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Cell(text: String, width: Dp, mono: Boolean = false) {
    Text(text, modifier = Modifier.width(width).padding(4.dp), style = if (mono) MonoFieldStyle else MaterialTheme.typography.bodySmall)
}
