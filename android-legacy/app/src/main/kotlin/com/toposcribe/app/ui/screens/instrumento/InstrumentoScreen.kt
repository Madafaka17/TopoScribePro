package com.toposcribe.app.ui.screens.instrumento

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.lazy.rememberLazyListState
import com.toposcribe.app.ui.components.isInvalidNumber
import com.toposcribe.app.ui.components.toDecimalOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toposcribe.app.ui.theme.MonoFieldStyle
import com.toposcribe.data.local.entity.CoordinateMode
import com.toposcribe.data.local.entity.DifferentialCorrectionType
import com.toposcribe.data.local.entity.GnssSolutionStatus
import com.toposcribe.data.local.entity.Hemisphere
import com.toposcribe.data.local.entity.InstrumentProfile
import com.toposcribe.data.local.entity.InstrumentType
import com.toposcribe.data.local.entity.ProjectEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private const val AUTOSAVE_DELAY_MS = 500L

@Composable
fun InstrumentoScreen(modifier: Modifier = Modifier, viewModel: InstrumentoViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var focusNameAfterCreate by remember { mutableStateOf(false) }
    var focusName by remember { mutableStateOf(false) }

    // Como en el diseño: siempre hay un proyecto de trabajo (vacío) con todos los formularios visibles.
    LaunchedEffect(state.isLoading, state.currentProject == null, state.projects.size) {
        if (!state.isLoading && state.currentProject == null) {
            // Si el proyecto guardado ya no existe, se abre el de trabajo mas reciente (el ultimo con cambios),
            // no el recien creado: asi no se aparta al usuario de sus datos.
            val latest = state.projects.maxByOrNull { it.updatedAt ?: it.createdAt }
            if (latest != null) viewModel.selectProject(latest.id) else viewModel.createBlank()
        }
    }

    // Tras "Nuevo Vacío": cuando el proyecto nuevo ya es el activo, se baja a "Metadatos de Brigada"
    // y se deja el cursor en "Nombre del Proyecto" para empezar a escribir.
    LaunchedEffect(state.currentProject?.id) {
        if (focusNameAfterCreate && state.currentProject != null) {
            focusNameAfterCreate = false
            listState.animateScrollToItem(METADATA_ITEM_INDEX)
            focusName = true
        }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            HeaderCard(
                current = state.currentProject,
                onNewProject = { focusNameAfterCreate = true; viewModel.createBlank() },
            )
        }
        val project = state.currentProject
        if (project != null) {
            item { MetadataCard(project, focusName, { focusName = false }, viewModel::updateMetadata) }
            item { InstrumentCard(project, viewModel) }
            item { CoordinateSystemCard(project, viewModel::updateCoordinateSystem) }
        }
    }
}

/** Posición de la tarjeta "Metadatos de Brigada" en la lista (la cabecera es el 0). */
private const val METADATA_ITEM_INDEX = 1

@Composable
private fun HeaderCard(current: ProjectEntity?, onNewProject: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "PROYECTO TOPOGRÁFICO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Text(
                    current?.name?.takeIf { it.isNotBlank() } ?: "Proyecto Nuevo sin Nombre",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "Parámetros de instrumental, sistema de referencia y metadatos de brigada.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (current?.isDemo == true) {
                Text(
                    "DATOS DE DEMOSTRACIÓN -- no representa un levantamiento real.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Button(onClick = onNewProject, modifier = Modifier.padding(top = 10.dp)) { Text("Nuevo Vacío") }
        }
    }
}

/** Los campos se guardan solos (como el `oninput` del diseño): sin botón "Guardar", 500 ms después de dejar de escribir. */
@Composable
private fun MetadataCard(project: ProjectEntity, focusName: Boolean, onFocusHandled: () -> Unit, onSave: (String, String, String, String, String, String, String, String) -> Unit) {
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(focusName) {
        if (focusName) {
            delay(150) // da tiempo a que el campo ya esté en pantalla
            try { nameFocus.requestFocus() } catch (e: IllegalStateException) { /* el campo aún no está colocado: se omite */ }
            onFocusHandled()
        }
    }

    var name by remember(project.id) { mutableStateOf(project.name) }
    var purpose by remember(project.id) { mutableStateOf(project.purpose ?: "") }
    var location by remember(project.id) { mutableStateOf(project.location ?: "") }
    var site by remember(project.id) { mutableStateOf(project.siteCharacteristics ?: "") }
    var date by remember(project.id) { mutableStateOf(project.date ?: LocalDate.now().toString()) }
    var responsible by remember(project.id) { mutableStateOf(project.responsible ?: "") }
    var fieldProcedure by remember(project.id) { mutableStateOf(project.fieldProcedure ?: "") }
    var observations by remember(project.id) { mutableStateOf(project.observations ?: "") }

    var firstRun by remember(project.id) { mutableStateOf(true) }
    // project.id entre las claves: al cambiar de proyecto el efecto se reinicia y consume el "primer arranque";
    // sin ella, si los campos del proyecto nuevo coincidían con los del anterior, la primera edición se perdía.
    LaunchedEffect(project.id, name, purpose, location, site, date, responsible, fieldProcedure, observations) {
        if (firstRun) {
            firstRun = false
            // La fecha que se muestra por defecto (hoy) también se guarda, para que salga en el informe.
            if (project.date != null) return@LaunchedEffect
        }
        delay(AUTOSAVE_DELAY_MS)
        onSave(name, purpose, location, site, date, responsible, fieldProcedure, observations)
    }

    FormCard(title = "1. Metadatos de Brigada y Levantamiento") {
        TwoColumns(
            { LabeledField("Nombre del Proyecto", name, "Ej: Poligonal Cerrada Eje Norte", Modifier.focusRequester(nameFocus)) { name = it } },
            { LabeledField("Ubicación / Paraje", location, "Ej: Quebrada Honda Km 12.4") { location = it } },
        )
        TwoColumns(
            { DateField("Fecha de Campaña", date) { date = it } },
            { LabeledField("Responsable (CIP/Registro)", responsible, "Ej: Ing. C. Albornoz (CIP 88412)") { responsible = it } },
        )
        LabeledField("Finalidad", purpose, "Ej: Determinación de linderos y replanteo") { purpose = it }
        LabeledField("Características del lugar", site) { site = it }
        LabeledField("Procedimiento de campo", fieldProcedure) { fieldProcedure = it }
        LabeledField("Observaciones", observations) { observations = it }
    }
}

@Composable
private fun InstrumentCard(project: ProjectEntity, viewModel: InstrumentoViewModel) {
    var instrumentType by remember(project.id) { mutableStateOf(project.instrumentType) }
    var instrument by remember(project.id) { mutableStateOf(project.instrument ?: "") }
    var prismConstantMm by remember(project.id) { mutableStateOf(formatNumber(project.prismConstantM * 1000.0)) }
    val profile = project.instrumentProfile
    var angularPrecision by remember(project.id) { mutableStateOf(formatNumber(profile.angularPrecisionArcSec ?: 2.0)) }
    var linearTolerance by remember(project.id) { mutableStateOf(formatNumber(profile.linearToleranceDenominator ?: 5000.0)) }
    var gnssStatus by remember(project.id) { mutableStateOf(profile.gnssSolutionStatus ?: GnssSolutionStatus.SIN_CONEXION) }
    var pdop by remember(project.id) { mutableStateOf(formatNumber(profile.pdop ?: 1.8)) }
    var correction by remember(project.id) { mutableStateOf(profile.differentialCorrectionType ?: DifferentialCorrectionType.NTRIP) }
    var compensator by remember(project.id) { mutableStateOf(formatNumber(profile.compensatorSensitivityArcSec ?: 10.0)) }
    var miraType by remember(project.id) { mutableStateOf(profile.miraType ?: "telescopic") }
    var levelPrecision by remember(project.id) { mutableStateOf(formatNumber(profile.standardPrecisionMmPerKm ?: 1.0)) }

    var firstRun by remember(project.id) { mutableStateOf(true) }
    // Con un número a medio escribir o inválido no se guarda nada (antes se guardaba 0 o se perdía el valor).
    val hasInvalidNumber = listOf(prismConstantMm, angularPrecision, linearTolerance, pdop, compensator, levelPrecision).any { it.isInvalidNumber() }
    LaunchedEffect(project.id, instrumentType, instrument, prismConstantMm, angularPrecision, linearTolerance, gnssStatus, pdop, correction, compensator, miraType, levelPrecision) {
        if (firstRun) { firstRun = false; return@LaunchedEffect }
        if (hasInvalidNumber) return@LaunchedEffect
        delay(AUTOSAVE_DELAY_MS)
        viewModel.updateInstrumentFull(
            instrument,
            instrumentType,
            prismConstantMm.toDecimalOrNull() ?: 0.0,
            InstrumentProfile(
                angularPrecisionArcSec = angularPrecision.toDecimalOrNull(),
                linearToleranceDenominator = linearTolerance.toDecimalOrNull(),
                gnssSolutionStatus = gnssStatus,
                pdop = pdop.toDecimalOrNull(),
                differentialCorrectionType = correction,
                compensatorSensitivityArcSec = compensator.toDecimalOrNull(),
                miraType = miraType,
                standardPrecisionMmPerKm = levelPrecision.toDecimalOrNull(),
            ),
        )
    }

    FormCard(
        title = "2. Instrumental y Óptica de Campo",
        badge = when (instrumentType) {
            InstrumentType.ESTACION_TOTAL -> "ESTACIÓN TOTAL"
            InstrumentType.TEODOLITO -> "TEODOLITO"
            InstrumentType.GNSS_RTK -> "GNSS / RTK"
            InstrumentType.NIVEL_OPTICO -> "NIVEL ÓPTICO / DIGITAL"
        },
    ) {
        SimpleDropdown(
            label = "Tipo de Instrumento en Uso",
            options = InstrumentType.entries,
            selected = instrumentType,
            optionLabel = ::instrumentTypeLabel,
            onSelect = { instrumentType = it },
        )
        LabeledField("Instrumento (marca / modelo)", instrument, "Ej: Leica TS06 Plus") { instrument = it }

        when (instrumentType) {
            InstrumentType.ESTACION_TOTAL -> {
                InfoBox(
                    "Campos exclusivos para Estación Total: constante de prisma, precisión angular y tolerancias EDM.",
                    background = MaterialTheme.colorScheme.surfaceContainerLow,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TwoColumns(
                    { LabeledNumberField("Constante de Prisma (mm)", prismConstantMm, suffix = "mm") { prismConstantMm = it } },
                    { LabeledNumberField("Precisión Angular (DIN)", angularPrecision, suffix = "\" (seg)") { angularPrecision = it } },
                )
                LabeledNumberField("Tolerancia Lineal Requerida", linearTolerance, prefix = "1 /") { linearTolerance = it }
            }
            InstrumentType.TEODOLITO -> {
                InfoBox(
                    "Campos del Teodolito: mide ángulos; la distancia sale por estadía (hilos del retículo) o con cinta. Sin constante de prisma.",
                    background = MaterialTheme.colorScheme.surfaceContainerLow,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LabeledNumberField("Precisión Angular", angularPrecision, suffix = "\" (seg)") { angularPrecision = it }
            }
            InstrumentType.GNSS_RTK -> {
                InfoBox(
                    "Campos exclusivos para GNSS Geodésico. Sin constantes prismáticas. Estado de solución transmitido por enlace diferencial.",
                    background = Color(0xFFEFF6FF),
                    content = Color(0xFF1E3A8A),
                )
                SimpleDropdown(
                    label = "Estado de Solución GNSS",
                    options = GnssSolutionStatus.entries,
                    selected = gnssStatus,
                    optionLabel = ::gnssStatusLabel,
                    onSelect = { gnssStatus = it },
                )
                TwoColumns(
                    { LabeledNumberField("Dilución de Precisión (PDOP)", pdop) { pdop = it } },
                    {
                        SimpleDropdown(
                            label = "Corrección Diferencial",
                            options = DifferentialCorrectionType.entries,
                            selected = correction,
                            optionLabel = ::correctionLabel,
                            onSelect = { correction = it },
                        )
                    },
                )
            }
            InstrumentType.NIVEL_OPTICO -> {
                InfoBox(
                    "Campos exclusivos para Nivelación Geométrica. Sin ángulos horizontales ni constantes prismáticas.",
                    background = Color(0xFFFFF7ED),
                    content = Color(0xFF7C2D12),
                )
                TwoColumns(
                    { LabeledNumberField("Sensibilidad Compensador", compensator, suffix = "\"") { compensator = it } },
                    { LabeledNumberField("Precisión Estándar", levelPrecision, suffix = "mm/km") { levelPrecision = it } },
                )
                SimpleDropdown(
                    label = "Tipo de Mira / Estadía",
                    options = listOf("telescopic", "invar"),
                    selected = miraType,
                    optionLabel = { if (it == "invar") "Mira Invar Código de Barras (Alta Precisión)" else "Mira Telescópica Aluminio E (5m)" },
                    onSelect = { miraType = it },
                )
            }
        }
    }
}

@Composable
private fun CoordinateSystemCard(
    project: ProjectEntity,
    onSave: (CoordinateMode, String, String, Int?, Hemisphere?) -> Unit,
) {
    var mode by remember(project.id) { mutableStateOf(project.coordinateMode) }
    var coordinateSystem by remember(project.id) { mutableStateOf(project.coordinateSystem ?: "") }
    var utmDatum by remember(project.id) { mutableStateOf(project.utmDatum ?: "") }
    var utmZone by remember(project.id) { mutableStateOf(project.utmZone?.toString() ?: "19") }
    var hemisphere by remember(project.id) { mutableStateOf(project.utmHemisphere ?: Hemisphere.S) }

    var firstRun by remember(project.id) { mutableStateOf(true) }
    LaunchedEffect(project.id, mode, coordinateSystem, utmDatum, utmZone, hemisphere) {
        if (firstRun) { firstRun = false; return@LaunchedEffect }
        delay(AUTOSAVE_DELAY_MS)
        onSave(mode, coordinateSystem, utmDatum, utmZone.toIntOrNull()?.takeIf { it in 1..60 }, if (mode == CoordinateMode.UTM) hemisphere else null)
    }

    FormCard(title = "3. Sistema de Referencia Espacial") {
        SimpleDropdown(
            label = "Tipo de Sistema",
            options = CoordinateMode.entries,
            selected = mode,
            optionLabel = { if (it == CoordinateMode.LOCAL) "Plano Topográfico Local (PTL) - Arbitrario" else "Georreferenciado Proyectado UTM" },
            onSelect = { mode = it },
        )
        if (mode == CoordinateMode.UTM) {
            LabeledField("Datum", utmDatum, "Ej: WGS84") { utmDatum = it }
            TwoColumns(
                { LabeledNumberField("Zona UTM (1-60)", utmZone) { utmZone = it } },
                {
                    SimpleDropdown(
                        label = "Hemisferio",
                        options = Hemisphere.entries,
                        selected = hemisphere,
                        optionLabel = { if (it == Hemisphere.N) "NORTE (N)" else "SUR (S)" },
                        onSelect = { hemisphere = it },
                    )
                },
            )
        } else {
            LabeledField("Sistema de coordenadas (descripción libre)", coordinateSystem) { coordinateSystem = it }
        }
        Text(
            "Regla: No se asigna zona UTM automáticamente para evitar distorsiones cartográficas en planos locales.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private fun gnssStatusLabel(status: GnssSolutionStatus): String = when (status) {
    GnssSolutionStatus.SIN_CONEXION -> "Sin Conexión / Desconectado"
    GnssSolutionStatus.AUTONOMO -> "Autónomo / Single (±2.5m)"
    GnssSolutionStatus.RTK_FLOAT -> "RTK Float (Flotante ±0.20m)"
    GnssSolutionStatus.RTK_FIXED -> "RTK Fixed (Fijo ±0.01m)"
}

private fun correctionLabel(type: DifferentialCorrectionType): String = when (type) {
    DifferentialCorrectionType.NTRIP -> "Red NTRIP (CORS / IP)"
    DifferentialCorrectionType.UHF -> "Radio UHF Base Local"
    DifferentialCorrectionType.SBAS -> "SBAS / Satelital L-Band"
}

private fun instrumentTypeLabel(type: InstrumentType): String = when (type) {
    InstrumentType.ESTACION_TOTAL -> "Estación Total (Taquímetro Electrónico)"
    InstrumentType.TEODOLITO -> "Teodolito (óptico o electrónico)"
    InstrumentType.GNSS_RTK -> "GNSS / Antena Geodésica RTK"
    InstrumentType.NIVEL_OPTICO -> "Nivel Óptico / Digital de Precisión"
}

@Composable
private fun FormCard(title: String, badge: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            content()
        }
    }
}

/** Dos campos lado a lado (cuadrícula de 2 columnas del diseño). */
@Composable
private fun TwoColumns(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { left() }
        Box(Modifier.weight(1f)) { right() }
    }
}

@Composable
private fun InfoBox(text: String, background: Color, content: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = content,
        modifier = Modifier.fillMaxWidth().background(background, RoundedCornerShape(4.dp)).padding(8.dp),
    )
}

@Composable
private fun LabeledField(label: String, value: String, placeholder: String? = null, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun LabeledNumberField(label: String, value: String, prefix: String? = null, suffix: String? = null, onChange: (String) -> Unit) {
    com.toposcribe.app.ui.components.SelectAllTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        prefix = prefix?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it) } },
        textStyle = MonoFieldStyle,
        singleLine = true,
        isError = value.isInvalidNumber(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Fecha con selector de calendario (el `type="date"` del diseño). Se guarda como yyyy-MM-dd y se muestra dd/mm/aaaa. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val parsed = remember(value) { runCatching { LocalDate.parse(value) }.getOrNull() }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = parsed?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text("dd/mm/aaaa") },
            trailingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = "Elegir fecha") },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { show = true })
    }
    if (show) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = parsed?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    show = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancelar") } },
        ) { DatePicker(state = pickerState) }
    }
}

/** Dropdown simple y robusto (Box + DropdownMenu, con un overlay clickeable transparente encima
 * del campo) en vez de ExposedDropdownMenuBox, para no depender de la firma exacta de
 * menuAnchor() de esta versión puntual de Material3. Los hijos de un Box se superponen por
 * diseño -- es justo lo que hace falta acá (campo + overlay clickeable + menú, todos anclados al
 * mismo lugar), a diferencia de Column que los apilaría verticalmente. */
@Composable
private fun <T> SimpleDropdown(label: String, options: List<T>, selected: T, optionLabel: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}
