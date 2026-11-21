package com.toposcribe.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Decodifica una imagen guardada reduciéndola a como máximo [maxSide] píxeles por lado y aplicando
 * la orientación EXIF (las fotos de cámara vienen "de lado" con una marca EXIF). Devuelve null si el
 * archivo no existe, está dañado o no hay memoria: nunca lanza excepción.
 */
fun decodeOriented(file: File?, maxSide: Int): Bitmap? {
    if (file == null || !file.isFile) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = exifRotation(file)
        if (rotation == 0) bmp else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            rotated
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

fun exifRotation(file: File): Int = try {
    when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
} catch (e: Exception) {
    0
}

/** ¿El archivo se puede decodificar como imagen? (para validar anexos y fotos antes de guardarlos). */
fun isDecodableImage(file: File): Boolean {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    return try {
        BitmapFactory.decodeFile(file.path, bounds)
        bounds.outWidth > 0 && bounds.outHeight > 0
    } catch (e: Exception) {
        false
    }
}

/**
 * Copia una imagen reduciéndola (lado máximo [maxSide]) y guardándola como JPEG con la orientación
 * ya aplicada. Una foto de cámara de 12 MP pasa de ~6 MB a ~600 KB: el respaldo y el PDF pesan menos.
 */
fun saveDownscaledJpeg(source: File, target: File, maxSide: Int = 2560, quality: Int = 90): Boolean {
    val bmp = decodeOriented(source, maxSide) ?: return false
    return try {
        FileOutputStream(target).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        true
    } catch (e: Exception) {
        target.delete()
        false
    } finally {
        bmp.recycle()
    }
}
