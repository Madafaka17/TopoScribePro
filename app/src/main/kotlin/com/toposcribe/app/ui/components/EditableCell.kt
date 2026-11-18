package com.toposcribe.app.ui.components

import kotlinx.coroutines.delay
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.toposcribe.app.ui.theme.MonoFieldStyle

/** Texto que las tablas usan para "sin dato"; al editar se interpreta como vacío. */
const val EMPTY_CELL = "—"

/** Vacío -> null (se borra el dato); número válido -> ese número; texto inválido -> se conserva [current]. */
fun String.parseNumberOr(current: Double?): Double? = if (isBlank()) null else toDoubleOrBlankNull() ?: current

/**
 * Celda de tabla editable en el sitio (como los `<input>` de las tablas del diseño): se escribe
 * directamente y el cambio se confirma al pulsar "Listo" o al salir de la celda. Solo llama a
 * [onCommit] si el texto realmente cambió; un "—" confirmado se entrega como cadena vacía.
 */
@Composable
fun EditableCell(value: String, width: Dp, mono: Boolean = false, onCommit: (String) -> Unit) {
    var field by remember(value) { mutableStateOf(TextFieldValue(value)) }
    val text = field.text
    var focused by remember { mutableStateOf(false) }

    // Al tocar la celda se selecciona todo su contenido: lo que escribes reemplaza el dato en vez de pegarse al final.
    LaunchedEffect(focused) {
        if (focused) {
            delay(80)
            field = field.copy(selection = TextRange(0, field.text.length))
        }
    }
    val focusManager = LocalFocusManager.current

    fun commit() {
        if (text != value) {
            onCommit(if (text.trim() == EMPTY_CELL) "" else text)
            // Si el cambio se guarda, `value` cambia y la celda se rehace sola con el dato nuevo. Si se
            // rechaza (texto inválido, código repetido…) `value` no cambia: volvemos al dato guardado en
            // vez de dejar en pantalla un texto que no esta en la base de datos.
            field = TextFieldValue(value)
        }
    }

    BasicTextField(
        value = field,
        onValueChange = { field = it },
        singleLine = true,
        textStyle = (if (mono) MonoFieldStyle else MaterialTheme.typography.bodySmall).copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        // Sin autocorrector: cambiaba lo escrito en códigos y descripciones por otras palabras.
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
        modifier = Modifier
            .width(width)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .onFocusChanged { state ->
                if (focused && !state.isFocused) commit()
                focused = state.isFocused
            },
        decorationBox = { inner ->
            Column {
                inner()
                HorizontalDivider(color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            }
        },
    )
}
