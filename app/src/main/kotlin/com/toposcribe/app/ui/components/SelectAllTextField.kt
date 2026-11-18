package com.toposcribe.app.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay

/**
 * Campo de texto que, al tocarlo, deja TODO el contenido seleccionado: lo que escribes reemplaza el
 * valor que ya había. Sin esto, el cursor queda al final y el número nuevo se mezcla con el viejo
 * ("90" + "85" = "9085"), que se veía como "pongo un dato y se pone otro". Para ajustar solo una
 * parte, se toca de nuevo y el cursor se coloca donde se toca.
 */
@Composable
fun SelectAllTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    textStyle: TextStyle = LocalTextStyle.current,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    isError: Boolean = false,
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    var focused by remember { mutableStateOf(false) }
    val currentValue by rememberUpdatedState(value)

    // Si el valor cambia desde fuera (otro botón lo rellena, se guardó y se reformateó), se muestra ese.
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))

    // Un instante después de recibir el foco (cuando el toque ya colocó el cursor) se selecciona todo.
    LaunchedEffect(focused) {
        if (focused) {
            delay(80)
            val text = currentValue
            field = TextFieldValue(text, TextRange(0, text.length))
        }
    }

    OutlinedTextField(
        value = shown,
        onValueChange = {
            field = it
            if (it.text != value) onValueChange(it.text)
        },
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        label = label,
        placeholder = placeholder,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        isError = isError,
    )
}
