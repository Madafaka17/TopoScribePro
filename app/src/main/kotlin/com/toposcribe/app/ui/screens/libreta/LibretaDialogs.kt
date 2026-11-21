package com.toposcribe.app.ui.screens.libreta

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.toposcribe.app.data.repository.RadiationReadingForm
import com.toposcribe.app.ui.components.CODE_KEYBOARD
import com.toposcribe.app.ui.components.Dropdown
import com.toposcribe.app.ui.components.FormDialog
import com.toposcribe.app.ui.components.NumberField
import com.toposcribe.app.ui.components.TextFieldRow
import com.toposcribe.app.ui.components.toDecimalOrNull
import com.toposcribe.calc.AngleConvention
import com.toposcribe.calc.DistanceKind
import com.toposcribe.calc.HorizontalKind
import com.toposcribe.calc.ReadingType
import com.toposcribe.calc.VerticalKind
import com.toposcribe.calc.formatDegreesAsDms
import com.toposcribe.calc.parseAngleText
import com.toposcribe.data.local.entity.LevelingObservationEntity
import com.toposcribe.data.local.entity.LevelingRunEntity
import com.toposcribe.data.local.entity.ObservationEditLogEntity
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.StationSetupEntity
import java.util.Locale

private fun f3(v: Double?) = if (v == null) "" else String.format(Locale.US, "%.3f", v)

private val INSTRUMENT_OPTIONS = listOf("NIVEL" to "Nivel de ingeniero", "TEODOLITO" to "Teodolito", "CONTROL" to "Control / BM", "OTRO" to "Otro")

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// -------------------------------------------------------------------------------------------------
// Punto
// -------------------------------------------------------------------------------------------------

@Composable
fun PointDialog(point: PointEntity?, suggestedCode: String, error: String?, onDismiss: () -> Unit, onConfirm: (PointForm) -> Unit) {
    var code by remember { mutableStateOf(point?.code ?: suggestedCode) }
    var description by remember { mutableStateOf(point?.description.orEmpty()) }
    var observations by remember { mutableStateOf(point?.observations.orEmpty()) }
    var instrument by remember { mutableStateOf(point?.instrumentRef ?: "") }
    var hasElev by remember { mutableStateOf(point?.fixedElevation != null) }
    var elevation by remember { mutableStateOf(f3(point?.fixedElevation)) }
    var hasCoords by remember { mutableStateOf(point?.fixedEast != null) }
    var east by remember { mutableStateOf(f3(point?.fixedEast)) }
    var north by remember { mutableStateOf(f3(point?.fixedNorth)) }
    var coordsControl by remember { mutableStateOf(point?.isControl ?: false) }
    FormDialog(
        title = if (point == null) "Añadir punto" else "Editar punto ${point.code}", error = error, onDismiss = onDismiss,
        onConfirm = { onConfirm(PointForm(code, description, observations, instrument, hasElev, elevation, hasCoords, east, north, coordsControl)) },
    ) {
        TextFieldRow("Código (único)", code, { code = it }, keyboard = CODE_KEYBOARD)
        TextFieldRow("Descripción", description, { description = it })
        TextFieldRow("Observaciones", observations, { observations = it }, singleLine = false)
        Dropdown("Instrumento o procedimiento", INSTRUMENT_OPTIONS, INSTRUMENT_OPTIONS.firstOrNull { it.first == instrument }, { it.second }, { instrument = it?.first ?: "" }, allowNone = "(sin indicar)")
        CheckRow("Tiene cota conocida (BM / control)", hasElev) { hasElev = it }
        if (hasElev) NumberField("Cota conocida", elevation, { elevation = it })
        CheckRow("Tiene coordenadas conocidas o locales declaradas", hasCoords) { hasCoords = it }
        if (hasCoords) {
            NumberField("Este", east, { east = it })
            NumberField("Norte", north, { north = it })
            RadioRow("Coordenadas de un control conocido", coordsControl) { coordsControl = true }
            RadioRow("Coordenadas locales declaradas por el usuario", !coordsControl) { coordsControl = false }
        }
        if (point != null) Text("Los cambios quedan en el historial del punto.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// -------------------------------------------------------------------------------------------------
// Nivelación
// -------------------------------------------------------------------------------------------------

@Composable
fun RunDialog(run: LevelingRunEntity?, bmPoints: List<PointEntity>, error: String?, onDismiss: () -> Unit, onConfirm: (name: String, start: Long?, end: Long?, endKnown: String) -> Unit) {
    var name by remember { mutableStateOf(run?.name.orEmpty()) }
    var start by remember { mutableStateOf(bmPoints.firstOrNull { it.id == run?.startBmPointId }) }
    var end by remember { mutableStateOf(bmPoints.firstOrNull { it.id == run?.endBmPointId }) }
    var endKnown by remember { mutableStateOf(f3(run?.endKnownElevation)) }
    FormDialog(title = if (run == null) "Nueva nivelación" else "Editar nivelación", error = error, onDismiss = onDismiss, onConfirm = { onConfirm(name, start?.id, end?.id, endKnown) }) {
        TextFieldRow("Nombre de la nivelación", name, { name = it })
        if (run == null) {
            Dropdown("BM inicial (cota conocida)", bmPoints, start, { "${it.code} — cota ${f3(it.fixedElevation)}" }, { start = it })
            if (bmPoints.isEmpty()) Text("No hay BM: cree un punto con «Tiene cota conocida» en «Añadir punto».", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else Text("BM inicial: ${bmPoints.firstOrNull { it.id == run.startBmPointId }?.code ?: "?"}", style = MaterialTheme.typography.bodyMedium)
        Dropdown("BM final de cota conocida (opcional)", bmPoints.filter { it.id != start?.id }, end, { "${it.code} — cota ${f3(it.fixedElevation)}" }, { end = it }, allowNone = "(sin BM final)")
        NumberField("o cota de cierre conocida (opcional)", endKnown, { endKnown = it }, supporting = "Si no hay BM final, el cierre contra control externo no se puede comprobar.")
    }
}

@Composable
fun LevelReadingDialog(
    existing: LevelingObservationEntity?, existingCode: String, points: List<PointEntity>, suggestedCode: String, suggestedType: ReadingType, error: String?,
    onDismiss: () -> Unit, onConfirm: (code: String, description: String, type: ReadingType, value: String, turning: Boolean, partial: String, notes: String) -> Unit,
) {
    var code by remember { mutableStateOf(if (existing != null) existingCode else suggestedCode) }
    var description by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(existing?.readingType ?: suggestedType) }
    var value by remember { mutableStateOf(f3(existing?.readingValue)) }
    var turning by remember { mutableStateOf(existing?.isTurningPoint ?: false) }
    var partial by remember { mutableStateOf(f3(existing?.partialDistance)) }
    var notes by remember { mutableStateOf(existing?.rawNotes.orEmpty()) }
    val known = points.any { it.code.trim().equals(code.trim(), ignoreCase = true) }
    FormDialog(
        title = if (existing == null) "Añadir lectura (nivel)" else "Editar lectura", error = error, onDismiss = onDismiss,
        onConfirm = { onConfirm(code, description, type, value, turning, partial, notes) },
    ) {
        TextFieldRow("Punto (código)", code, { code = it }, keyboard = CODE_KEYBOARD)
        if (code.isNotBlank() && !known) Text("El punto «${code.trim()}» no existe: se creará como punto nuevo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (code.isNotBlank() && !known && existing == null) TextFieldRow("Descripción del punto nuevo", description, { description = it })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ReadingType.BS to "VA (atrás)", ReadingType.IS to "VI (intermedia)", ReadingType.FS to "VD (adelante)").forEach { (t, l) ->
                FilterChip(selected = type == t, onClick = { type = t }, label = { Text(l) })
            }
        }
        NumberField("Lectura de mira", value, { value = it })
        if (type != ReadingType.BS) NumberField("Distancia parcial desde el punto anterior", partial, { partial = it }, supporting = "Necesaria para el perfil longitudinal.")
        if (type == ReadingType.FS) CheckRow("Es punto de cambio (la siguiente VA parte de este punto)", turning) { turning = it }
        TextFieldRow("Observaciones", notes, { notes = it })
        if (existing != null) Text("El valor anterior queda registrado en el historial.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// -------------------------------------------------------------------------------------------------
// Teodolito
// -------------------------------------------------------------------------------------------------

@Composable
fun SetupDialog(
    existing: StationSetupEntity?, points: List<PointEntity>, hasCoords: (PointEntity) -> Boolean, error: String?,
    onDismiss: () -> Unit, onConfirm: (SetupForm) -> Unit,
) {
    var station by remember { mutableStateOf(points.firstOrNull { it.id == existing?.occupiedPointId }) }
    var ai by remember { mutableStateOf(f3(existing?.instrumentHeightAi)) }
    var label by remember { mutableStateOf(existing?.backsightPointLabel.orEmpty()) }
    var ref by remember { mutableStateOf(points.firstOrNull { it.id == existing?.backsightPointId }) }
    var az by remember { mutableStateOf(existing?.let { formatDegreesAsDms(it.baseAzimuthDecimal, 2) } ?: "") }
    var reading by remember { mutableStateOf(existing?.let { formatDegreesAsDms(it.backsightReadingDeg, 2) } ?: "0°00'00\"") }
    var convention by remember { mutableStateOf(existing?.angleConvention ?: AngleConvention.HORARIO) }
    var east by remember { mutableStateOf("") }
    var north by remember { mutableStateOf("") }
    val needsCoords = station != null && !hasCoords(station!!)
    FormDialog(
        title = if (existing == null) "Estación ocupada y orientación" else "Editar estación", error = error, onDismiss = onDismiss,
        onConfirm = { onConfirm(SetupForm(station?.id, ai, label, ref?.id, az, reading, convention, east, north)) },
    ) {
        if (existing == null) Dropdown("Estación ocupada (punto)", points, station, { it.code }, { station = it })
        else Text("Estación: ${station?.code}", style = MaterialTheme.typography.bodyMedium)
        if (points.isEmpty()) Text("No hay puntos: cree la estación con «Añadir punto».", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (needsCoords) {
            Text("La estación no tiene coordenadas. Declare las coordenadas locales (o reales) del punto ocupado:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            NumberField("Este de la estación", east, { east = it })
            NumberField("Norte de la estación", north, { north = it })
        }
        NumberField("Altura del instrumento", ai, { ai = it })
        Dropdown("Referencia de orientación (punto, opcional)", points.filter { it.id != station?.id }, ref, { it.code }, { ref = it; if (it != null && label.isBlank()) label = it.code }, allowNone = "(sin punto: solo descripción)")
        TextFieldRow("Descripción de la referencia (vista atrás)", label, { label = it })
        TextFieldRow("Azimut conocido de la referencia (°  o  G M S)", az, { az = it })
        TextFieldRow("Lectura horizontal al visar la referencia", reading, { reading = it })
        Text("Convenio de lectura angular", style = MaterialTheme.typography.labelMedium)
        AngleConvention.entries.forEach { c -> RadioRow(c.labelEs, convention == c) { convention = c } }
    }
}

@Composable
fun RadiationDialog(
    existing: PlanimetryObservationEntity?, existingTargetCode: String, setups: List<StationSetupEntity>, points: List<PointEntity>, suggestedCode: String,
    defaultSetupId: Long?, error: String?, onDismiss: () -> Unit, onConfirm: (RadiationReadingForm?, String?) -> Unit,
) {
    val stations = setups.mapNotNull { s -> points.firstOrNull { it.id == s.occupiedPointId }?.let { s to it } }
    var setup by remember { mutableStateOf(setups.firstOrNull { it.id == (existing?.stationSetupId ?: defaultSetupId) } ?: setups.lastOrNull()) }
    var code by remember { mutableStateOf(if (existing != null) existingTargetCode else suggestedCode) }
    var description by remember { mutableStateOf(points.firstOrNull { it.id == existing?.targetPointId }?.description.orEmpty()) }
    var kind by remember { mutableStateOf(if (existing?.horizontalAngle != null && existing.azimuth == null) HorizontalKind.LECTURA_HORIZONTAL else HorizontalKind.AZIMUT) }
    var angle by remember { mutableStateOf((existing?.azimuth ?: existing?.horizontalAngle)?.let { formatDegreesAsDms(it, 2) } ?: "") }
    var distKind by remember { mutableStateOf(if (existing?.slopeDistance != null) DistanceKind.INCLINADA else DistanceKind.HORIZONTAL) }
    var distance by remember { mutableStateOf(f3(existing?.slopeDistance ?: existing?.horizontalDistance)) }
    var vKind by remember { mutableStateOf(if (existing?.verticalAngle != null) VerticalKind.VERTICAL else VerticalKind.CENITAL) }
    var vAngle by remember { mutableStateOf((existing?.zenithAngle ?: existing?.verticalAngle)?.let { formatDegreesAsDms(it, 2) } ?: "") }
    var notes by remember { mutableStateOf(existing?.rawNotes.orEmpty()) }
    var localError by remember { mutableStateOf<String?>(null) }
    val targetKnown = points.any { it.code.trim().equals(code.trim(), ignoreCase = true) }

    FormDialog(
        title = if (existing == null) "Añadir lectura (teodolito)" else "Editar lectura", error = localError ?: error, onDismiss = onDismiss,
        onConfirm = {
            localError = null
            val s = setup
            if (s == null) { onConfirm(null, "Defina primero la estación ocupada con su orientación."); return@FormDialog }
            val a = parseAngleText(angle, if (kind == HorizontalKind.AZIMUT) "Azimut" else "Lectura horizontal")
            if (a.error != null) { localError = a.error; return@FormDialog }
            val d = distance.toDecimalOrNull()
            if (d == null) { localError = "«Distancia» debe ser un número."; return@FormDialog }
            var v: Double? = null
            if (distKind == DistanceKind.INCLINADA) {
                val pv = parseAngleText(vAngle, if (vKind == VerticalKind.CENITAL) "Ángulo cenital" else "Ángulo vertical")
                if (pv.error != null) { localError = pv.error; return@FormDialog }
                v = pv.degrees
            }
            onConfirm(
                RadiationReadingForm(s.occupiedPointId, s.id, code, description.ifBlank { null }, kind, a.degrees!!, d, distKind, if (distKind == DistanceKind.INCLINADA) vKind else null, v, notes.ifBlank { null }),
                null,
            )
        },
    ) {
        Dropdown("Estación ocupada", stations.map { it.first }, setup, { s -> "${points.firstOrNull { it.id == s.occupiedPointId }?.code ?: "?"} — ref. ${s.backsightPointLabel}" }, { setup = it })
        TextFieldRow("Punto visado (código)", code, { code = it }, keyboard = CODE_KEYBOARD)
        if (code.isNotBlank() && !targetKnown) Text("El punto «${code.trim()}» no existe: se creará como punto nuevo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        TextFieldRow("Descripción del punto", description, { description = it })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = kind == HorizontalKind.AZIMUT, onClick = { kind = HorizontalKind.AZIMUT }, label = { Text("Azimut") })
            FilterChip(selected = kind == HorizontalKind.LECTURA_HORIZONTAL, onClick = { kind = HorizontalKind.LECTURA_HORIZONTAL }, label = { Text("Lectura horizontal") })
        }
        TextFieldRow(if (kind == HorizontalKind.AZIMUT) "Azimut (° decimal o G M S)" else "Lectura del círculo horizontal (° o G M S)", angle, { angle = it })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = distKind == DistanceKind.HORIZONTAL, onClick = { distKind = DistanceKind.HORIZONTAL }, label = { Text("Dist. horizontal") })
            FilterChip(selected = distKind == DistanceKind.INCLINADA, onClick = { distKind = DistanceKind.INCLINADA }, label = { Text("Dist. inclinada") })
        }
        NumberField(if (distKind == DistanceKind.HORIZONTAL) "Distancia horizontal" else "Distancia inclinada", distance, { distance = it })
        if (distKind == DistanceKind.INCLINADA) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = vKind == VerticalKind.CENITAL, onClick = { vKind = VerticalKind.CENITAL }, label = { Text("Ángulo cenital") })
                FilterChip(selected = vKind == VerticalKind.VERTICAL, onClick = { vKind = VerticalKind.VERTICAL }, label = { Text("Ángulo vertical") })
            }
            TextFieldRow(if (vKind == VerticalKind.CENITAL) "Ángulo cenital (° o G M S)" else "Ángulo vertical (° o G M S)", vAngle, { vAngle = it })
        }
        TextFieldRow("Observaciones", notes, { notes = it })
        if (existing != null) Text("El valor anterior queda registrado en el historial.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// -------------------------------------------------------------------------------------------------
// Historial de correcciones
// -------------------------------------------------------------------------------------------------

@Composable
fun HistoryDialog(title: String, loader: suspend () -> List<ObservationEditLogEntity>, onDismiss: () -> Unit) {
    var items by remember { mutableStateOf<List<ObservationEditLogEntity>?>(null) }
    LaunchedEffect(Unit) { items = loader() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val list = items
                if (list == null) Text("Cargando…")
                else if (list.isEmpty()) Text("Esta lectura no tiene correcciones: el valor vigente es el original.")
                else {
                    Text("La lectura original se conserva; cada corrección guarda el valor anterior:", style = MaterialTheme.typography.bodySmall)
                    list.forEach { l ->
                        Column(Modifier.padding(vertical = 2.dp)) {
                            Text("${l.field}: ${l.oldValue ?: "—"} → ${l.newValue ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                            Text(l.editedAt.replace('T', ' ').take(19), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
