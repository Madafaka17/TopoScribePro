package com.toposcribe.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.toposcribe.app.ui.theme.MonoFieldStyle

/** Datos del modal "Registro de Nuevo Vértice / Punto Topográfico" (modo libreta o BM fijo). */
data class NewPointForm(
    val code: String,
    val tramo: String,
    val description: String,
    val isControl: Boolean,
    // Modo libreta
    val stationCode: String = "",
    val horizontalDistance: Boolean = true,
    val distance: Double? = null,
    val useBearing: Boolean = false,
    val azimuthDeg: Double? = null,
    val bearing: String = "",
    val zenithDeg: Double? = null,
    val instrumentHeight: Double? = null,
    val prismHeight: Double? = null,
    // Modo control
    val east: Double? = null,
    val north: Double? = null,
    val elevation: Double? = null,
    // Foto vinculada al punto
    val photoPath: String? = null,
    val photoCaption: String = "",
)

private fun dmsToDecimal(deg: String, min: String, sec: String): Double? {
    val d = deg.toDecimalOrNull() ?: return null
    return d + (min.toDecimalOrNull() ?: 0.0) / 60.0 + (sec.toDecimalOrNull() ?: 0.0) / 3600.0
}

@Composable
fun AddPointDialog(
    error: String?,
    existingCodes: Set<String>,
    defaultStation: String,
    onDismiss: () -> Unit,
    onConfirm: (NewPointForm) -> Unit,
) {
    val context = LocalContext.current
    var controlMode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var tramo by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    var station by remember { mutableStateOf(defaultStation) }
    var horizontalDist by remember { mutableStateOf(true) }
    var distance by remember { mutableStateOf("50.000") }
    var useBearing by remember { mutableStateOf(false) }
    var deg by remember { mutableStateOf("45") }
    var min by remember { mutableStateOf("0") }
    var sec by remember { mutableStateOf("0") }
    var bearing by remember { mutableStateOf("") }
    var zenDeg by remember { mutableStateOf("90") }
    var zenMin by remember { mutableStateOf("0") }
    var ai by remember { mutableStateOf("1.550") }
    var ap by remember { mutableStateOf("1.600") }

    var east by remember { mutableStateOf("500.000") }
    var north by remember { mutableStateOf("1000.000") }
    var elevation by remember { mutableStateOf("100.000") }

    var photoPath by remember { mutableStateOf<String?>(null) }
    var caption by remember { mutableStateOf("") }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) photoPath = copyPhotoToAppFiles(context, uri, "pt") ?: photoPath
    }
    val thumbnail = remember(photoPath) { loadPhotoThumbnail(photoPath) }

    val duplicate = code.isNotBlank() && existingCodes.any { it.equals(code.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registro de Nuevo Vértice / Punto Topográfico") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MODO DE INGRESO DE DATOS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !controlMode, onClick = { controlMode = false })
                    Text("Observación de Libreta (Ángulo/Dist)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = controlMode, onClick = { controlMode = true })
                    Text("BM / Vértice Fijo (Coordenadas E, N, Z)", style = MaterialTheme.typography.bodySmall)
                }

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    label = { Text("Código de Punto / Vértice *") },
                    isError = duplicate,
                    supportingText = { if (duplicate) Text("¡Código ya existe!") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(value = tramo, onValueChange = { tramo = it }, label = { Text("Tramo / Sector (opcional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Descripción / Detalle de Vértice") }, modifier = Modifier.fillMaxWidth())

                if (!controlMode) {
                    Text("Datos de Observación Angular y Métrica", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(value = station, onValueChange = { station = it.uppercase() }, label = { Text("Estación Base *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { horizontalDist = !horizontalDist }) { Text(if (horizontalDist) "Horizontal (d)" else "Inclinada (Di)") }
                        OutlinedTextField(value = distance, onValueChange = { distance = it }, label = { Text("Distancia [m] *") }, textStyle = MonoFieldStyle, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedButton(onClick = { useBearing = !useBearing }) { Text(if (useBearing) "Tipo de ángulo: Rumbo" else "Tipo de ángulo: Azimut (0-360°)") }
                    if (useBearing) {
                        OutlinedTextField(value = bearing, onValueChange = { bearing = it.uppercase() }, label = { Text("Rumbo (ej. S30E)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(value = deg, onValueChange = { deg = it }, label = { Text("°") }, textStyle = MonoFieldStyle, singleLine = true, modifier = Modifier.width(80.dp))
                            OutlinedTextField(value = min, onValueChange = { min = it }, label = { Text("'") }, textStyle = MonoFieldStyle, singleLine = true, modifier = Modifier.width(80.dp))
                            OutlinedTextField(value = sec, onValueChange = { sec = it }, label = { Text("\"") }, textStyle = MonoFieldStyle, singleLine = true, modifier = Modifier.width(80.dp))
                        }
                    }
                    Text("Ángulo Cenital (Z)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = zenDeg, onValueChange = { zenDeg = it }, label = { Text("°") }, textStyle = MonoFieldStyle, singleLine = true, modifier = Modifier.width(80.dp))
                        OutlinedTextField(value = zenMin, onValueChange = { zenMin = it }, label = { Text("'") }, textStyle = MonoFieldStyle, singleLine = true, modifier = Modifier.width(80.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = ai, onValueChange = { ai = it }, label = { Text("Ai [m]") }, textStyle = MonoFieldStyle, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = ap, onValueChange = { ap = it }, label = { Text("Ap [m]") }, textStyle = MonoFieldStyle, singleLine = true, modifier = Modifier.weight(1f))
                    }
                } else {
                    Text("Coordenadas Geodésicas / Locales Directas", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    OutlinedTextField(value = east, onValueChange = { east = it }, label = { Text("Coordenada Este (X) [m]") }, textStyle = MonoFieldStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = north, onValueChange = { north = it }, label = { Text("Coordenada Norte (Y) [m]") }, textStyle = MonoFieldStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = elevation, onValueChange = { elevation = it }, label = { Text("Cota Elevación (Z) [m]") }, textStyle = MonoFieldStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                Text("Fotografía de Campo Vinculada al Punto", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (thumbnail != null) {
                    Image(bitmap = thumbnail, contentDescription = "Foto de campo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(140.dp))
                } else {
                    Text("[Sin fotografía registrada]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { photoLauncher.launch("image/*") }) { Text("Subir / Tomar Foto") }
                    if (photoPath != null) OutlinedButton(onClick = { photoPath = null }) { Text("Retirar Foto", color = MaterialTheme.colorScheme.error) }
                }
                OutlinedTextField(value = caption, onValueChange = { caption = it }, label = { Text("Pie de Foto / Descripción Fotográfica") }, modifier = Modifier.fillMaxWidth())

                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !duplicate,
                onClick = {
                    onConfirm(
                        NewPointForm(
                            code = code.trim(),
                            tramo = tramo,
                            description = description,
                            isControl = controlMode,
                            stationCode = station.trim(),
                            horizontalDistance = horizontalDist,
                            distance = distance.toDecimalOrNull(),
                            useBearing = useBearing,
                            azimuthDeg = if (useBearing) null else dmsToDecimal(deg, min, sec),
                            bearing = bearing.trim(),
                            zenithDeg = dmsToDecimal(zenDeg, zenMin, "0"),
                            instrumentHeight = ai.toDecimalOrNull(),
                            prismHeight = ap.toDecimalOrNull(),
                            east = east.toDecimalOrNull(),
                            north = north.toDecimalOrNull(),
                            elevation = elevation.toDecimalOrNull(),
                            photoPath = photoPath,
                            photoCaption = caption,
                        ),
                    )
                },
            ) { Text("Guardar Punto") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
