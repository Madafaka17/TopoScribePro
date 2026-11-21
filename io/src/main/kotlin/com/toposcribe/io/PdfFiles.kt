package com.toposcribe.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** Lectura de PDF con android.graphics.pdf.PdfRenderer: número de páginas y página → bitmap. */
object PdfFiles {

    /** Número de páginas, o null si el PDF está dañado, vacío o protegido con contraseña. */
    fun pageCount(file: File): Int? = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r -> r.pageCount.takeIf { it > 0 } }
        }
    } catch (e: Exception) {
        null
    }

    /** Renderiza varias páginas (índices base 0) abriendo el archivo una sola vez; null donde falle. */
    fun renderPages(file: File, indices: List<Int>, targetWidthPx: Int, onPage: (index: Int, bitmap: Bitmap?) -> Unit) {
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    for (i in indices) {
                        if (i < 0 || i >= renderer.pageCount) { onPage(i, null); continue }
                        val bmp = try {
                            renderer.openPage(i).use { page ->
                                val w = targetWidthPx.coerceAtLeast(64)
                                val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(64)
                                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                b.eraseColor(Color.WHITE)
                                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                b
                            }
                        } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
                        onPage(i, bmp)
                    }
                }
            }
        } catch (e: Exception) {
            indices.forEach { onPage(it, null) }
        }
    }

    fun renderPage(file: File, index: Int, targetWidthPx: Int): Bitmap? {
        var result: Bitmap? = null
        renderPages(file, listOf(index), targetWidthPx) { _, b -> result = b }
        return result
    }
}

/** Dimensiones de una imagen tal como se mostrará (con la rotación EXIF ya aplicada), sin decodificarla. */
fun imageDisplaySize(file: File): Pair<Int, Int>? {
    if (!file.isFile) return null
    return try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        if (exifRotation(file) % 180 != 0) o.outHeight to o.outWidth else o.outWidth to o.outHeight
    } catch (e: Exception) {
        null
    }
}
