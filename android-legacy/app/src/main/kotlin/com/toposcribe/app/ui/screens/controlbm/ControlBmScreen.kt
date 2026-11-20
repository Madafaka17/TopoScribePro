package com.toposcribe.app.ui.screens.controlbm

import com.toposcribe.app.ui.components.isInvalidNumber
import com.toposcribe.app.ui.components.toDecimalOrNull
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toposcribe.app.ui.components.EMPTY_CELL
import com.toposcribe.app.ui.components.EditableCell
import com.toposcribe.app.ui.components.parseNumberOr
import com.toposcribe.app.ui.components.toDoubleOrBlankNull
import com.toposcribe.app.ui.theme.MonoFieldStyle
import com.toposcribe.data.local.entity.PointEntity

@Composable
fun ControlBmScreen(modifier: Modifier = Modifier, viewModel: ControlBmViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val error by viewModel.error.collectAsState()
    val systemLabel by viewModel.systemLabel.collectAsState()

    var code by remember { mutableStateOf("") }
    var tramo by remember { mutableStateOf("") }
    var east by remember { mutableStateOf("") }
    var north by remember { mutableStateOf("") }
    var elevation by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }

    var editing by remember { mutableStateOf<PointEntity?>(null) }
    editing?.let { p ->
        com.toposcribe.app.ui.components.EditFieldsDialog(
            title = "Editar punto ${p.code}",
            labels = listOf("Código BM", "Tramo / Sector", "Este (X) [m]", "Norte (Y) [m]", "Cota (Z) [m]", "Descripción"),
            initial = listOf(p.code, p.tramoSector ?: "", p.fixedEast?.toString() ?: "", p.fixedNorth?.toString() ?: "", p.fixedElevation?.toString() ?: "", p.description ?: ""),
            numeric = setOf(2, 3, 4),
            error = error,
            onDismiss = { editing = null; viewModel.clearError() },
            onConfirm = { v ->
                viewModel.updatePoint(p, v[0], v[1], v[2].parseNumberOr(p.fixedEast), v[3].parseNumberOr(p.fixedNorth), v[4].parseNumberOr(p.fixedElevation), v[5])
                editing = null
            },
        )
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var photoTarget by remember { mutableStateOf<Long?>(null) }
    val photoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        val id = photoTarget
        if (uri != null && id != null) {
            com.toposcribe.app.ui.components.copyPhotoToAppFiles(context, uri, "bm$id")?.let { viewModel.setPhoto(id, it) }
        }
        photoTarget = null
    }

    LazyColumn(modifier = modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Puntos de Control y Bancos de Nivel (BMs)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Vértices conocidos fijos con coordenadas oficiales para partida y orientación.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (error != null) {
                        Text(
                            "ALERTA DE ERROR: ${error ?: ""}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    // Los campos se reparten el ancho (antes tenian ancho fijo y se cortaban en pantallas estrechas).
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Código BM") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = tramo, onValueChange = { tramo = it }, label = { Text("Tramo / Sector") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = east, onValueChange = { east = it }, label = { Text("Este (X)") }, textStyle = MonoFieldStyle, singleLine = true, isError = east.isInvalidNumber(), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = north, onValueChange = { north = it }, label = { Text("Norte (Y)") }, textStyle = MonoFieldStyle, singleLine = true, isError = north.isInvalidNumber(), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = elevation, onValueChange = { elevation = it }, label = { Text("Cota (Z)") }, textStyle = MonoFieldStyle, singleLine = true, isError = elevation.isInvalidNumber(), modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
                    if (formError != null) Text(formError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        val e = east.toDecimalOrNull()
                        val n = north.toDecimalOrNull()
                        val z = elevation.toDecimalOrNull()
                        formError = when {
                            code.isBlank() -> "El código del punto es obligatorio."
                            east.isInvalidNumber() || north.isInvalidNumber() || elevation.isInvalidNumber() -> "Este, Norte y Cota deben ser números (usa coma o punto decimal)."
                            e == null && n == null && z == null -> "Indica al menos las coordenadas Este y Norte, o la Cota."
                            (e == null) != (n == null) -> "Este y Norte van juntos: completa los dos o ninguno."
                            else -> null
                        }
                        if (formError == null) {
                            viewModel.addControlPoint(code.trim(), tramo, e, n, z, description) {
                                // Solo se limpia el formulario cuando el punto se guardó de verdad.
                                code = ""; tramo = ""; east = ""; north = ""; elevation = ""; description = ""
                            }
                        }
                    }) {
                        Icon(Icons.Filled.AddCircle, contentDescription = null)
                        Text(" Agregar BM / Control")
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                val scroll = rememberScrollState()
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.horizontalScroll(scroll)) {
                        TableHeaderCell("Código", 90.dp)
                        TableHeaderCell("Tramo", 90.dp)
                        TableHeaderCell("Este", 90.dp)
                        TableHeaderCell("Norte", 90.dp)
                        TableHeaderCell("Cota", 90.dp)
                        TableHeaderCell("Sistema", 90.dp)
                        TableHeaderCell("Descripción", 160.dp)
                        TableHeaderCell("Foto", 60.dp)
                        TableHeaderCell("Acción", 190.dp)
                    }
                    if (state.points.isEmpty() && !state.isLoading) {
                        Text("Todavía no hay puntos de control registrados.", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.points.forEach { point ->
                        Row(Modifier.horizontalScroll(scroll)) {
                            // Edición en la propia celda: cada una reenvía la fila completa con su campo cambiado.
                            fun save(
                                code: String = point.code,
                                tramo: String = point.tramoSector ?: "",
                                east: Double? = point.fixedEast,
                                north: Double? = point.fixedNorth,
                                elevation: Double? = point.fixedElevation,
                                description: String = point.description ?: "",
                            ) = viewModel.updatePoint(point, code, tramo, east, north, elevation, description)

                            EditableCell(point.code, 90.dp, mono = true) { save(code = it) }
                            EditableCell(point.tramoSector ?: EMPTY_CELL, 90.dp) { save(tramo = it) }
                            EditableCell(fmt(point.fixedEast), 90.dp, mono = true) { save(east = it.parseNumberOr(point.fixedEast)) }
                            EditableCell(fmt(point.fixedNorth), 90.dp, mono = true) { save(north = it.parseNumberOr(point.fixedNorth)) }
                            EditableCell(fmt(point.fixedElevation), 90.dp, mono = true) { save(elevation = it.parseNumberOr(point.fixedElevation)) }
                            TableCell(systemLabel, 90.dp)
                            EditableCell(point.description ?: EMPTY_CELL, 160.dp) { save(description = it) }
                            androidx.compose.foundation.layout.Box(Modifier.width(60.dp)) {
                                androidx.compose.material3.TextButton(onClick = { photoTarget = point.id; photoLauncher.launch("image/*") }) {
                                    Text(if (point.sketchPhotoPath != null) "Sí" else "＋")
                                }
                            }
                            androidx.compose.foundation.layout.Box(Modifier.width(190.dp).padding(2.dp)) {
                                Row {
                                    androidx.compose.material3.TextButton(onClick = { editing = point }) { Text("Editar") }
                                    OutlinedButton(onClick = { viewModel.deletePoint(point) }) { Text("Eliminar") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fmt(value: Double?): String = value?.let { "%.3f".format(it) } ?: "—"

@Composable
private fun TableHeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        modifier = Modifier.width(width).padding(4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TableCell(text: String, width: androidx.compose.ui.unit.Dp, mono: Boolean = false) {
    Text(
        text,
        modifier = Modifier.width(width).padding(4.dp),
        style = if (mono) MonoFieldStyle else MaterialTheme.typography.bodySmall,
    )
}
