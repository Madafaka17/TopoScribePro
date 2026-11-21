package com.toposcribe.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.Instant

/**
 * Antes de que Room migre una base de datos de una versión anterior, se hace una COPIA del archivo
 * (toposcribe.db) en files/backups/. Si algo fallara durante la migración, el usuario no pierde sus
 * proyectos: la copia queda intacta. No toca la base original.
 */
object PreMigrationBackup {

    /** Devuelve la carpeta de la copia, o null si no hacía falta (no hay base o ya está en la versión actual). */
    fun backupIfNeeded(context: Context, dbName: String = TopoScribeDatabase.DATABASE_NAME, currentVersion: Int = TopoScribeDatabase.VERSION): File? {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.isFile || dbFile.length() == 0L) return null
        val version = try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        } catch (e: Exception) {
            return null // base ilegible: Room dará su propio error; no se copia nada dudoso
        }
        if (version == 0 || version >= currentVersion) return null

        val dir = File(context.filesDir, "backups/pre-migracion-v$version-${Instant.now().toString().replace(':', '-')}")
        dir.mkdirs()
        // El WAL puede tener cambios aún no integrados: se copian los tres archivos para no perder nada.
        for (suffix in listOf("", "-wal", "-shm")) {
            val src = File(dbFile.path + suffix)
            if (src.isFile) src.copyTo(File(dir, src.name), overwrite = true)
        }
        return dir
    }
}
