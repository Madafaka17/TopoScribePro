package com.toposcribe.app.ui.screens.libreta

import com.toposcribe.app.ui.components.SelectAllTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.toposcribe.app.ui.components.copyPhotoToAppFiles
import com.toposcribe.app.ui.components.loadPhotoThumbnail
import com.toposcribe.app.ui.theme.MonoFieldStyle
import com.toposcribe.calc.ReadingType
import com.toposcribe.calc.bearingToAzimuth
import com.toposcribe.data.local.entity.FixType
import com.toposcribe.data.local.entity.LevelingObservationEntity
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import java.io.File
import kotlin.math.abs

/** Teclado para códigos (A, B, EST-A, BM-01): mayúsculas por el propio teclado y SIN autocorrector, que cambiaba lo escrito por otras palabras. */
internal val CODE_KEYBOARD = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false)

/** Teclado numérico con decimal (coma o punto) y sin autocorrector. */
internal val NUMBER_KEYBOARD = KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false)

// -------------------------------------------------------------------------------------------
// Foto: cámara o galería
// -------------------------------------------------------------------------------------------

/**
 * Devuelve una función que abre el selector "Cámara / Galería". La foto elegida se copia al
 * almacenamiento privado de la app y [onPicked] recibe su ruta.
 */
@Composable
fun rememberPhotoChooser(prefix: String, onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    var showChooser by remember { mutableStateOf(false) }
    // Ruta de la foto en curso: se guarda con rememberSaveable porque la app de cámara puede
    // dejar la nuestra en segundo plano y Android recrear la actividad mientras tanto.
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = pendingPath?.let { File(it) }
        if (taken && file != null && file.exists() && file.length() > 0) onPicked(file.absolutePath)
        else file?.delete()
        pendingPath = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) copyPhotoToAppFiles(context, uri, prefix)?.let(onPicked)
    }

    if (showChooser) {
        AlertDialog(
            onDismissRequest = { showChooser = false },
            title = { Text("Fotografía del punto") },
            text = { Text("Elige de dónde tomar la imagen.") },
            confirmButton = {
                TextButton(onClick = {
                    showChooser = false
                    val dir = File(context.filesDir, "photos").apply { mkdirs() }
                    val file = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
                    pendingPath = file.absolutePath
                    try {
                        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
                    } catch (e: ActivityNotFoundException) {
                        pendingPath = null // dispositivo sin app de cámara: queda la galería
                    }
                }) { Text("Cámara") }
            },
            dismissButton = {
                TextButton(onClick = { showChooser = false; gallery.launch("image/*") }) { Text("Galería") }
            },
        )
    }
    return { showChooser = true }
}

@Composable
private fun PhotoSection(path: String?, caption: String, onPath: (String?) -> Unit, onCaption: (String) -> Unit, prefix: String) {
    val choose = rememberPhotoChooser(prefix) { onPath(it) }
    val thumbnail = remember(path) { loadPhotoThumbnail(path) }
    Text("Fotografía (cámara o galería)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    if (thumbnail != null) {
        Image(bitmap = thumbnail, contentDescription = "Foto del punto", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(140.dp))
    } else {
        Text("[Sin fotografía registrada]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = choose) { Text(if (path == null) "Añadir foto" else "Cambiar foto") }
        if (path != null) OutlinedButton(onClick = { onPath(null) }) { Text("Retirar", color = MaterialTheme.colorScheme.error) }
    }
    OutlinedTextField(value = caption, onValueChange = onCaption, label = { Text("Pie de foto") }, modifier = Modifier.fillMaxWidth())
}

// -------------------------------------------------------------------------------------------
// Utilidades de validación
// -------------------------------------------------------------------------------------------

/** Texto -> número (admite coma decimal). Vacío = null sin error; texto inválido = error. */
private fun parseOptional(text: String, label: String): Pair<Double?, String?> {
    val clean = text.trim().replace(',', '.')
    if (clean.isEmpty()) return null to null
    val v = clean.toDoubleOrNull() ?: return null to "«$label» debe ser un número."
    return v to null
}

private fun parseRequired(text: String, label: String): Pair<Double?, String?> {
    val (v, err) = parseOptional(text, label)
    return if (err == null && v == null) null to "«$label» es obligatorio." else v to err
}

/** Grados, minutos y segundos -> grados decimales; el signo lo da el campo de grados ("-5" => negativo). */
private fun parseDms(deg: String, min: String, sec: String, label: String, minValue: Double, maxValue: Double): Pair<Double?, String?> {
    val d = deg.trim().replace(',', '.').toDoubleOrNull() ?: return null to "«$label»: los grados deben ser un número."
    val m = if (min.isBlank()) 0.0 else min.trim().toDoubleOrNull() ?: return null to "«$label»: los minutos deben ser un número."
    val s = if (sec.isBlank()) 0.0 else sec.trim().replace(',', '.').toDoubleOrNull() ?: return null to "«$label»: los segundos deben ser un número."
    if (m < 0 || m >= 60) return null to "«$label»: los minutos van de 0 a 59."
    if (s < 0 || s >= 60) return null to "«$label»: los segundos van de 0 a 59.99."
    val negative = deg.trim().startsWith("-")
    val total = (if (negative) -1 else 1) * (abs(d) + m / 60.0 + s / 3600.0)
    if (total < minValue || total > maxValue) return null to "«$label» debe estar entre ${minValue.toInt()}° y ${maxValue.toInt()}°."
    return total to null
}

// -------------------------------------------------------------------------------------------
// Componentes de formulario
// -------------------------------------------------------------------------------------------

@Composable
private fun DialogColumn(content: @Composable () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    SelectAllTextField(value = value, onValueChange = onChange, label = { Text(label) }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true, modifier = modifier)
}

@Composable
private fun DmsFields(deg: String, min: String, sec: String, onDeg: (String) -> Unit, onMin: (String) -> Unit, onSec: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumberField("°", deg, Modifier.weight(1f), onDeg)
        NumberField("'", min, Modifier.weight(1f), onMin)
        NumberField("\"", sec, Modifier.weight(1f), onSec)
    }
}

/** Campo de texto con menú desplegable de sugerencias (estaciones o puntos existentes). */
@Composable
private fun CodeField(label: String, value: String, options: List<String>, isError: Boolean = false, supporting: String? = null, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            isError = isError,
            keyboardOptions = CODE_KEYBOARD,
            supportingText = supporting?.let { { Text(it) } },
            trailingIcon = {
                if (options.isNotEmpty()) IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Puntos existentes") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { code -> DropdownMenuItem(text = { Text(code) }, onClick = { onChange(code); expanded = false }) }
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
        }
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

// -------------------------------------------------------------------------------------------
// + Añadir punto — estación total
// -------------------------------------------------------------------------------------------

@Composable
fun AddStationPointDialog(
    existingCodes: List<String>,
    defaultCode: String,
    defaultStation: String,
    defaultAi: String,
    serverError: String?,
    onDismiss: () -> Unit,
    onConfirm: (StationPointForm) -> Unit,
    theodolite: Boolean = false,
) {
    var code by remember { mutableStateOf(defaultCode) }
    var station by remember { mutableStateOf(defaultStation) }
    // Teodolito: la distancia sale por estadía (hilos del retículo) o con cinta. K = constante estadimétrica (normalmente 100).
    var stadia by remember { mutableStateOf(theodolite) }
    var hs by remember { mutableStateOf("") }
    var hm by remember { mutableStateOf("") }
    var hi by remember { mutableStateOf("") }
    var stadiaK by remember { mutableStateOf("100") }
    var tramo by remember { mutableStateOf("") }
    var angleKind by remember { mutableStateOf(AngleKind.AZIMUT) }
    var deg by remember { mutableStateOf("") }
    var min by remember { mutableStateOf("0") }
    var sec by remember { mutableStateOf("0") }
    var bearing by remember { mutableStateOf("") }
    var verticalKind by remember { mutableStateOf(VerticalKind.CENITAL) }
    var vDeg by remember { mutableStateOf("90") }
    var vMin by remember { mutableStateOf("0") }
    var vSec by remember { mutableStateOf("0") }
    var horizontalDistance by remember { mutableStateOf(true) }
    var distance by remember { mutableStateOf("") }
    var ai by remember { mutableStateOf(defaultAi) }
    var ap by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var photoPath by remember { mutableStateOf<String?>(null) }
    var caption by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val duplicate = code.isNotBlank() && existingCodes.any { it.equals(code.trim(), ignoreCase = true) }

    fun submit() {
        val form = validateStationForm(
            code, station, tramo, angleKind, deg, min, sec, bearing, verticalKind, vDeg, vMin, vSec,
            horizontalDistance, distance, ai, ap, description, photoPath, caption,
            stadia = theodolite && stadia, hs = hs, hm = hm, hi = hi, k = stadiaK,
        )
        if (form.second != null) { localError = form.second; return }
        localError = null
        onConfirm(form.first!!)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (theodolite) "+ Añadir punto (teodolito)" else "+ Añadir punto (estación total)") },
        text = {
            DialogColumn {
                CodeField("Identificador del punto *", code, emptyList(), isError = duplicate, supporting = if (duplicate) "¡Ese identificador ya existe!" else "Sugerido automáticamente; puedes cambiarlo.") { code = it }
                CodeField("Estación ocupada *", station, existingCodes) { station = it }
                OutlinedTextField(value = tramo, onValueChange = { tramo = it }, label = { Text("Tramo / sector (opcional)") }, modifier = Modifier.fillMaxWidth())

                Text("Ángulo horizontal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                ChipRow(listOf(AngleKind.AZIMUT to "Azimut", AngleKind.RUMBO to "Rumbo", AngleKind.LECTURA_HORIZONTAL to "Lectura horiz."), angleKind) { angleKind = it }
                if (angleKind == AngleKind.RUMBO) {
                    OutlinedTextField(value = bearing, onValueChange = { bearing = it }, label = { Text("Rumbo (ej. S30°E o S30E)") }, keyboardOptions = CODE_KEYBOARD, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    DmsFields(deg, min, sec, { deg = it }, { min = it }, { sec = it })
                }

                Text("Ángulo vertical o cenital", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                ChipRow(listOf(VerticalKind.CENITAL to "Cenital (Z)", VerticalKind.VERTICAL to "Vertical (±)"), verticalKind) {
                    verticalKind = it
                    vDeg = if (it == VerticalKind.CENITAL) "90" else "0"
                }
                DmsFields(vDeg, vMin, vSec, { vDeg = it }, { vMin = it }, { vSec = it })

                Text("Distancia", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (theodolite) {
                    ChipRow(listOf(true to "Estadía (hilos)", false to "Cinta (horizontal)"), stadia) { stadia = it }
                } else {
                    ChipRow(listOf(true to "Horizontal", false to "Inclinada"), horizontalDistance) { horizontalDistance = it }
                }
                if (theodolite && stadia) {
                    // Lectura de la mira en los tres hilos; la distancia se calcula sola: S = K·L·sen Z, H = K·L·sen²Z.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumberField("Hilo superior [m]", hs, Modifier.weight(1f)) { hs = it }
                        NumberField("Hilo medio [m]", hm, Modifier.weight(1f)) { hm = it }
                        NumberField("Hilo inferior [m]", hi, Modifier.weight(1f)) { hi = it }
                    }
                    NumberField("Constante estadimétrica K", stadiaK) { stadiaK = it }
                    val preview = stadiaDistances(hs, hi, stadiaK, verticalKind, vDeg, vMin, vSec)
                    Text(
                        if (preview != null) "Distancia inclinada ${"%.3f".format(preview.first)} m · horizontal ${"%.3f".format(preview.second)} m"
                        else "La distancia se calcula al completar los hilos y el ángulo vertical.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    NumberField("Altura instrumento Ai [m]", ai) { ai = it }
                } else {
                    NumberField(if (theodolite) "Distancia horizontal medida con cinta [m] *" else "Distancia [m] *", distance) { distance = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumberField("Altura instrumento Ai [m]", ai, Modifier.weight(1f)) { ai = it }
                        NumberField(if (theodolite) "Altura señal [m]" else "Altura prisma Ap [m]", ap, Modifier.weight(1f)) { ap = it }
                    }
                }
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Código / descripción / observaciones") }, modifier = Modifier.fillMaxWidth())
                PhotoSection(photoPath, caption, { photoPath = it }, { caption = it }, "pt")
                ErrorText(localError ?: serverError)
            }
        },
        confirmButton = { TextButton(enabled = !duplicate, onClick = ::submit) { Text("Guardar punto") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun validateStationForm(
    code: String, station: String, tramo: String, angleKind: AngleKind, deg: String, min: String, sec: String, bearing: String,
    verticalKind: VerticalKind, vDeg: String, vMin: String, vSec: String, horizontalDistance: Boolean, distance: String,
    ai: String, ap: String, description: String, photoPath: String?, caption: String,
    stadia: Boolean = false, hs: String = "", hm: String = "", hi: String = "", k: String = "100",
): Pair<StationPointForm?, String?> {
    if (code.isBlank()) return null to "El identificador del punto es obligatorio."
    if (station.isBlank()) return null to "La estación ocupada es obligatoria."
    if (station.trim().equals(code.trim(), ignoreCase = true)) return null to "La estación ocupada y el punto no pueden ser el mismo."

    var angleDeg: Double? = null
    if (angleKind == AngleKind.RUMBO) {
        if (bearing.isBlank()) return null to "Escribe el rumbo (ej. S30E)."
        if (bearingToAzimuth(bearing) == null) return null to "El rumbo no es válido. Usa el formato S30°E, N45W…"
    } else {
        val (value, err) = parseDms(deg, min, sec, "Ángulo horizontal", 0.0, 360.0)
        if (err != null) return null to err
        angleDeg = value
        if (angleKind == AngleKind.AZIMUT && value!! >= 360.0) return null to "El azimut debe ser menor de 360°."
    }

    val (vertical, vErr) = if (verticalKind == VerticalKind.CENITAL) parseDms(vDeg, vMin, vSec, "Ángulo cenital", 0.0, 180.0)
    else parseDms(vDeg, vMin, vSec, "Ángulo vertical", -90.0, 90.0)
    if (vErr != null) return null to vErr

    val (aiValue, aiErr) = parseOptional(ai, "Altura del instrumento")
    if (aiErr != null) return null to aiErr
    if ((aiValue ?: 0.0) < 0.0) return null to "Las alturas no pueden ser negativas."

    if (stadia) {
        // Teodolito por estadía: se calcula la distancia a partir de los hilos y el ángulo vertical.
        val (hsV, hsErr) = parseRequired(hs, "Hilo superior"); if (hsErr != null) return null to hsErr
        val (hmV, hmErr) = parseRequired(hm, "Hilo medio"); if (hmErr != null) return null to hmErr
        val (hiV, hiErr) = parseRequired(hi, "Hilo inferior"); if (hiErr != null) return null to hiErr
        val (kV, kErr) = parseRequired(k, "Constante K"); if (kErr != null) return null to kErr
        if (hsV!! <= hiV!!) return null to "El hilo superior debe ser mayor que el inferior."
        if (hmV!! > hsV || hmV < hiV) return null to "El hilo medio debe quedar entre el inferior y el superior."
        if (abs(hmV - (hsV + hiV) / 2) > 0.01) return null to "Revisa los hilos: el medio debe coincidir con el promedio del superior y el inferior (diferencia máxima 1 cm)."
        if (kV!! <= 0.0) return null to "La constante K debe ser mayor que 0 (normalmente 100)."
        val distances = stadiaDistances(hs, hi, k, verticalKind, vDeg, vMin, vSec) ?: return null to "No se pudo calcular la distancia: revisa el ángulo vertical."
        val note = "Estadía: hilos S=${"%.3f".format(hsV)} M=${"%.3f".format(hmV)} I=${"%.3f".format(hiV)} K=${"%.0f".format(kV)}"
        return StationPointForm(
            code = code.trim().uppercase(), stationCode = station.trim().uppercase(), tramo = tramo.trim(),
            description = listOf(description.trim(), note).filter { it.isNotEmpty() }.joinToString(" · "),
            angleKind = angleKind, angleDeg = angleDeg, bearing = bearing.trim().uppercase(),
            verticalKind = verticalKind, verticalDeg = vertical,
            horizontalDistance = false, distance = distances.first,
            instrumentHeight = aiValue, prismHeight = hmV, photoPath = photoPath, photoCaption = caption.trim(),
        ) to null
    }

    val (dist, dErr) = parseRequired(distance, "Distancia")
    if (dErr != null) return null to dErr
    if (dist!! <= 0.0) return null to "La distancia debe ser mayor que 0."

    val (apValue, apErr) = parseOptional(ap, "Altura del prisma")
    if (apErr != null) return null to apErr
    if ((apValue ?: 0.0) < 0.0) return null to "Las alturas no pueden ser negativas."

    return StationPointForm(
        code = code.trim().uppercase(), stationCode = station.trim().uppercase(), tramo = tramo.trim(), description = description.trim(),
        angleKind = angleKind, angleDeg = angleDeg, bearing = bearing.trim().uppercase(),
        verticalKind = verticalKind, verticalDeg = vertical,
        horizontalDistance = horizontalDistance, distance = dist,
        instrumentHeight = aiValue, prismHeight = apValue, photoPath = photoPath, photoCaption = caption.trim(),
    ) to null
}

/**
 * Distancias por estadía: L = hilo superior − hilo inferior; inclinada S = K·L·sen Z; horizontal H = K·L·sen² Z.
 * Con ángulo vertical (altitud) v, Z = 90° − v. Devuelve (inclinada, horizontal) o null si faltan datos.
 */
private fun stadiaDistances(hs: String, hi: String, k: String, kind: VerticalKind, vDeg: String, vMin: String, vSec: String): Pair<Double, Double>? {
    val upper = parseOptional(hs, "").first ?: return null
    val lower = parseOptional(hi, "").first ?: return null
    val constant = parseOptional(k, "").first ?: return null
    val (angle, err) = if (kind == VerticalKind.CENITAL) parseDms(vDeg, vMin, vSec, "Z", 0.0, 180.0) else parseDms(vDeg, vMin, vSec, "V", -90.0, 90.0)
    if (err != null || angle == null) return null
    val zenith = if (kind == VerticalKind.CENITAL) angle else 90.0 - angle
    val length = upper - lower
    if (length <= 0.0 || constant <= 0.0 || zenith <= 0.0 || zenith >= 180.0) return null
    val sinZ = kotlin.math.sin(Math.toRadians(zenith))
    val slope = constant * length * sinZ
    return slope to slope * sinZ
}

// -------------------------------------------------------------------------------------------
// + Añadir punto — nivel de ingeniero
// -------------------------------------------------------------------------------------------

private val READING_OPTIONS = listOf(ReadingType.BS to "VA (atrás)", ReadingType.IS to "VI (interm.)", ReadingType.FS to "VD (adelante)")

@Composable
fun AddLevelReadingDialog(
    existingCodes: List<String>,
    defaultCode: String,
    defaultType: ReadingType,
    serverError: String?,
    onDismiss: () -> Unit,
    onConfirm: (code: String, type: ReadingType, value: Double, turning: Boolean, description: String, photoPath: String?, caption: String) -> Unit,
) {
    var code by remember { mutableStateOf(defaultCode) }
    var type by remember { mutableStateOf(defaultType) }
    var value by remember { mutableStateOf("") }
    var turning by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }
    var photoPath by remember { mutableStateOf<String?>(null) }
    var caption by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("+ Añadir punto (nivel)") },
        text = {
            DialogColumn {
                CodeField("Punto o estación *", code, existingCodes, supporting = "Usa el código de un punto existente o escribe uno nuevo.") { code = it }
                Text("Tipo de lectura", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                ChipRow(READING_OPTIONS, type) { type = it }
                NumberField("Lectura de mira [m] *", value) { value = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = turning, onCheckedChange = { turning = it })
                    Text("Cambio de estación (punto de cambio)", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Descripción / observaciones") }, modifier = Modifier.fillMaxWidth())
                PhotoSection(photoPath, caption, { photoPath = it }, { caption = it }, "pt")
                ErrorText(localError ?: serverError)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (v, err) = parseRequired(value, "Lectura de mira")
                when {
                    code.isBlank() -> localError = "El punto o estación es obligatorio."
                    err != null -> localError = err
                    v!! <= 0.0 || v > 6.0 -> localError = "La lectura de mira debe estar entre 0 y 6 m."
                    else -> { localError = null; onConfirm(code.trim().uppercase(), type, v, turning, description.trim(), photoPath, caption.trim()) }
                }
            }) { Text("Guardar punto") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// -------------------------------------------------------------------------------------------
// Editar
// -------------------------------------------------------------------------------------------

@Composable
fun EditStationObservationDialog(
    obs: PlanimetryObservationEntity,
    targetCode: String,
    serverError: String?,
    onDismiss: () -> Unit,
    onConfirm: (
        targetCode: String, tramo: String, azimuth: Double?, bearing: String, horizontalAngle: Double?,
        horizontalDistance: Double?, slopeDistance: Double?, zenith: Double?, vertical: Double?, ai: Double?, ap: Double?, description: String,
    ) -> Unit,
) {
    var code by remember { mutableStateOf(targetCode) }
    var tramo by remember { mutableStateOf(obs.tramo ?: "") }
    var azimuth by remember { mutableStateOf(obs.azimuth?.toString() ?: "") }
    var bearing by remember { mutableStateOf(obs.bearing ?: "") }
    var horizontalAngle by remember { mutableStateOf(obs.horizontalAngle?.toString() ?: "") }
    var zenith by remember { mutableStateOf(obs.zenithAngle?.toString() ?: "") }
    var vertical by remember { mutableStateOf(obs.verticalAngle?.toString() ?: "") }
    var hd by remember { mutableStateOf(obs.horizontalDistance?.toString() ?: "") }
    var sd by remember { mutableStateOf(obs.slopeDistance?.toString() ?: "") }
    var ai by remember { mutableStateOf(obs.instrumentHeight?.toString() ?: "") }
    var ap by remember { mutableStateOf(obs.prismHeight?.toString() ?: "") }
    var description by remember { mutableStateOf(obs.rawNotes ?: "") }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar observación") },
        text = {
            DialogColumn {
                CodeField("Punto visado", code, emptyList()) { code = it }
                OutlinedTextField(value = tramo, onValueChange = { tramo = it }, label = { Text("Tramo") }, modifier = Modifier.fillMaxWidth())
                NumberField("Azimut (°)", azimuth) { azimuth = it }
                OutlinedTextField(value = bearing, onValueChange = { bearing = it }, label = { Text("Rumbo (ej. S30E)") }, keyboardOptions = CODE_KEYBOARD, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumberField("Lectura horizontal / áng. interior (°)", horizontalAngle) { horizontalAngle = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField("Cenital (°)", zenith, Modifier.weight(1f)) { zenith = it }
                    NumberField("Vertical (°)", vertical, Modifier.weight(1f)) { vertical = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField("Dist. horiz. [m]", hd, Modifier.weight(1f)) { hd = it }
                    NumberField("Dist. incl. [m]", sd, Modifier.weight(1f)) { sd = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField("Ai [m]", ai, Modifier.weight(1f)) { ai = it }
                    NumberField("Ap [m]", ap, Modifier.weight(1f)) { ap = it }
                }
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Código / descripción / observaciones") }, modifier = Modifier.fillMaxWidth())
                ErrorText(localError ?: serverError)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val az = parseOptional(azimuth, "Azimut")
                val ha = parseOptional(horizontalAngle, "Lectura horizontal")
                val zen = parseOptional(zenith, "Cenital")
                val ver = parseOptional(vertical, "Vertical")
                val hDist = parseOptional(hd, "Distancia horizontal")
                val sDist = parseOptional(sd, "Distancia inclinada")
                val aiV = parseOptional(ai, "Ai")
                val apV = parseOptional(ap, "Ap")
                val firstError = listOf(az, ha, zen, ver, hDist, sDist, aiV, apV).firstNotNullOfOrNull { it.second }
                when {
                    firstError != null -> localError = firstError
                    az.first != null && (az.first!! < 0.0 || az.first!! >= 360.0) -> localError = "El azimut debe estar entre 0° y 360°."
                    ha.first != null && (ha.first!! < 0.0 || ha.first!! > 360.0) -> localError = "La lectura horizontal debe estar entre 0° y 360°."
                    zen.first != null && (zen.first!! < 0.0 || zen.first!! > 180.0) -> localError = "El cenital debe estar entre 0° y 180°."
                    ver.first != null && (ver.first!! < -90.0 || ver.first!! > 90.0) -> localError = "El ángulo vertical debe estar entre -90° y 90°."
                    bearing.isNotBlank() && bearingToAzimuth(bearing) == null -> localError = "El rumbo no es válido. Usa el formato S30°E, N45W…"
                    (hDist.first ?: 1.0) <= 0.0 || (sDist.first ?: 1.0) <= 0.0 -> localError = "Las distancias deben ser mayores que 0."
                    hDist.first == null && sDist.first == null -> localError = "Indica la distancia horizontal o la inclinada."
                    az.first == null && bearing.isBlank() && ha.first == null -> localError = "Indica el azimut, el rumbo o la lectura horizontal."
                    code.isBlank() -> localError = "El punto visado es obligatorio."
                    else -> {
                        localError = null
                        onConfirm(code, tramo, az.first, bearing, ha.first, hDist.first, sDist.first, zen.first, ver.first, aiV.first, apV.first, description)
                    }
                }
            }) { Text("Guardar cambios") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun EditLevelReadingDialog(
    reading: LevelingObservationEntity,
    pointCode: String,
    existingCodes: List<String>,
    serverError: String?,
    onDismiss: () -> Unit,
    onConfirm: (code: String, type: ReadingType, value: Double, turning: Boolean, notes: String) -> Unit,
) {
    var code by remember { mutableStateOf(pointCode) }
    var type by remember { mutableStateOf(reading.readingType) }
    var value by remember { mutableStateOf(reading.readingValue.toString()) }
    var turning by remember { mutableStateOf(reading.isTurningPoint) }
    var notes by remember { mutableStateOf(reading.rawNotes ?: "") }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar lectura") },
        text = {
            DialogColumn {
                CodeField("Punto o estación", code, existingCodes) { code = it }
                ChipRow(READING_OPTIONS, type) { type = it }
                NumberField("Lectura de mira [m]", value) { value = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = turning, onCheckedChange = { turning = it })
                    Text("Cambio de estación", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Observaciones") }, modifier = Modifier.fillMaxWidth())
                ErrorText(localError ?: serverError)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (v, err) = parseRequired(value, "Lectura de mira")
                when {
                    code.isBlank() -> localError = "El punto o estación es obligatorio."
                    err != null -> localError = err
                    v!! <= 0.0 || v > 6.0 -> localError = "La lectura de mira debe estar entre 0 y 6 m."
                    else -> { localError = null; onConfirm(code.trim(), type, v, turning, notes) }
                }
            }) { Text("Guardar cambios") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// -------------------------------------------------------------------------------------------
// GPS diferencial (GNSS): alta y edición de un punto medido
// -------------------------------------------------------------------------------------------

/** Texto de cada tipo de solución con su precisión típica. */
fun fixLabel(fix: FixType?): String = when (fix) {
    FixType.FIXED -> "RTK Fixed"
    FixType.FLOAT -> "RTK Float"
    FixType.DGPS -> "DGPS"
    FixType.AUTONOMO -> "Autónomo"
    null -> "—"
}

private fun fixAccuracy(fix: FixType): String = when (fix) {
    FixType.FIXED -> "RTK Fixed: precisión de unos ±0,01 m."
    FixType.FLOAT -> "RTK Float: precisión de unos ±0,20 m. Conviene esperar a Fixed para puntos de control."
    FixType.DGPS -> "DGPS (diferencial por código): precisión de 0,5 a 1 m."
    FixType.AUTONOMO -> "Autónomo (single): precisión de unos ±2,5 m. No sirve para trabajos de precisión."
}

/** Valores de partida al editar un punto GNSS ya guardado. */
data class GnssInitial(
    val code: String, val tramo: String, val description: String, val fix: FixType,
    val east: String, val north: String, val elevation: String,
    val pdop: String, val satellites: String, val antennaHeight: String, val caption: String,
)

@Composable
fun GnssPointDialog(
    title: String,
    existingCodes: List<String>,
    defaultCode: String,
    defaultFix: FixType,
    defaultAntenna: String,
    initial: GnssInitial?,
    withPhoto: Boolean,
    serverError: String?,
    onDismiss: () -> Unit,
    onConfirm: (GnssPointForm) -> Unit,
) {
    var code by remember { mutableStateOf(initial?.code ?: defaultCode) }
    var fix by remember { mutableStateOf(initial?.fix ?: defaultFix) }
    var east by remember { mutableStateOf(initial?.east ?: "") }
    var north by remember { mutableStateOf(initial?.north ?: "") }
    var elevation by remember { mutableStateOf(initial?.elevation ?: "") }
    var pdop by remember { mutableStateOf(initial?.pdop ?: "") }
    var satellites by remember { mutableStateOf(initial?.satellites ?: "") }
    var antenna by remember { mutableStateOf(initial?.antennaHeight ?: defaultAntenna) }
    var tramo by remember { mutableStateOf(initial?.tramo ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var photoPath by remember { mutableStateOf<String?>(null) }
    var caption by remember { mutableStateOf(initial?.caption ?: "") }
    var localError by remember { mutableStateOf<String?>(null) }

    // Al editar, el propio código del punto no cuenta como repetido.
    val duplicate = code.isNotBlank() && code.trim() != initial?.code && existingCodes.any { it.equals(code.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            DialogColumn {
                CodeField("Identificador del punto *", code, emptyList(), isError = duplicate, supporting = if (duplicate) "¡Ese identificador ya existe!" else "Sugerido automáticamente; puedes cambiarlo.") { code = it }
                Text("Solución del receptor", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                ChipRow(listOf(FixType.FIXED to "RTK Fixed", FixType.FLOAT to "RTK Float", FixType.DGPS to "DGPS", FixType.AUTONOMO to "Autónomo"), fix) { fix = it }
                Text(
                    fixAccuracy(fix),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fix == FixType.FIXED) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
                NumberField("Este (X) [m] *", east) { east = it }
                NumberField("Norte (Y) [m] *", north) { north = it }
                NumberField("Cota / elevación (Z) [m]", elevation) { elevation = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField("PDOP", pdop, Modifier.weight(1f)) { pdop = it }
                    NumberField("Satélites", satellites, Modifier.weight(1f)) { satellites = it }
                }
                NumberField("Altura de antena [m]", antenna) { antenna = it }
                OutlinedTextField(value = tramo, onValueChange = { tramo = it }, label = { Text("Tramo / sector (opcional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Código / descripción / observaciones") }, modifier = Modifier.fillMaxWidth())
                if (withPhoto) {
                    PhotoSection(photoPath, caption, { photoPath = it }, { caption = it }, "pt")
                } else {
                    OutlinedTextField(value = caption, onValueChange = { caption = it }, label = { Text("Pie de foto") }, modifier = Modifier.fillMaxWidth())
                }
                ErrorText(localError ?: serverError)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !duplicate,
                onClick = {
                    val e = parseRequired(east, "Este (X)")
                    val n = parseRequired(north, "Norte (Y)")
                    val z = parseOptional(elevation, "Cota")
                    val dop = parseOptional(pdop, "PDOP")
                    val ant = parseOptional(antenna, "Altura de antena")
                    val sat = satellites.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: -1 }
                    val firstError = listOf(e, n, z, dop, ant).firstNotNullOfOrNull { it.second }
                    localError = when {
                        code.isBlank() -> "El identificador del punto es obligatorio."
                        firstError != null -> firstError
                        (dop.first ?: 0.0) < 0.0 || (dop.first ?: 0.0) > 99.0 -> "El PDOP debe estar entre 0 y 99."
                        sat != null && (sat < 0 || sat > 60) -> "Los satélites deben ser un número entero de 0 a 60."
                        (ant.first ?: 0.0) < 0.0 || (ant.first ?: 0.0) > 10.0 -> "La altura de antena debe estar entre 0 y 10 m."
                        else -> null
                    }
                    if (localError == null) {
                        onConfirm(
                            GnssPointForm(
                                code = if (initial == null) code.trim().uppercase() else code.trim(),
                                tramo = tramo.trim(), description = description.trim(), fix = fix,
                                east = e.first!!, north = n.first!!, elevation = z.first,
                                pdop = dop.first, satellites = sat, antennaHeight = ant.first,
                                photoPath = photoPath, photoCaption = caption.trim(),
                            ),
                        )
                    }
                },
            ) { Text(if (initial == null) "Guardar punto" else "Guardar cambios") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}