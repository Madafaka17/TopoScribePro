package com.toposcribe.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.toposcribe.app.ui.theme.MonoFieldStyle
import com.toposcribe.calc.ResultState

/** Muestra un mensaje breve (snackbar) desde cualquier pantalla. */
val LocalNotify = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

/** Teclado para códigos (A, B, EST-A, BM-01): mayúsculas y SIN autocorrector, que cambiaba lo escrito. */
val CODE_KEYBOARD = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false)
val NUMBER_KEYBOARD = KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false)
val TEXT_KEYBOARD = KeyboardOptions(autoCorrectEnabled = false)

/** Número escrito con coma o punto decimal; null si está vacío o no es un número. */
fun String.toDecimalOrNull(): Double? = trim().replace(',', '.').toDoubleOrNull()

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), error: String? = null, supporting: String? = null) {
    SelectAllTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, textStyle = MonoFieldStyle, keyboardOptions = NUMBER_KEYBOARD, singleLine = true,
        isError = error != null, supportingText = (error ?: supporting)?.let { { Text(it) } }, modifier = modifier,
    )
}

@Composable
fun TextFieldRow(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), singleLine: Boolean = true, error: String? = null, keyboard: KeyboardOptions = TEXT_KEYBOARD) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, isError = error != null,
        supportingText = error?.let { { Text(it) } }, keyboardOptions = keyboard, modifier = modifier,
    )
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun ErrorText(message: String?, modifier: Modifier = Modifier) {
    if (message != null) Text(message, modifier = modifier, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

/** Diálogo de confirmación para acciones destructivas (eliminar, retirar). */
@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String = "Eliminar", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Diálogo de formulario con desplazamiento y error del servidor. */
@Composable
fun FormDialog(
    title: String, confirmLabel: String = "Guardar", error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
                ErrorText(error)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Lista desplegable de opciones (elección única). */
@Composable
fun <T> Dropdown(label: String, options: List<T>, selected: T?, optionLabel: (T) -> String, onSelect: (T?) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), allowNone: String? = null, error: String? = null) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected?.let(optionLabel) ?: (allowNone ?: "Elegir…"), maxLines = 1)
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        ErrorText(error)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowNone != null) DropdownMenuItem(text = { Text(allowNone) }, onClick = { open = false; onSelect(null) })
            options.forEach { o -> DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { open = false; onSelect(o) }) }
            if (options.isEmpty() && allowNone == null) DropdownMenuItem(text = { Text("(sin opciones)") }, onClick = { open = false })
        }
    }
}

/** Aviso del estado de un resultado guardado: sin calcular / desactualizado / de una versión anterior. */
@Composable
fun StateBanner(state: ResultState, what: String, modifier: Modifier = Modifier) {
    val (bg, fg, icon) = when (state) {
        ResultState.ACTUALIZADO -> Triple(Color(0xFFE6F4EA), Color(0xFF1B5E20), Icons.Filled.CheckCircle)
        ResultState.SIN_CALCULAR -> Triple(Color(0xFFEFF4FF), Color(0xFF0B1C30), Icons.Filled.Warning)
        else -> Triple(Color(0xFFFFF4D6), Color(0xFF6B3F00), Icons.Filled.Warning)
    }
    Row(modifier.fillMaxWidth().background(bg).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = fg)
        Text("$what: ${state.labelEs}", Modifier.padding(start = 8.dp), color = fg, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun NoProjectMessage(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No hay un proyecto abierto", style = MaterialTheme.typography.titleMedium)
        Text("Cree o abra un proyecto en la pestaña «Proyecto» para empezar a registrar el levantamiento.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
