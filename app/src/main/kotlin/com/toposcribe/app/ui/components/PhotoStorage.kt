package com.toposcribe.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.toposcribe.data.backup.resolveStoredFile
import com.toposcribe.io.decodeOriented
import com.toposcribe.io.saveDownscaledJpeg
import java.io.File

fun photosDir(context: Context): File = File(context.filesDir, "photos").apply { mkdirs() }

/**
 * Copia la imagen elegida al almacenamiento privado de la app (el Uri del selector no es
 * permanente), reduciéndola y aplicando su orientación. Devuelve la ruta o null si no se pudo leer.
 */
fun copyPhotoToAppFiles(context: Context, uri: Uri, prefix: String): String? = try {
    val raw = File(context.cacheDir, "pick_${System.currentTimeMillis()}.tmp")
    context.contentResolver.openInputStream(uri)?.use { input -> raw.outputStream().use { input.copyTo(it) } }
    val target = File(photosDir(context), "${prefix}_${System.currentTimeMillis()}.jpg")
    val ok = raw.length() > 0 && saveDownscaledJpeg(raw, target)
    raw.delete()
    if (ok) target.absolutePath else { target.delete(); null }
} catch (e: Exception) {
    null
}

/** Imagen reducida de una foto guardada (para vistas previas); null si no existe o no se puede leer. */
fun loadPhotoImage(context: Context, path: String?, maxSidePx: Int = 900): ImageBitmap? {
    val file = resolveStoredFile(path, photosDir(context)) ?: return null
    return decodeOriented(file, maxSidePx)?.asImageBitmap()
}

/**
 * Devuelve una función que abre el selector «Cámara / Galería». La foto se copia al almacenamiento
 * privado y [onPicked] recibe la ruta; [onError] recibe el mensaje si la imagen no se pudo leer.
 */
@Composable
fun rememberPhotoChooser(prefix: String, onError: (String) -> Unit = {}, onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    var showChooser by remember { mutableStateOf(false) }
    // La app de cámara puede dejarnos en segundo plano y Android recrear la actividad: la ruta en curso se conserva.
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = pendingPath?.let { File(it) }
        if (taken && file != null && file.exists() && file.length() > 0) {
            val final = File(photosDir(context), "${prefix}_${System.currentTimeMillis()}.jpg")
            if (saveDownscaledJpeg(file, final)) onPicked(final.absolutePath) else onError("La fotografía no se pudo procesar.")
        }
        file?.delete()
        pendingPath = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val path = copyPhotoToAppFiles(context, uri, prefix)
            if (path != null) onPicked(path) else onError("No se pudo leer la imagen elegida (archivo dañado o formato no compatible).")
        }
    }

    if (showChooser) {
        AlertDialog(
            onDismissRequest = { showChooser = false },
            title = { Text("Fotografía del punto") },
            text = { Text("Elige de dónde tomar la imagen.") },
            confirmButton = {
                TextButton(onClick = {
                    showChooser = false
                    val file = File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
                    pendingPath = file.absolutePath
                    try {
                        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
                    } catch (e: ActivityNotFoundException) {
                        pendingPath = null
                        onError("Este dispositivo no tiene una aplicación de cámara; use la galería.")
                    }
                }) { Text("Cámara") }
            },
            dismissButton = { TextButton(onClick = { showChooser = false; gallery.launch("image/*") }) { Text("Galería / archivos") } },
        )
    }
    return { showChooser = true }
}
