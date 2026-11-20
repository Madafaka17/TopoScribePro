package com.toposcribe.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.toposcribe.app.ui.theme.MonoFieldStyle

/**
 * Diálogo genérico para editar una fila ya guardada: un campo de texto por etiqueta, en el mismo orden.
 * [numeric] marca los índices que se muestran con fuente monoespaciada (valores numéricos).
 */
@Composable
fun EditFieldsDialog(
    title: String,
    labels: List<String>,
    initial: List<String>,
    numeric: Set<Int> = emptySet(),
    error: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val values = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, label ->
                    if (i in numeric) {
                        SelectAllTextField(
                            value = values[i],
                            onValueChange = { values[i] = it },
                            label = { Text(label) },
                            textStyle = MonoFieldStyle,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedTextField(
                            value = values[i],
                            onValueChange = { values[i] = it },
                            label = { Text(label) },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(values.toList()) }) { Text("Guardar cambios") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Número escrito con coma o punto decimal ("500,25" o "500.25"); null si está vacío o no es un número.
 * Con teclado en español el decimal es la coma: `toDoubleOrNull()` a secas lo rechazaba y el dato se perdía. */
fun String.toDecimalOrNull(): Double? = trim().replace(',', '.').toDoubleOrNull()

/** Texto que no está vacío pero tampoco es un número válido (para marcar el campo en rojo). */
fun String.isInvalidNumber(): Boolean = isNotBlank() && toDecimalOrNull() == null

/** "" -> null, "1,5" o "1.5" -> 1.5; null si no es número. */
fun String.toDoubleOrBlankNull(): Double? = trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()
