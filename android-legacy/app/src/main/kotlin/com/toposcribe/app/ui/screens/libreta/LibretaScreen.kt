package com.toposcribe.app.ui.screens.libreta

import com.toposcribe.app.ui.components.SelectAllTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toposcribe.app.ui.components.EMPTY_CELL
import com.toposcribe.app.ui.components.EditableCell
import com.toposcribe.app.ui.components.parseNumberOr
import com.toposcribe.app.ui.theme.MonoFieldStyle
import com.toposcribe.calc.ReadingType
import com.toposcribe.calc.decimalToDms
import com.toposcribe.calc.formatDms
import com.toposcribe.data.local.entity.DifferentialCorrectionType
import com.toposcribe.data.local.entity.FixType
import com.toposcribe.data.local.entity.GnssSolutionStatus
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.StationSetupEntity
import com.toposcribe.io.buildCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val READING_LABELS = mapOf(ReadingType.BS to "VA", ReadingType.IS to "VI", ReadingType.FS to "VD")

@Composable
fun LibretaScreen(modifier: Modifier = Modifier, viewModel: LibretaViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Estado de interfaz que debe sobrevivir a cambios de pestaña y rotación.
    var query by rememberSaveable { mutableStateOf("") }
    var quickKey by rememberSaveable { mutableStateOf(0) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var editingObsId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingLevelId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingGnssId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmNew by rememberSaveable { mutableStateOf(false) }
    var photoTargetPointId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }

    val isLevel = state.mode == LibretaMode.NIVEL
    val isGnss = state.mode == LibretaMode.GNSS
    val isTheodolite = state.mode == LibretaMode.TEODOLITO
    val choosePhoto = rememberPhotoChooser("pt") { path -> photoTargetPointId?.let { viewModel.setPointPhoto(it, path) } }

    val csvImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            when {
                isLevel -> viewModel.importLevelCsv(text)
                isGnss -> viewModel.importGnssCsv(text)
                else -> viewModel.importCsv(text)
            }
        }
    }
    val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val csv = buildFieldBookCsv(state)
            scope.launch {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } }
            }
        }
    }

    if (state.isLoading) {
        Text("Cargando libreta…", modifier = modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val codeById = state.points.associate { it.id to it.code }
    val existingCodes = state.points.map { it.code }

    // ---------------- Diálogos ----------------
    if (showAdd) {
        if (isGnss) {
            val lastGnss = state.gnssPoints.lastOrNull()
            GnssPointDialog(
                title = "+ Añadir punto (GPS diferencial)",
                existingCodes = existingCodes,
                defaultCode = suggestNextPointCode(existingCodes),
                defaultFix = gnssDefaultFix(state),
                defaultAntenna = lastGnss?.let { decodeGnssExtra(it.observations).antennaHeight }?.let { "%.3f".format(it) } ?: "",
                initial = null,
                withPhoto = true,
                serverError = dialogError,
                onDismiss = { showAdd = false; dialogError = null },
                onConfirm = { form ->
                    viewModel.addGnssPoint(form) { err ->
                        dialogError = err
                        if (err == null) showAdd = false
                    }
                },
            )
        } else if (isLevel) {
            val lastReading = state.levelRows.lastOrNull()?.reading
            AddLevelReadingDialog(
                existingCodes = existingCodes,
                defaultCode = if (lastReading == null) state.levelReferenceCode ?: "" else suggestNextPointCode(existingCodes),
                defaultType = if (lastReading == null) ReadingType.BS else ReadingType.IS,
                serverError = dialogError,
                onDismiss = { showAdd = false; dialogError = null },
                onConfirm = { code, type, value, turning, description, photo, caption ->
                    viewModel.addLevelReading(code, type, value, turning, description, photo, caption) { err ->
                        dialogError = err
                        if (err == null) showAdd = false
                    }
                },
            )
        } else {
            val lastObs = state.observations.lastOrNull()
            AddStationPointDialog(
                existingCodes = existingCodes,
                defaultCode = suggestNextPointCode(existingCodes),
                defaultStation = lastObs?.let { codeById[it.targetPointId] } ?: state.latestSetup?.let { codeById[it.occupiedPointId] } ?: "",
                defaultAi = state.latestSetup?.instrumentHeightAi?.let { "%.3f".format(it) } ?: "",
                theodolite = isTheodolite,
                serverError = dialogError,
                onDismiss = { showAdd = false; dialogError = null },
                onConfirm = { form ->
                    viewModel.addStationPoint(form) { err ->
                        dialogError = err
                        if (err == null) showAdd = false
                    }
                },
            )
        }
    }

    state.observations.find { it.id == editingObsId }?.let { obs ->
        EditStationObservationDialog(
            obs = obs,
            targetCode = codeById[obs.targetPointId] ?: "",
            serverError = dialogError,
            onDismiss = { editingObsId = null; dialogError = null },
            onConfirm = { code, tramo, az, bearing, ha, hd, sd, zen, ver, ai, ap, desc ->
                viewModel.updateObservation(obs, tramo, az, bearing, ha, hd, sd, zen, ver, ai, ap, desc, code) { err ->
                    dialogError = err
                    if (err == null) editingObsId = null
                }
            },
        )
    }

    state.levelRows.find { it.reading.id == editingLevelId }?.let { row ->
        EditLevelReadingDialog(
            reading = row.reading,
            pointCode = codeById[row.reading.pointId] ?: "",
            existingCodes = existingCodes,
            serverError = dialogError,
            onDismiss = { editingLevelId = null; dialogError = null },
            onConfirm = { code, type, value, turning, notes ->
                viewModel.updateLevelReading(row.reading, code, type, value, turning, notes) { err ->
                    dialogError = err
                    if (err == null) editingLevelId = null
                }
            },
        )
    }

    state.gnssPoints.find { it.id == editingGnssId }?.let { point ->
        val extra = decodeGnssExtra(point.observations)
        GnssPointDialog(
            title = "Editar punto ${point.code}",
            existingCodes = existingCodes,
            defaultCode = point.code,
            defaultFix = point.fixType ?: FixType.FIXED,
            defaultAntenna = "",
            initial = GnssInitial(
                code = point.code, tramo = point.tramoSector ?: "", description = point.description ?: "", fix = point.fixType ?: FixType.FIXED,
                east = point.fixedEast?.toString() ?: "", north = point.fixedNorth?.toString() ?: "", elevation = point.fixedElevation?.toString() ?: "",
                pdop = extra.pdop?.toString() ?: "", satellites = extra.satellites?.toString() ?: "", antennaHeight = extra.antennaHeight?.toString() ?: "",
                caption = point.photoCaption ?: "",
            ),
            withPhoto = false,
            serverError = dialogError,
            onDismiss = { editingGnssId = null; dialogError = null },
            onConfirm = { form ->
                viewModel.updateGnssPoint(point, form) { err ->
                    dialogError = err
                    if (err == null) editingGnssId = null
                }
            },
        )
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("Nuevo vacío") },
            text = {
                Text(
                    "Se creará una libreta (proyecto) nueva y vacía para empezar otros datos, con el mismo tipo de instrumento. " +
                        "La libreta actual no se borra: queda guardada en el teléfono, pero la app no ofrece volver a abrirla. ¿Continuar?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmNew = false
                    viewModel.newBlankProject { quickKey++; query = "" }
                }) { Text("Nuevo vacío") }
            },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Cancelar") } },
        )
    }

    if (confirmClear) {
        val count = when {
            isLevel -> state.levelRows.size
            isGnss -> state.gnssPoints.size
            else -> state.observations.size
        }
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Limpiar libreta") },
            text = { Text("Se eliminarán las $count anotaciones de la libreta del proyecto activo. ¿Continuar?") },
            confirmButton = { TextButton(onClick = { viewModel.clearFieldBook(); confirmClear = false }) { Text("Limpiar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }

    // ---------------- Contenido ----------------
    LazyColumn(modifier = modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { InstrumentSelectorCard(state.mode, viewModel::setMode) }

        item {
            if (isLevel) LevelReferenceCard(state, viewModel)
            else if (isGnss) GnssInfoCard(state)
            else StationSetupCard(state.project?.id, state.points, state.latestSetup, viewModel::saveStationSetup)
        }

        item {
            FieldBookActions(
                count = when {
                    isLevel -> state.levelRows.size
                    isGnss -> state.gnssPoints.size
                    else -> state.observations.size
                },
                unitLabel = when {
                    isLevel -> "LECTURAS"
                    isGnss -> "PUNTOS"
                    else -> "OBS"
                },
                onNewBlank = { confirmNew = true },
                onAddPoint = { dialogError = null; showAdd = true },
                onQuickRow = { quickKey++ },
                onImportCsv = { csvImport.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel")) },
                onExportCsv = {
                    csvExport.launch(
                        when (state.mode) {
                            LibretaMode.NIVEL -> "libreta_nivel.csv"
                            LibretaMode.GNSS -> "libreta_gnss.csv"
                            LibretaMode.TEODOLITO -> "libreta_teodolito.csv"
                            LibretaMode.ESTACION_TOTAL -> "libreta_estacion_total.csv"
                        },
                    )
                },
                onClear = { confirmClear = true },
            )
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Buscar punto, tramo o descripción") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Column {
                if (error != null) Text(error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 6.dp))
                if (message != null) Text(message ?: "", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(bottom = 6.dp))
            }
        }

        item {
            if (isLevel) {
                QuickLevelCard(quickKey, state.project?.id) { code, type, value, turning, onResult ->
                    viewModel.addLevelReading(code, type, value, turning, "", null, "", onResult)
                }
            } else if (isGnss) {
                QuickGnssCard(quickKey, state.project?.id, existingCodes, gnssDefaultFix(state)) { form, onResult -> viewModel.addGnssPoint(form, onResult) }
            } else {
                QuickRowCard(
                    projectId = state.project?.id,
                    points = state.points,
                    lastTarget = state.observations.lastOrNull()?.let { last -> state.points.find { it.id == last.targetPointId } },
                    quickKey = quickKey,
                    onAdd = viewModel::addObservation,
                )
            }
        }

        item {
            if (isLevel) {
                LevelTable(
                    state = state,
                    query = query,
                    codeById = codeById,
                    onEdit = { dialogError = null; editingLevelId = it.reading.id },
                    onDuplicate = { viewModel.duplicateLevelReading(it.reading) },
                    onDelete = { viewModel.deleteLevelReading(it.reading) },
                    onValue = { row, value ->
                        viewModel.updateLevelReading(row.reading, codeById[row.reading.pointId] ?: "", row.reading.readingType, value, row.reading.isTurningPoint, row.reading.rawNotes ?: "")
                    },
                )
            } else if (isGnss) {
                GnssTable(
                    state = state,
                    query = query,
                    onEdit = { dialogError = null; editingGnssId = it.id },
                    onDuplicate = viewModel::duplicateGnssPoint,
                    onDelete = viewModel::deleteGnssPoint,
                    onPhoto = { p -> photoTargetPointId = p.id; choosePhoto() },
                    onUpdate = { p, east, north, elevation, desc ->
                        val extra = decodeGnssExtra(p.observations)
                        viewModel.updateGnssPoint(
                            p,
                            GnssPointForm(
                                code = p.code, tramo = p.tramoSector ?: "", description = desc, fix = p.fixType ?: FixType.FIXED,
                                east = east, north = north, elevation = elevation, pdop = extra.pdop, satellites = extra.satellites,
                                antennaHeight = extra.antennaHeight, photoPath = null, photoCaption = p.photoCaption ?: "",
                            ),
                        )
                    },
                )
            } else {
                ObservationsTable(
                    theodolite = isTheodolite,
                    state = state,
                    query = query,
                    codeById = codeById,
                    onEdit = { dialogError = null; editingObsId = it.id },
                    onDuplicate = viewModel::duplicateObservation,
                    onDelete = { viewModel.deleteObservation(it.id) },
                    onPhoto = { obs -> photoTargetPointId = obs.targetPointId; choosePhoto() },
                    onUpdate = { o, tramo, hd, sd, ai, ap, desc ->
                        viewModel.updateObservation(o, tramo, o.azimuth, o.bearing ?: "", o.horizontalAngle, hd, sd, o.zenithAngle, o.verticalAngle, ai, ap, desc)
                    },
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Tipo de instrumento
// -------------------------------------------------------------------------------------------

private fun modeLabel(mode: LibretaMode): String = when (mode) {
    LibretaMode.ESTACION_TOTAL -> "Estación total"
    LibretaMode.TEODOLITO -> "Teodolito"
    LibretaMode.NIVEL -> "Nivel de ingeniero"
    LibretaMode.GNSS -> "GPS diferencial (GNSS)"
}

private fun modeHelp(mode: LibretaMode): String = when (mode) {
    LibretaMode.ESTACION_TOTAL -> "Campos de la estación total: estación ocupada, punto visado, ángulo horizontal, ángulo vertical o cenital, distancia, Ai, Ap, código y observaciones."
    LibretaMode.TEODOLITO -> "Campos del teodolito: estación ocupada, punto visado, ángulo horizontal, ángulo vertical o cenital, distancia por estadía (hilos superior, medio e inferior) o con cinta, altura del instrumento y de la señal, código y observaciones."
    LibretaMode.NIVEL -> "Campos del nivel: punto o estación, vista atrás (VA), intermedia (VI), adelante (VD), cota de referencia, altura de instrumento y cota calculadas, cambio de estación."
    LibretaMode.GNSS -> "Campos del GPS diferencial: identificador, solución (RTK Fixed/Float, DGPS o autónomo), Este, Norte y cota medidos, PDOP, satélites, altura de antena, descripción y foto."
}

@Composable
private fun InstrumentSelectorCard(mode: LibretaMode, onSelect: (LibretaMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tipo de instrumento", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Box(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = modeLabel(mode),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Instrumento en uso") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().clickable { expanded = true })
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    LibretaMode.entries.forEach { m ->
                        DropdownMenuItem(text = { Text(modeLabel(m)) }, onClick = { onSelect(m); expanded = false })
                    }
                }
            }
            Text(modeHelp(mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// -------------------------------------------------------------------------------------------
// Estación total: estación ocupada y orientación (se guarda sola)
// -------------------------------------------------------------------------------------------

@Composable
private fun StationSetupCard(
    projectId: Long?,
    points: List<PointEntity>,
    latest: StationSetupEntity?,
    onSave: (Long, Double, String, Int, Int, Double) -> Unit,
) {
    var occupiedId by rememberSaveable(projectId) { mutableStateOf(latest?.occupiedPointId) }
    var ai by rememberSaveable(projectId) { mutableStateOf(latest?.instrumentHeightAi?.let { "%.3f".format(it) } ?: "1.550") }
    var backsight by rememberSaveable(projectId) { mutableStateOf(latest?.backsightPointLabel ?: "") }
    var deg by rememberSaveable(projectId) { mutableStateOf((latest?.baseAzimuthDeg ?: 0).toString()) }
    var min by rememberSaveable(projectId) { mutableStateOf((latest?.baseAzimuthMin ?: 0).toString()) }
    var sec by rememberSaveable(projectId) { mutableStateOf((latest?.baseAzimuthSec ?: 0.0).toString()) }
    val occupied = points.find { it.id == occupiedId }

    val aiValue = ai.replace(',', '.').toDoubleOrNull()
    val degValue = deg.toIntOrNull()
    val minValue = min.toIntOrNull()
    val secValue = sec.replace(',', '.').toDoubleOrNull()
    val valid = occupied != null && aiValue != null && aiValue >= 0 && degValue != null && degValue in 0..360 &&
        minValue != null && minValue in 0..59 && secValue != null && secValue >= 0 && secValue < 60

    // Guardado automático 0,8 s después de dejar de escribir, solo con datos válidos.
    LaunchedEffect(occupiedId, ai, backsight, deg, min, sec) {
        if (!valid) return@LaunchedEffect
        delay(800)
        onSave(occupiedId!!, aiValue!!, backsight, degValue!!, minValue!!, secValue!!)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Estación Ocupada y Referencia de Orientación (Vista Atrás)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            PointPicker(label = "Estación Ocupada", points = points, selected = occupied, onSelect = { occupiedId = it.id })
            SelectAllTextField(
                value = ai, onValueChange = { ai = it }, label = { Text("Altura Instrumento (Ai) [m]") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true,
                isError = aiValue == null || aiValue < 0, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = backsight, onValueChange = { backsight = it }, label = { Text("Punto Vista Atrás (orientación)") }, keyboardOptions = CODE_KEYBOARD, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Azimut base de calaje", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectAllTextField(value = deg, onValueChange = { deg = it }, label = { Text("°") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, isError = degValue == null || degValue !in 0..360, modifier = Modifier.weight(1f))
                SelectAllTextField(value = min, onValueChange = { min = it }, label = { Text("'") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, isError = minValue == null || minValue !in 0..59, modifier = Modifier.weight(1f))
                SelectAllTextField(value = sec, onValueChange = { sec = it }, label = { Text("\"") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, isError = secValue == null || secValue < 0 || secValue >= 60, modifier = Modifier.weight(1f))
            }
            Text(
                if (valid) "Se guarda automáticamente." else if (occupied == null) "Elige la estación ocupada para guardar." else "Revisa los valores marcados en rojo.",
                style = MaterialTheme.typography.bodySmall,
                color = if (valid) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// Nivel: cota de referencia
// -------------------------------------------------------------------------------------------

@Composable
private fun LevelReferenceCard(state: LibretaUiState, viewModel: LibretaViewModel) {
    var code by rememberSaveable(state.project?.id, state.levelReferenceCode) { mutableStateOf(state.levelReferenceCode ?: "") }
    var elevation by rememberSaveable(state.project?.id, state.levelReferenceElevation) { mutableStateOf(state.levelReferenceElevation?.let { "%.3f".format(it) } ?: "") }
    var localError by remember { mutableStateOf<String?>(null) }
    val known = state.points.filter { it.fixedElevation != null }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cota conocida o de referencia (BM)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                if (state.levelReferenceElevation != null) "Referencia actual: ${state.levelReferenceCode} = ${"%.3f".format(state.levelReferenceElevation)} m"
                else "Aún no hay referencia: define el BM de partida y su cota para calcular las cotas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (known.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text("Usar un BM existente") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        known.forEach { p ->
                            DropdownMenuItem(
                                text = { Text("${p.code} — ${"%.3f".format(p.fixedElevation)} m") },
                                onClick = { code = p.code; elevation = "%.3f".format(p.fixedElevation); expanded = false },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Código BM") }, keyboardOptions = CODE_KEYBOARD, singleLine = true, modifier = Modifier.weight(1f))
                SelectAllTextField(value = elevation, onValueChange = { elevation = it }, label = { Text("Cota [m]") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, modifier = Modifier.weight(1f))
            }
            if (localError != null) Text(localError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val value = elevation.trim().replace(',', '.').toDoubleOrNull()
                when {
                    code.isBlank() -> localError = "El código del BM es obligatorio."
                    value == null -> localError = "La cota debe ser un número."
                    else -> viewModel.setLevelReference(code.trim().uppercase(), value) { localError = it }
                }
            }) { Text("Establecer referencia") }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Acciones
// -------------------------------------------------------------------------------------------

@Composable
private fun FieldBookActions(
    count: Int,
    unitLabel: String,
    onNewBlank: () -> Unit,
    onAddPoint: () -> Unit,
    onQuickRow: () -> Unit,
    onImportCsv: () -> Unit,
    onExportCsv: () -> Unit,
    onClear: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Libreta Electrónica de Campo", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text("$count $unitLabel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onAddPoint) { Text("+ Añadir punto") }
                OutlinedButton(onClick = onNewBlank) { Text("Nuevo vacío") }
                OutlinedButton(onClick = onQuickRow) { Text("Fila rápida") }
                OutlinedButton(onClick = onImportCsv) { Text("Importar CSV") }
                OutlinedButton(onClick = onExportCsv, enabled = count > 0) { Text("Exportar CSV") }
                OutlinedButton(onClick = onClear, enabled = count > 0) { Text("Limpiar", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Fila rápida
// -------------------------------------------------------------------------------------------

@Composable
private fun QuickRowCard(
    projectId: Long?,
    points: List<PointEntity>,
    lastTarget: PointEntity?,
    quickKey: Int,
    onAdd: (Long, Long, String, Double?, String, Double?, Double?, Double?, Double?, Double?, String, Double?) -> Unit,
) {
    // "Fila rápida": al pulsarla (quickKey cambia) el formulario se limpia y la estación arranca en
    // el último punto visado, que es el flujo normal de una poligonal. Los datos sobreviven a cambios de pestaña.
    var occupiedId by rememberSaveable(quickKey, projectId) { mutableStateOf(if (quickKey > 0) lastTarget?.id else null) }
    var targetId by rememberSaveable(quickKey, projectId) { mutableStateOf<Long?>(null) }
    var tramo by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var azimuth by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var bearing by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var horizontalAngle by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var horizontalDistance by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var slopeDistance by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var zenithAngle by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var instrumentHeight by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var prismHeight by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var description by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fila rápida", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            PointPicker(label = "Estación", points = points, selected = points.find { it.id == occupiedId }, onSelect = { occupiedId = it.id })
            PointPicker(label = "Punto Visado", points = points, selected = points.find { it.id == targetId }, onSelect = { targetId = it.id })
            OutlinedTextField(value = tramo, onValueChange = { tramo = it }, label = { Text("Tramo") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectAllTextField(value = azimuth, onValueChange = { azimuth = it }, label = { Text("Azimut (°)") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = bearing, onValueChange = { bearing = it }, label = { Text("Rumbo (S30E)") }, keyboardOptions = CODE_KEYBOARD, singleLine = true, modifier = Modifier.weight(1f))
            }
            SelectAllTextField(value = horizontalAngle, onValueChange = { horizontalAngle = it }, label = { Text("Lectura Horiz. / áng. interior (°)") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectAllTextField(value = horizontalDistance, onValueChange = { horizontalDistance = it }, label = { Text("Dist. Horiz.") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, modifier = Modifier.weight(1f))
                SelectAllTextField(value = slopeDistance, onValueChange = { slopeDistance = it }, label = { Text("Dist. Incl.") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, modifier = Modifier.weight(1f))
                SelectAllTextField(value = zenithAngle, onValueChange = { zenithAngle = it }, label = { Text("Cenital (°)") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectAllTextField(value = instrumentHeight, onValueChange = { instrumentHeight = it }, label = { Text("Ai [m]") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, modifier = Modifier.weight(1f))
                SelectAllTextField(value = prismHeight, onValueChange = { prismHeight = it }, label = { Text("Ap [m]") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Código / descripción") }, modifier = Modifier.fillMaxWidth())
            if (localError != null) Text(localError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                fun num(text: String) = text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: Double.NaN }
                val values = listOf(azimuth, horizontalAngle, horizontalDistance, slopeDistance, zenithAngle, instrumentHeight, prismHeight).map { num(it) }
                val o = occupiedId
                val t = targetId
                when {
                    o == null || t == null -> localError = "Elige la estación y el punto visado."
                    o == t -> localError = "La estación y el punto visado no pueden ser el mismo."
                    values.any { it != null && it.isNaN() } -> localError = "Hay campos numéricos con texto inválido."
                    values[2] == null && values[3] == null -> localError = "Indica la distancia horizontal o la inclinada."
                    (values[0] ?: 0.0) !in 0.0..360.0 -> localError = "El azimut debe estar entre 0° y 360°."
                    (values[4] ?: 0.0) !in 0.0..180.0 -> localError = "El cenital debe estar entre 0° y 180°."
                    else -> {
                        localError = null
                        onAdd(o, t, tramo, values[0], bearing, values[2], values[3], values[4], values[5], values[6], description, values[1])
                    }
                }
            }) { Text("Añadir fila") }
        }
    }
}

@Composable
private fun QuickLevelCard(quickKey: Int, projectId: Long?, onAdd: (String, ReadingType, Double, Boolean, (String?) -> Unit) -> Unit) {
    var code by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var typeName by rememberSaveable(quickKey, projectId) { mutableStateOf(ReadingType.BS.name) }
    var value by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var turning by rememberSaveable(quickKey, projectId) { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val type = ReadingType.valueOf(typeName)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fila rápida", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Punto o estación") }, keyboardOptions = CODE_KEYBOARD, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReadingType.entries.forEach { t ->
                    FilterChip(selected = type == t, onClick = { typeName = t.name }, label = { Text(READING_LABELS[t] ?: t.name) })
                }
            }
            SelectAllTextField(value = value, onValueChange = { value = it }, label = { Text("Lectura de mira [m]") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = turning, onCheckedChange = { turning = it })
                Text("Cambio de estación", style = MaterialTheme.typography.bodySmall)
            }
            if (localError != null) Text(localError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val v = value.trim().replace(',', '.').toDoubleOrNull()
                when {
                    code.isBlank() -> localError = "El punto o estación es obligatorio."
                    v == null -> localError = "La lectura debe ser un número."
                    v <= 0.0 || v > 6.0 -> localError = "La lectura de mira debe estar entre 0 y 6 m."
                    else -> onAdd(code.trim().uppercase(), type, v, turning) { err ->
                        localError = err
                        if (err == null) { value = ""; turning = false }
                    }
                }
            }) { Text("Añadir lectura") }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Tablas
// -------------------------------------------------------------------------------------------

private fun matches(query: String, vararg texts: String?): Boolean =
    query.isBlank() || texts.any { it?.contains(query.trim(), ignoreCase = true) == true }

@Composable
private fun ObservationsTable(
    theodolite: Boolean,
    state: LibretaUiState,
    query: String,
    codeById: Map<Long, String>,
    onEdit: (PlanimetryObservationEntity) -> Unit,
    onDuplicate: (PlanimetryObservationEntity) -> Unit,
    onDelete: (PlanimetryObservationEntity) -> Unit,
    onPhoto: (PlanimetryObservationEntity) -> Unit,
    onUpdate: (PlanimetryObservationEntity, String, Double?, Double?, Double?, Double?, String) -> Unit,
) {
    val pointById = state.points.associateBy { it.id }
    // Una sola fuente (título, ancho) para encabezado y celdas, así no se desalinean.
    val columns = listOf(
        "#" to 40.dp, "Estación" to 90.dp, "Punto Visado" to 100.dp, "Tramo" to 80.dp, "Tipo Áng." to 100.dp,
        "Lectura Horiz. [° ' \"]" to 130.dp, "Tipo Dist." to 90.dp, "Distancia [m]" to 100.dp, "Áng. vertical" to 120.dp,
        "Ai [m]" to 70.dp, (if (theodolite) "Hilo medio / señal [m]" else "Ap [m]") to 110.dp, "Descripción" to 150.dp, "Foto" to 60.dp, "Acción" to 280.dp,
    )
    val w = columns.map { it.second }
    val rows = state.observations.withIndex().filter { (_, obs) ->
        matches(query, codeById[obs.occupiedPointId], codeById[obs.targetPointId], obs.tramo, obs.rawNotes)
    }
    Card(Modifier.fillMaxWidth()) {
        val scroll = rememberScrollState()
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.horizontalScroll(scroll)) { columns.forEach { (title, width) -> HeaderCell(title, width) } }
            if (state.observations.isEmpty()) {
                Text("Todavía no hay observaciones registradas. Usa «+ Añadir punto» o la fila rápida.", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (rows.isEmpty()) {
                Text("Sin resultados para «$query».", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rows.forEach { (index, obs) ->
                val horizontalAngle = obs.horizontalAngle
                val azimuth = obs.azimuth
                val zenith = obs.zenithAngle
                val vertical = obs.verticalAngle
                val angleText = when {
                    horizontalAngle != null -> formatDms(decimalToDms(horizontalAngle))
                    azimuth != null -> formatDms(decimalToDms(azimuth))
                    else -> obs.bearing ?: "—"
                }
                val angleType = when {
                    horizontalAngle != null -> "Áng. interior"
                    azimuth != null || obs.bearing != null -> "Azimut/Rumbo"
                    else -> "—"
                }
                val verticalText = when {
                    zenith != null -> "Z ${formatDms(decimalToDms(zenith))}"
                    vertical != null -> "V ${"%.4f".format(vertical)}°"
                    else -> "—"
                }
                val hasPhoto = obs.photoPath != null || pointById[obs.targetPointId]?.sketchPhotoPath != null
                Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                    Cell((index + 1).toString(), w[0], mono = true)
                    Cell(codeById[obs.occupiedPointId] ?: "?", w[1], mono = true)
                    Cell(codeById[obs.targetPointId] ?: "?", w[2], mono = true)
                    EditableCell(obs.tramo ?: EMPTY_CELL, w[3]) { onUpdate(obs, it, obs.horizontalDistance, obs.slopeDistance, obs.instrumentHeight, obs.prismHeight, obs.rawNotes ?: "") }
                    Cell(angleType, w[4])
                    Cell(angleText, w[5], mono = true)
                    Cell(if (obs.horizontalDistance != null) "Horizontal" else if (obs.slopeDistance != null) "Inclinada" else "—", w[6])
                    EditableCell(fmt(obs.horizontalDistance ?: obs.slopeDistance), w[7], mono = true) { text ->
                        val value = text.parseNumberOr(obs.horizontalDistance ?: obs.slopeDistance)
                        // La distancia editada conserva su tipo: inclinada solo si la observación ya era inclinada.
                        if (obs.horizontalDistance == null && obs.slopeDistance != null) {
                            onUpdate(obs, obs.tramo ?: "", null, value, obs.instrumentHeight, obs.prismHeight, obs.rawNotes ?: "")
                        } else {
                            onUpdate(obs, obs.tramo ?: "", value, null, obs.instrumentHeight, obs.prismHeight, obs.rawNotes ?: "")
                        }
                    }
                    Cell(verticalText, w[8], mono = true)
                    EditableCell(fmt(obs.instrumentHeight), w[9], mono = true) {
                        onUpdate(obs, obs.tramo ?: "", obs.horizontalDistance, obs.slopeDistance, it.parseNumberOr(obs.instrumentHeight), obs.prismHeight, obs.rawNotes ?: "")
                    }
                    EditableCell(fmt(obs.prismHeight), w[10], mono = true) {
                        onUpdate(obs, obs.tramo ?: "", obs.horizontalDistance, obs.slopeDistance, obs.instrumentHeight, it.parseNumberOr(obs.prismHeight), obs.rawNotes ?: "")
                    }
                    EditableCell(obs.rawNotes ?: EMPTY_CELL, w[11]) {
                        onUpdate(obs, obs.tramo ?: "", obs.horizontalDistance, obs.slopeDistance, obs.instrumentHeight, obs.prismHeight, it)
                    }
                    Box(Modifier.width(w[12])) { TextButton(onClick = { onPhoto(obs) }) { Text(if (hasPhoto) "Sí" else "＋") } }
                    Row(Modifier.width(w[13])) {
                        TextButton(onClick = { onEdit(obs) }) { Text("Editar") }
                        TextButton(onClick = { onDuplicate(obs) }) { Text("Duplicar") }
                        TextButton(onClick = { onDelete(obs) }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelTable(
    state: LibretaUiState,
    query: String,
    codeById: Map<Long, String>,
    onEdit: (LevelRowView) -> Unit,
    onDuplicate: (LevelRowView) -> Unit,
    onDelete: (LevelRowView) -> Unit,
    onValue: (LevelRowView, Double) -> Unit,
) {
    val pointById = state.points.associateBy { it.id }
    val columns = listOf(
        "Orden" to 60.dp, "Punto / Estación" to 120.dp, "VA (+) [m]" to 90.dp, "VI [m]" to 90.dp, "VD (−) [m]" to 90.dp,
        "Alt. Inst. (AI)" to 100.dp, "Cota [m]" to 90.dp, "Cambio" to 70.dp, "Descripción" to 150.dp, "Foto" to 60.dp, "Acción" to 280.dp,
    )
    val w = columns.map { it.second }
    val rows = state.levelRows.filter { r ->
        matches(query, codeById[r.reading.pointId], r.reading.rawNotes, pointById[r.reading.pointId]?.description)
    }
    val sumVa = state.levelRows.filter { it.reading.readingType == ReadingType.BS }.sumOf { it.reading.readingValue }
    val sumVd = state.levelRows.filter { it.reading.readingType == ReadingType.FS }.sumOf { it.reading.readingValue }
    val lastElevation = state.levelRows.lastOrNull { it.elevation != null }?.elevation
    val reference = state.levelReferenceElevation

    Card(Modifier.fillMaxWidth()) {
        val scroll = rememberScrollState()
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Summary("Σ VA", "%.3f m".format(sumVa))
                Summary("Σ VD", "%.3f m".format(sumVd))
                Summary("Δ Cota (final − ref.)", if (lastElevation != null && reference != null) "%.3f m".format(lastElevation - reference) else "-- m")
                Summary(
                    "Comprobación",
                    when (state.levelCheckOk) { true -> "Libreta cuadrada"; false -> "NO cuadra"; null -> "Sin calcular" },
                    if (state.levelCheckOk == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            state.levelWarnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            Row(Modifier.horizontalScroll(scroll)) { columns.forEach { (title, width) -> HeaderCell(title, width) } }
            if (reference != null) {
                Row(Modifier.horizontalScroll(scroll)) {
                    Cell("Ref.", w[0], mono = true)
                    Cell(state.levelReferenceCode ?: "?", w[1], mono = true)
                    Cell("—", w[2]); Cell("—", w[3]); Cell("—", w[4]); Cell("—", w[5])
                    Cell("%.3f".format(reference), w[6], mono = true)
                    Cell("—", w[7]); Cell("Cota de referencia", w[8]); Cell("—", w[9]); Cell("—", w[10])
                }
            }
            if (state.levelRows.isEmpty()) {
                Text("Todavía no hay lecturas registradas. Usa «+ Añadir punto» o la fila rápida.", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (rows.isEmpty()) {
                Text("Sin resultados para «$query».", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rows.forEach { row ->
                val reading = row.reading
                val point = pointById[reading.pointId]
                val value = "%.3f".format(reading.readingValue)
                Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                    Cell(reading.sequenceOrder.toString(), w[0], mono = true)
                    Cell(codeById[reading.pointId] ?: "?", w[1], mono = true)
                    // La lectura se edita en la columna de su tipo; las otras dos quedan vacías.
                    listOf(ReadingType.BS to w[2], ReadingType.IS to w[3], ReadingType.FS to w[4]).forEach { (type, width) ->
                        if (reading.readingType == type) {
                            EditableCell(value, width, mono = true) { text ->
                                text.parseNumberOr(reading.readingValue)?.takeIf { it > 0.0 && it <= 6.0 }?.let { onValue(row, it) }
                            }
                        } else {
                            Cell("—", width, mono = true)
                        }
                    }
                    Cell(row.instrumentHeight?.let { "%.3f".format(it) } ?: "—", w[5], mono = true)
                    Cell(row.elevation?.let { "%.3f".format(it) } ?: "—", w[6], mono = true)
                    Cell(if (reading.isTurningPoint) "Sí" else "No", w[7])
                    Cell(reading.rawNotes ?: point?.description ?: "—", w[8])
                    Cell(if (point?.sketchPhotoPath != null) "Sí" else "No", w[9])
                    Row(Modifier.width(w[10])) {
                        TextButton(onClick = { onEdit(row) }) { Text("Editar") }
                        TextButton(onClick = { onDuplicate(row) }) { Text("Duplicar") }
                        TextButton(onClick = { onDelete(row) }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Summary(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoFieldStyle, color = valueColor)
    }
}

// -------------------------------------------------------------------------------------------
// Exportar la libreta completa (sin perder datos)
// -------------------------------------------------------------------------------------------

private fun buildFieldBookCsv(state: LibretaUiState): String {
    val codeById = state.points.associate { it.id to it.code }
    val pointById = state.points.associateBy { it.id }
    fun n(v: Double?) = v?.let { "%.4f".format(it) } ?: ""

    if (state.mode == LibretaMode.GNSS) return buildGnssCsv(state)

    return if (state.mode == LibretaMode.NIVEL) {
        val headers = listOf("Orden", "Punto", "VA (m)", "VI (m)", "VD (m)", "Altura instrumento AI (m)", "Cota calculada (m)", "Cambio de estación", "Descripción", "Foto")
        val rows = mutableListOf<List<String>>()
        state.levelReferenceElevation?.let {
            rows += listOf("0", state.levelReferenceCode ?: "", "", "", "", "", "%.4f".format(it), "", "Cota de referencia", "")
        }
        state.levelRows.forEach { r ->
            val x = r.reading
            rows += listOf(
                x.sequenceOrder.toString(), codeById[x.pointId] ?: "",
                if (x.readingType == ReadingType.BS) "%.4f".format(x.readingValue) else "",
                if (x.readingType == ReadingType.IS) "%.4f".format(x.readingValue) else "",
                if (x.readingType == ReadingType.FS) "%.4f".format(x.readingValue) else "",
                n(r.instrumentHeight), n(r.elevation), if (x.isTurningPoint) "Sí" else "No",
                x.rawNotes ?: pointById[x.pointId]?.description ?: "", if (pointById[x.pointId]?.sketchPhotoPath != null) "Sí" else "No",
            )
        }
        buildCsv(headers, rows)
    } else {
        val headers = listOf(
            "N", "Estación", "Punto visado", "Tramo", "Azimut (°)", "Rumbo", "Lectura horizontal (°)", "Cenital (°)", "Vertical (°)",
            "Dist. horizontal (m)", "Dist. inclinada (m)", "Ai (m)", "Ap (m)", "Descripción", "Foto",
        )
        val rows = state.observations.mapIndexed { i, o ->
            listOf(
                (i + 1).toString(), codeById[o.occupiedPointId] ?: "", codeById[o.targetPointId] ?: "", o.tramo ?: "",
                n(o.azimuth), o.bearing ?: "", n(o.horizontalAngle), n(o.zenithAngle), n(o.verticalAngle),
                n(o.horizontalDistance), n(o.slopeDistance), n(o.instrumentHeight), n(o.prismHeight),
                o.rawNotes ?: "", if (o.photoPath != null || pointById[o.targetPointId]?.sketchPhotoPath != null) "Sí" else "No",
            )
        }
        buildCsv(headers, rows)
    }
}

// -------------------------------------------------------------------------------------------

@Composable
private fun PointPicker(label: String, points: List<PointEntity>, selected: PointEntity?, onSelect: (PointEntity) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected?.code ?: "Seleccione...",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (points.isEmpty()) {
                DropdownMenuItem(text = { Text("Sin puntos: créalos con + Añadir punto") }, onClick = {})
            }
            points.forEach { p ->
                DropdownMenuItem(text = { Text("${p.code} -- ${p.description ?: ""}") }, onClick = { onSelect(p); expanded = false })
            }
        }
    }
}

private fun fmt(value: Double?): String = value?.let { "%.3f".format(it) } ?: "—"

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Text(text, modifier = Modifier.width(width).padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Cell(text: String, width: Dp, mono: Boolean = false) {
    Text(text, modifier = Modifier.width(width).padding(4.dp), style = if (mono) MonoFieldStyle else MaterialTheme.typography.bodySmall)
}

// -------------------------------------------------------------------------------------------
// GPS diferencial (GNSS)
// -------------------------------------------------------------------------------------------

/** Solución que se propone en un punto nuevo: la que indica el proyecto en Instrumento (RTK Fixed si no hay dato). */
private fun gnssDefaultFix(state: LibretaUiState): FixType = when (state.project?.instrumentProfile?.gnssSolutionStatus) {
    GnssSolutionStatus.RTK_FLOAT -> FixType.FLOAT
    GnssSolutionStatus.AUTONOMO, GnssSolutionStatus.SIN_CONEXION -> FixType.AUTONOMO
    else -> FixType.FIXED
}

private fun buildGnssCsv(state: LibretaUiState): String {
    val headers = listOf("N", "Punto", "Este (m)", "Norte (m)", "Cota (m)", "Solución", "PDOP", "Satélites", "Altura antena (m)", "Tramo", "Descripción", "Foto")
    fun n(v: Double?) = v?.let { "%.4f".format(it) } ?: ""
    val rows = state.gnssPoints.mapIndexed { i, p ->
        val extra = decodeGnssExtra(p.observations)
        listOf(
            (i + 1).toString(), p.code, n(p.fixedEast), n(p.fixedNorth), n(p.fixedElevation), fixLabel(p.fixType),
            extra.pdop?.toString() ?: "", extra.satellites?.toString() ?: "", n(extra.antennaHeight),
            p.tramoSector ?: "", p.description ?: "", if (p.sketchPhotoPath != null) "Sí" else "No",
        )
    }
    return buildCsv(headers, rows)
}

@Composable
private fun GnssInfoCard(state: LibretaUiState) {
    val profile = state.project?.instrumentProfile
    val statusText = when (profile?.gnssSolutionStatus) {
        GnssSolutionStatus.RTK_FIXED -> "RTK Fixed (±0,01 m)"
        GnssSolutionStatus.RTK_FLOAT -> "RTK Float (±0,20 m)"
        GnssSolutionStatus.AUTONOMO -> "Autónomo (±2,5 m)"
        GnssSolutionStatus.SIN_CONEXION -> "Sin conexión"
        null -> "sin definir"
    }
    val correctionText = when (profile?.differentialCorrectionType) {
        DifferentialCorrectionType.NTRIP -> "Red NTRIP (CORS)"
        DifferentialCorrectionType.UHF -> "Radio UHF (base local)"
        DifferentialCorrectionType.SBAS -> "SBAS / L-Band"
        null -> "sin definir"
    }
    val counts = FixType.entries.map { it to state.gnssPoints.count { p -> p.fixType == it } }.filter { it.second > 0 }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Receptor GNSS (GPS diferencial)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Según la pestaña Instrumento: solución $statusText · corrección $correctionText" +
                    (profile?.pdop?.let { " · PDOP $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Cada punto se guarda con las coordenadas que midió el receptor. Entra a Planimetría, Plano CAD, Exportar y al informe como dato medido con GNSS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (counts.isNotEmpty()) {
                Text(
                    "Puntos por solución: " + counts.joinToString(" · ") { (fix, n) -> "${fixLabel(fix)} $n" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            val weak = state.gnssPoints.count { it.fixType != null && it.fixType != FixType.FIXED }
            if (weak > 0) {
                Text("⚠ $weak punto(s) sin solución RTK Fixed: revisa su precisión antes de usarlos como control.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun QuickGnssCard(
    quickKey: Int,
    projectId: Long?,
    existingCodes: List<String>,
    defaultFix: FixType,
    onAdd: (GnssPointForm, (String?) -> Unit) -> Unit,
) {
    var code by rememberSaveable(quickKey, projectId) { mutableStateOf(suggestNextPointCode(existingCodes)) }
    var fixName by rememberSaveable(quickKey, projectId) { mutableStateOf(defaultFix.name) }
    var east by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var north by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var elevation by rememberSaveable(quickKey, projectId) { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val fix = FixType.valueOf(fixName)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fila rápida", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Identificador del punto") }, keyboardOptions = CODE_KEYBOARD, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FixType.entries.forEach { f -> FilterChip(selected = fix == f, onClick = { fixName = f.name }, label = { Text(fixLabel(f)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectAllTextField(value = east, onValueChange = { east = it }, label = { Text("Este (X)") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, modifier = Modifier.weight(1f))
                SelectAllTextField(value = north, onValueChange = { north = it }, label = { Text("Norte (Y)") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, modifier = Modifier.weight(1f))
                SelectAllTextField(value = elevation, onValueChange = { elevation = it }, label = { Text("Cota (Z)") }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, modifier = Modifier.weight(1f))
            }
            if (localError != null) Text(localError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                fun num(text: String) = text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: Double.NaN }
                val e = num(east)
                val n = num(north)
                val z = num(elevation)
                when {
                    code.isBlank() -> localError = "El identificador del punto es obligatorio."
                    e == null || n == null -> localError = "Este y Norte son obligatorios."
                    e.isNaN() || n.isNaN() || (z != null && z.isNaN()) -> localError = "Este, Norte y Cota deben ser números."
                    else -> onAdd(GnssPointForm(code.trim().uppercase(), "", "", fix, e, n, z, null, null, null, null, "")) { err ->
                        localError = err
                        if (err == null) {
                            code = suggestNextPointCode(existingCodes + code.trim())
                            east = ""; north = ""; elevation = ""
                        }
                    }
                }
            }) { Text("Añadir punto") }
        }
    }
}

@Composable
private fun GnssTable(
    state: LibretaUiState,
    query: String,
    onEdit: (PointEntity) -> Unit,
    onDuplicate: (PointEntity) -> Unit,
    onDelete: (PointEntity) -> Unit,
    onPhoto: (PointEntity) -> Unit,
    onUpdate: (PointEntity, Double, Double, Double?, String) -> Unit,
) {
    val columns = listOf(
        "#" to 40.dp, "Punto" to 90.dp, "Este (X) [m]" to 120.dp, "Norte (Y) [m]" to 120.dp, "Cota (Z) [m]" to 100.dp,
        "Solución" to 100.dp, "PDOP" to 60.dp, "Sat." to 50.dp, "Alt. antena [m]" to 100.dp, "Tramo" to 80.dp,
        "Descripción" to 150.dp, "Foto" to 60.dp, "Acción" to 280.dp,
    )
    val w = columns.map { it.second }
    val rows = state.gnssPoints.withIndex().filter { (_, p) -> matches(query, p.code, p.description, p.tramoSector) }

    Card(Modifier.fillMaxWidth()) {
        val scroll = rememberScrollState()
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.horizontalScroll(scroll)) { columns.forEach { (title, width) -> HeaderCell(title, width) } }
            if (state.gnssPoints.isEmpty()) {
                Text("Todavía no hay puntos GNSS. Usa «+ Añadir punto» o la fila rápida.", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (rows.isEmpty()) {
                Text("Sin resultados para «$query».", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rows.forEach { (index, p) ->
                val extra = decodeGnssExtra(p.observations)
                val east = p.fixedEast ?: return@forEach
                val north = p.fixedNorth ?: return@forEach
                Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                    Cell((index + 1).toString(), w[0], mono = true)
                    Cell(p.code, w[1], mono = true)
                    // Este, Norte, Cota y descripción se editan directamente en la celda.
                    EditableCell(fmt(east), w[2], mono = true) { text -> text.parseNumberOr(east)?.let { onUpdate(p, it, north, p.fixedElevation, p.description ?: "") } }
                    EditableCell(fmt(north), w[3], mono = true) { text -> text.parseNumberOr(north)?.let { onUpdate(p, east, it, p.fixedElevation, p.description ?: "") } }
                    EditableCell(fmt(p.fixedElevation), w[4], mono = true) { text -> onUpdate(p, east, north, text.parseNumberOr(p.fixedElevation), p.description ?: "") }
                    Cell(fixLabel(p.fixType) + if (p.fixType != null && p.fixType != FixType.FIXED) " ⚠" else "", w[5])
                    Cell(extra.pdop?.toString() ?: "—", w[6], mono = true)
                    Cell(extra.satellites?.toString() ?: "—", w[7], mono = true)
                    Cell(extra.antennaHeight?.let { "%.3f".format(it) } ?: "—", w[8], mono = true)
                    Cell(p.tramoSector ?: "—", w[9])
                    EditableCell(p.description ?: EMPTY_CELL, w[10]) { onUpdate(p, east, north, p.fixedElevation, it) }
                    Box(Modifier.width(w[11])) { TextButton(onClick = { onPhoto(p) }) { Text(if (p.sketchPhotoPath != null) "Sí" else "＋") } }
                    Row(Modifier.width(w[12])) {
                        TextButton(onClick = { onEdit(p) }) { Text("Editar") }
                        TextButton(onClick = { onDuplicate(p) }) { Text("Duplicar") }
                        TextButton(onClick = { onDelete(p) }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}