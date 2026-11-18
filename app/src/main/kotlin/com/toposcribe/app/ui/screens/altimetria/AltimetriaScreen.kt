package com.toposcribe.app.ui.screens.altimetria

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toposcribe.app.ui.components.EditableCell
import com.toposcribe.app.ui.components.parseNumberOr
import com.toposcribe.app.ui.theme.MonoFieldStyle
import com.toposcribe.calc.ReadingType
import com.toposcribe.data.local.entity.LevelingRunEntity
import com.toposcribe.data.local.entity.PointEntity

@Composable
fun AltimetriaScreen(modifier: Modifier = Modifier, viewModel: AltimetriaViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val result by viewModel.levelingResult.collectAsState()
    val trigResult by viewModel.trigResult.collectAsState()
    val error by viewModel.error.collectAsState()

    var editing by remember { mutableStateOf<com.toposcribe.data.local.entity.LevelingObservationEntity?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    editing?.let { o ->
        com.toposcribe.app.ui.components.EditFieldsDialog(
            title = "Editar lectura",
            labels = listOf("Tipo de lectura (BS, IS o FS)", "Lectura de mira [m]", "Cambio de estación (Sí / No)"),
            initial = listOf(o.readingType.name, o.readingValue.toString(), if (o.isTurningPoint) "Sí" else "No"),
            numeric = setOf(1),
            error = editError,
            onDismiss = { editing = null; editError = null },
            onConfirm = { v ->
                val type = ReadingType.entries.find { it.name.equals(v[0].trim(), ignoreCase = true) }
                val value = v[1].trim().replace(',', '.').toDecimalOrNull()
                when {
                    type == null -> editError = "El tipo debe ser BS, IS o FS."
                    value == null -> editError = "La lectura debe ser un número."
                    else -> {
                        viewModel.updateObservation(o, type, value, v[2].trim().lowercase() in setOf("sí", "si", "s", "yes", "true"))
                        editing = null
                        editError = null
                    }
                }
            },
        )
    }

    LazyColumn(modifier = modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SummaryCard(state.observations, result) }
        item { NewRunCard(points = state.points, onCreate = viewModel::createRun) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Nivelaciones", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    state.runs.forEach { run ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(run.name)
                            Row {
                                OutlinedButton(onClick = { viewModel.selectRun(run.id) }) { Text("Ver") }
                                Button(onClick = { viewModel.selectRun(run.id); viewModel.computeRun(run.id) }, modifier = Modifier.padding(start = 6.dp)) { Text("Calcular") }
                            }
                        }
                    }
                    if (error != null) Text(error ?: "", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        state.runs.find { it.id == state.activeRunId }?.let { activeRun ->
            item { AddObservationCard(runName = activeRun.name, points = state.points, onAdd = viewModel::addObservation) }
        }

        item { ObservationsTable(points = state.points, observations = state.observations, result = result, onDelete = viewModel::deleteObservation, onEdit = { editing = it }, onValueChange = { o, v -> viewModel.updateObservation(o, o.readingType, v, o.isTurningPoint) }) }

        result?.let { r ->
            item { CheckCard(r) }
        }

        item { TrigCard(points = state.points, trigResult = trigResult, onAdd = viewModel::addTrigObservation, onCompute = viewModel::computeTrig) }
    }
}

@Composable
private fun NewRunCard(points: List<PointEntity>, onCreate: (String, Long, Long?, Double?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var startBm by remember { mutableStateOf<PointEntity?>(null) }
    var endBm by remember { mutableStateOf<PointEntity?>(null) }
    var endKnownElevation by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nueva nivelación geométrica", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
            PointDropdown("BM inicial (cota conocida)", points, startBm) { startBm = it }
            PointDropdown("BM de cierre (opcional)", points, endBm) { endBm = it }
            OutlinedTextField(
                value = endKnownElevation, onValueChange = { endKnownElevation = it }, label = { Text("Cota de cierre conocida (opcional)") }, textStyle = MonoFieldStyle,
                singleLine = true, isError = endKnownElevation.isInvalidNumber(), modifier = Modifier.fillMaxWidth(),
            )
            var formError by remember { mutableStateOf<String?>(null) }
            if (formError != null) Text(formError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val bm = startBm
                formError = when {
                    bm == null -> "Elige el BM inicial (debe tener cota conocida)."
                    endKnownElevation.isInvalidNumber() -> "La cota de cierre debe ser un número."
                    else -> null
                }
                if (formError == null) {
                    onCreate(name, bm!!.id, endBm?.id, endKnownElevation.toDecimalOrNull())
                    name = ""
                    endKnownElevation = ""
                }
            }) { Text("Crear nivelación") }
        }
    }
}

@Composable
private fun AddObservationCard(runName: String, points: List<PointEntity>, onAdd: (Long, ReadingType, Double, Boolean) -> Unit) {
    var point by remember { mutableStateOf<PointEntity?>(null) }
    var readingType by remember { mutableStateOf(ReadingType.BS) }
    var readingValue by remember { mutableStateOf("") }
    var isTurningPoint by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("+ Fila Nivelación — $runName", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            PointDropdown("Punto", points, point) { point = it }
            ReadingTypeDropdown(readingType) { readingType = it }
            OutlinedTextField(
                value = readingValue, onValueChange = { readingValue = it }, label = { Text("Lectura de mira [m]") }, textStyle = MonoFieldStyle,
                singleLine = true, isError = readingValue.isInvalidNumber(), modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = isTurningPoint, onCheckedChange = { isTurningPoint = it })
                Text("Cambio de estación")
            }
            if (formError != null) Text(formError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val p = point
                val value = readingValue.toDecimalOrNull()
                formError = when {
                    p == null -> "Elige el punto de la lectura."
                    value == null -> "La lectura debe ser un número (usa coma o punto decimal)."
                    value <= 0.0 -> "La lectura de mira debe ser mayor que 0."
                    else -> null
                }
                if (formError == null) {
                    onAdd(p!!.id, readingType, value!!, isTurningPoint)
                    readingValue = ""
                    isTurningPoint = false
                }
            }) { Text("Agregar lectura") }
        }
    }
}

/** Comprobación aritmética ΣVA − ΣVD vs ΔCota; antes de calcular solo muestra las sumas de la libreta. */
@Composable
private fun SummaryCard(observations: List<com.toposcribe.data.local.entity.LevelingObservationEntity>, result: com.toposcribe.app.data.repository.LevelingComputeResult?) {
    val sumVa = result?.sumBacksight ?: observations.filter { it.readingType == ReadingType.BS }.sumOf { it.readingValue }
    val sumVd = result?.sumForesight ?: observations.filter { it.readingType == ReadingType.FS }.sumOf { it.readingValue }
    val deltaCota = result?.let { it.finalElevation - it.startElevation }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nivelación Geométrica y Trigonométrica Rigurosa", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Método del Nivel: AI = Cota + VA y Cota = AI − VD, con comprobación matemática obligatoria.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SummaryMetric("Σ Vista Atrás (Σ VA)", "%.3f m".format(sumVa))
                SummaryMetric("Σ Vista Adelante (Σ VD)", "%.3f m".format(sumVd))
                SummaryMetric("Δ Cota (Final − Inicial)", deltaCota?.let { "%.3f m".format(it) } ?: "-- m")
                SummaryMetric(
                    "Comprobación",
                    when {
                        result == null -> "Sin calcular"
                        result.arithmeticCheckOk -> "Libreta cuadrada"
                        else -> "NO cuadra"
                    },
                    if (result != null && !result.arithmeticCheckOk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoFieldStyle, color = valueColor)
    }
}

@Composable
private fun ObservationsTable(
    points: List<PointEntity>,
    observations: List<com.toposcribe.data.local.entity.LevelingObservationEntity>,
    result: com.toposcribe.app.data.repository.LevelingComputeResult?,
    onDelete: (Long) -> Unit,
    onEdit: (com.toposcribe.data.local.entity.LevelingObservationEntity) -> Unit,
    onValueChange: (com.toposcribe.data.local.entity.LevelingObservationEntity, Double) -> Unit,
) {
    val codeById = points.associate { it.id to it.code }
    val rowByObservation = result?.rows?.associateBy { it.observationId } ?: emptyMap()
    val columns = listOf(
        "Orden" to 60.dp, "Punto Visado" to 100.dp, "V. Atrás (+) [m]" to 110.dp, "V. Intermedia [m]" to 120.dp,
        "V. Adelante (−) [m]" to 130.dp, "Alt. Inst. (AI)" to 100.dp, "Cota [m]" to 90.dp, "Método Origen" to 130.dp,
        "Cambio" to 70.dp, "Acción" to 200.dp,
    )
    val w = columns.map { it.second }
    Card(Modifier.fillMaxWidth()) {
        val scroll = rememberScrollState()
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.horizontalScroll(scroll)) {
                columns.forEach { (title, width) -> HeaderCell(title, width) }
            }
            if (observations.isEmpty()) {
                Text("Todavía no hay lecturas registradas.", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            observations.forEach { obs ->
                val computed = rowByObservation[obs.id]
                val reading = "%.3f".format(obs.readingValue)
                Row(Modifier.horizontalScroll(scroll)) {
                    Cell(obs.sequenceOrder.toString(), w[0], mono = true)
                    Cell(codeById[obs.pointId] ?: "?", w[1], mono = true)
                    // La lectura se edita en la columna de su tipo; las otras dos quedan vacías.
                    listOf(ReadingType.BS to w[2], ReadingType.IS to w[3], ReadingType.FS to w[4]).forEach { (type, width) ->
                        if (obs.readingType == type) {
                            EditableCell(reading, width, mono = true) { text ->
                                text.parseNumberOr(obs.readingValue)?.let { onValueChange(obs, it) }
                            }
                        } else {
                            Cell("—", width, mono = true)
                        }
                    }
                    Cell(computed?.let { "%.3f".format(it.heightOfInstrument) } ?: "—", w[5], mono = true)
                    Cell(computed?.let { "%.3f".format(it.elevation) } ?: "—", w[6], mono = true)
                    Cell(if (computed != null) "Nivelación geométrica" else "Sin calcular", w[7])
                    Cell(if (obs.isTurningPoint) "Sí" else "No", w[8])
                    Row(Modifier.width(w[9]).padding(2.dp)) {
                        androidx.compose.material3.TextButton(onClick = { onEdit(obs) }) { Text("Editar") }
                        OutlinedButton(onClick = { onDelete(obs.id) }) { Text("Eliminar") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckCard(result: com.toposcribe.app.data.repository.LevelingComputeResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Comprobación de la libreta", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text("ΣBS=${"%.3f".format(result.sumBacksight)}  ΣFS=${"%.3f".format(result.sumForesight)}  ΣBS-ΣFS=${"%.3f".format(result.checkValue)}", style = MonoFieldStyle)
            Text(
                if (result.arithmeticCheckOk) "Comprobación aritmética conforme." else "Comprobación aritmética NO conforme: revisar la libreta.",
                color = if (result.arithmeticCheckOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text("Cierre de campo", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            if (result.fieldMisclosure != null) {
                Text("Cota de cierre conocida: ${"%.3f".format(result.knownEndElevation ?: 0.0)} — cota calculada: ${"%.3f".format(result.finalElevation)} — error: ${"%.3f".format(result.fieldMisclosure)}", style = MonoFieldStyle)
            } else {
                Text("No se calculó cierre de campo: esta nivelación no tiene una cota de cierre conocida independiente.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TrigCard(
    points: List<PointEntity>,
    trigResult: com.toposcribe.app.data.repository.PlanimetryComputeResult?,
    onAdd: (Long, Long, Double?, Double?, Double?, Double?, Double, Double) -> Unit,
    onCompute: () -> Unit,
) {
    var occupied by remember { mutableStateOf<PointEntity?>(null) }
    var target by remember { mutableStateOf<PointEntity?>(null) }
    var slopeDistance by remember { mutableStateOf("") }
    var zenithAngle by remember { mutableStateOf("") }
    var instrumentHeight by remember { mutableStateOf("") }
    var targetHeight by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nivelación trigonométrica", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            PointDropdown("Estación", points, occupied) { occupied = it }
            PointDropdown("Punto visado", points, target) { target = it }
            // Los campos se reparten el ancho de la pantalla (antes tenían ancho fijo y se cortaban en teléfonos estrechos).
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = slopeDistance, onValueChange = { slopeDistance = it }, label = { Text("Dist. inclinada") }, textStyle = MonoFieldStyle, singleLine = true, isError = slopeDistance.isInvalidNumber(), modifier = Modifier.weight(1f))
                OutlinedTextField(value = zenithAngle, onValueChange = { zenithAngle = it }, label = { Text("Cenital (°)") }, textStyle = MonoFieldStyle, singleLine = true, isError = zenithAngle.isInvalidNumber(), modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = instrumentHeight, onValueChange = { instrumentHeight = it }, label = { Text("Alt. instrumento") }, textStyle = MonoFieldStyle, singleLine = true, isError = instrumentHeight.isInvalidNumber(), modifier = Modifier.weight(1f))
                OutlinedTextField(value = targetHeight, onValueChange = { targetHeight = it }, label = { Text("Alt. prisma") }, textStyle = MonoFieldStyle, singleLine = true, isError = targetHeight.isInvalidNumber(), modifier = Modifier.weight(1f))
            }
            var formError by remember { mutableStateOf<String?>(null) }
            if (formError != null) Text(formError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val o = occupied
                val t = target
                val sd = slopeDistance.toDecimalOrNull()
                val zen = zenithAngle.toDecimalOrNull()
                val hi = instrumentHeight.toDecimalOrNull()
                val ht = targetHeight.toDecimalOrNull()
                formError = when {
                    o == null || t == null -> "Elige la estación y el punto visado."
                    o.id == t.id -> "La estación y el punto visado no pueden ser el mismo."
                    sd == null || zen == null -> "Indica la distancia inclinada y el ángulo cenital (números, con coma o punto)."
                    hi == null || ht == null -> "Indica la altura del instrumento y la del prisma."
                    zen < 0.0 || zen > 180.0 -> "El cenital debe estar entre 0° y 180°."
                    sd <= 0.0 -> "La distancia debe ser mayor que 0."
                    else -> null
                }
                if (formError == null) {
                    onAdd(o!!.id, t!!.id, sd, null, zen, null, hi!!, ht!!)
                    slopeDistance = ""; zenithAngle = ""
                }
            }) { Text("Agregar observación") }
            Button(onClick = onCompute) { Text("Calcular nivelación trigonométrica") }
            trigResult?.let { r ->
                Text("Puntos calculados: ${r.computedCount}")
                r.warnings.forEach { w -> Text("⚠ ${w.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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

@Composable
private fun ReadingTypeDropdown(selected: ReadingType, onSelect: (ReadingType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(value = selected.name, onValueChange = {}, readOnly = true, label = { Text("Tipo de lectura") }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReadingType.entries.forEach { t -> DropdownMenuItem(text = { Text(t.name) }, onClick = { onSelect(t); expanded = false }) }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Text(text, modifier = Modifier.width(width).padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Cell(text: String, width: Dp, mono: Boolean = false) {
    Text(text, modifier = Modifier.width(width).padding(4.dp), style = if (mono) MonoFieldStyle else MaterialTheme.typography.bodySmall)
}
