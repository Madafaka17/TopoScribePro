package com.toposcribe.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.toposcribe.calc.AnnexFileKind
import com.toposcribe.calc.AnnexRules
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.AnnexEntity
import com.toposcribe.data.local.entity.AnnexKind
import com.toposcribe.io.PdfFiles
import com.toposcribe.io.isDecodableImage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Módulo de anexos: croquis, fichas de campo, PDF, fotografías generales y otros respaldos. */
@Singleton
class AnnexRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: TopoScribeDatabase,
    private val files: AppFiles,
) {
    fun observe(projectId: Long): Flow<List<AnnexEntity>> = db.annexDao().observeByProject(projectId)
    suspend fun list(projectId: Long): List<AnnexEntity> = db.annexDao().listByProject(projectId)

    private data class Meta(val name: String, val size: Long?)

    private fun readMeta(uri: Uri): Meta {
        var name: String? = null
        var size: Long? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) { /* se usa el nombre del último segmento de la ruta */ }
        return Meta(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "", size)
    }

    /** Copia el contenido a un archivo temporal con tope de tamaño; valida que se pueda leer. */
    private suspend fun stage(uri: Uri): Outcome<Triple<File, String, Long>> = withContext(Dispatchers.IO) {
        val meta = readMeta(uri)
        AnnexRules.validate(meta.name, meta.size)?.let { return@withContext Outcome.Fail(it) }
        val tmp = File(files.workDir, "annex_${UUID.randomUUID()}.${AnnexRules.extensionOf(meta.name)}")
        try {
            var total = 0L
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext Outcome.Fail("No se pudo abrir el archivo seleccionado (el origen no permite leerlo).")
            input.use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > AnnexRules.MAX_BYTES) { tmp.delete(); return@withContext Outcome.Fail(AnnexRules.validate(meta.name, total)!!) }
                        o.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) { tmp.delete(); return@withContext Outcome.Fail("El archivo está vacío.") }
            val mime = AnnexRules.mimeFor(meta.name)!!
            when (AnnexRules.kindOf(mime)) {
                AnnexFileKind.IMAGE -> if (!isDecodableImage(tmp)) { tmp.delete(); return@withContext Outcome.Fail("La imagen está dañada o su formato no se puede leer.") }
                AnnexFileKind.PDF -> if (PdfFiles.pageCount(tmp) == null) { tmp.delete(); return@withContext Outcome.Fail("El PDF está dañado, vacío o protegido con contraseña.") }
                AnnexFileKind.OTHER -> Unit
            }
            Outcome.Ok(Triple(tmp, meta.name, total))
        } catch (e: Exception) {
            tmp.delete()
            Outcome.Fail("Error al leer el archivo: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun checkRelations(projectId: Long, pointId: Long?): String? {
        if (pointId == null) return null
        val p = db.pointDao().getById(pointId)
        return if (p == null || p.projectId != projectId || p.deletedAt != null) "El punto relacionado no existe en este proyecto." else null
    }

    suspend fun add(projectId: Long, uri: Uri, title: String, description: String?, kind: AnnexKind, pointId: Long?, section: String?): Outcome<AnnexEntity> {
        if (title.isBlank()) return Outcome.Fail("El título del anexo es obligatorio.")
        checkRelations(projectId, pointId)?.let { return Outcome.Fail(it) }
        val staged = stage(uri)
        if (staged is Outcome.Fail) return staged
        val (tmp, name, size) = (staged as Outcome.Ok).value
        val stored = "${UUID.randomUUID()}.${AnnexRules.extensionOf(name)}"
        val target = files.annex(stored)
        withContext(Dispatchers.IO) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        val now = Instant.now().toString()
        val entity = AnnexEntity(
            projectId = projectId, originalName = name, title = title.trim(), description = description?.ifBlank { null }, mimeType = AnnexRules.mimeFor(name)!!,
            kind = kind, sizeBytes = size, storedFile = stored, addedAt = now, pointId = pointId, reportSection = section?.ifBlank { null },
            sortOrder = db.annexDao().maxOrder(projectId) + 1,
        )
        return Outcome.Ok(entity.copy(id = db.annexDao().insert(entity)))
    }

    /** Reemplaza el archivo conservando título, descripción, relaciones y orden. */
    suspend fun replaceFile(annexId: Long, uri: Uri): Outcome<AnnexEntity> {
        val old = db.annexDao().getById(annexId) ?: return Outcome.Fail("El anexo ya no existe.")
        val staged = stage(uri)
        if (staged is Outcome.Fail) return staged
        val (tmp, name, size) = (staged as Outcome.Ok).value
        val stored = "${UUID.randomUUID()}.${AnnexRules.extensionOf(name)}"
        withContext(Dispatchers.IO) { tmp.copyTo(files.annex(stored), overwrite = true); tmp.delete() }
        val updated = old.copy(originalName = name, mimeType = AnnexRules.mimeFor(name)!!, sizeBytes = size, storedFile = stored, updatedAt = Instant.now().toString())
        db.annexDao().update(updated)
        files.deleteAnnex(old.storedFile)
        return Outcome.Ok(updated)
    }

    suspend fun updateInfo(annexId: Long, title: String, description: String?, kind: AnnexKind, pointId: Long?, section: String?): Outcome<Unit> {
        val a = db.annexDao().getById(annexId) ?: return Outcome.Fail("El anexo ya no existe.")
        if (title.isBlank()) return Outcome.Fail("El título del anexo es obligatorio.")
        checkRelations(a.projectId, pointId)?.let { return Outcome.Fail(it) }
        db.annexDao().update(a.copy(title = title.trim(), description = description?.ifBlank { null }, kind = kind, pointId = pointId, reportSection = section?.ifBlank { null }, updatedAt = Instant.now().toString()))
        return Outcome.Ok(Unit)
    }

    /** Sube o baja un anexo en el orden de aparición del informe. */
    suspend fun move(annexId: Long, up: Boolean) {
        val a = db.annexDao().getById(annexId) ?: return
        val ordered = db.annexDao().listByProject(a.projectId).toMutableList()
        val i = ordered.indexOfFirst { it.id == annexId }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j < 0 || j >= ordered.size) return
        val moved = ordered.removeAt(i); ordered.add(j, moved)
        ordered.forEachIndexed { idx, e -> if (e.sortOrder != idx + 1) db.annexDao().update(e.copy(sortOrder = idx + 1)) }
    }

    suspend fun delete(annexId: Long) {
        val a = db.annexDao().getById(annexId) ?: return
        db.annexDao().delete(a)
        files.deleteAnnex(a.storedFile)
        // se normaliza el orden restante
        db.annexDao().listByProject(a.projectId).forEachIndexed { idx, e -> if (e.sortOrder != idx + 1) db.annexDao().update(e.copy(sortOrder = idx + 1)) }
    }

    /** Copia el archivo original a la ubicación elegida por el usuario («Descargar»). */
    suspend fun exportTo(annexId: Long, destination: Uri): Outcome<Unit> = withContext(Dispatchers.IO) {
        val a = db.annexDao().getById(annexId) ?: return@withContext Outcome.Fail("El anexo ya no existe.")
        val src = files.annex(a.storedFile)
        if (!src.isFile) return@withContext Outcome.Fail("El archivo del anexo no se encuentra en el dispositivo.")
        try {
            context.contentResolver.openOutputStream(destination)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: return@withContext Outcome.Fail("No se pudo escribir en la ubicación elegida.")
            Outcome.Ok(Unit)
        } catch (e: Exception) { Outcome.Fail("No se pudo guardar el archivo: ${e.message}") }
    }

    fun file(a: AnnexEntity): File = files.annex(a.storedFile)

    /** Uri de contenido para abrir el archivo con otra aplicación. */
    fun shareUri(a: AnnexEntity): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file(a))
}
