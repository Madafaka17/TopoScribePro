package com.toposcribe.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/** Copia la imagen elegida a almacenamiento privado de la app (el Uri del selector no es permanente). Devuelve la ruta o null. */
fun copyPhotoToAppFiles(context: Context, uri: Uri, prefix: String): String? = try {
    val dir = File(context.filesDir, "photos").apply { mkdirs() }
    val target = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
        ?.let { target.absolutePath }
} catch (e: Exception) {
    null
}

/** Miniatura reducida de una foto guardada (para vistas previas); null si el archivo no existe o no se puede leer. */
fun loadPhotoThumbnail(path: String?, maxSidePx: Int = 600): androidx.compose.ui.graphics.ImageBitmap? {
    if (path.isNullOrBlank() || !File(path).exists()) return null
    return try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSidePx || bounds.outHeight / sample > maxSidePx) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}
