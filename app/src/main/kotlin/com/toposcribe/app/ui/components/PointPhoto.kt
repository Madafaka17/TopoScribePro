package com.toposcribe.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.toposcribe.data.local.entity.PointEntity

/**
 * Fotografía asociada a UN punto: «Agregar fotografía» (cámara o galería), pie de foto, ver,
 * reemplazar y retirar (con confirmación). La imagen se muestra COMPLETA (sin recortar).
 */
@Composable
fun PointPhotoSection(
    point: PointEntity,
    onSetPhoto: (String) -> Unit,
    onRemovePhoto: () -> Unit,
    onSaveCaption: (String) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val image = remember(point.sketchPhotoPath) { loadPhotoImage(context, point.sketchPhotoPath, 1000) }
    val hasPhoto = point.sketchPhotoPath != null
    var caption by remember(point.id, point.photoCaption) { mutableStateOf(point.photoCaption.orEmpty()) }
    var confirmRemove by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf(false) }
    val choose = rememberPhotoChooser("pt${point.id}", onError = onMessage) { path -> onSetPhoto(path) }

    if (confirmRemove) {
        ConfirmDialog("Retirar fotografía", "Se quitará la fotografía del punto ${point.code} y su pie de foto. ¿Continuar?", "Retirar", onConfirm = { confirmRemove = false; onRemovePhoto() }, onDismiss = { confirmRemove = false })
    }
    if (viewing && image != null) PhotoViewerDialog(point.code, point.photoCaption, point.sketchPhotoPath) { viewing = false }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (image != null) {
            Image(
                bitmap = image, contentDescription = "Fotografía del punto ${point.code}", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clickable { viewing = true }.testTag("foto_${point.code}"),
            )
        } else {
            Text(
                if (hasPhoto) "La fotografía registrada ya no está disponible en este dispositivo." else "Sin fotografía registrada",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = choose) { Text(if (hasPhoto) "Reemplazar fotografía" else "Agregar fotografía") }
            if (image != null) OutlinedButton(onClick = { viewing = true }) { Text("Ver") }
            if (hasPhoto) OutlinedButton(onClick = { confirmRemove = true }) { Text("Retirar", color = MaterialTheme.colorScheme.error) }
        }
        if (hasPhoto) {
            TextFieldRow("Pie de foto", caption, { caption = it })
            if (caption != point.photoCaption.orEmpty()) TextButton(onClick = { onSaveCaption(caption) }) { Text("Guardar pie de foto") }
        }
    }
}

@Composable
fun PhotoViewerDialog(code: String, caption: String?, path: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val image = remember(path) { loadPhotoImage(context, path, 2000) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Punto $code") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (image != null) Image(bitmap = image, contentDescription = "Fotografía del punto $code", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                else Text("La imagen no está disponible.", color = MaterialTheme.colorScheme.error)
                if (!caption.isNullOrBlank()) Text(caption, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
