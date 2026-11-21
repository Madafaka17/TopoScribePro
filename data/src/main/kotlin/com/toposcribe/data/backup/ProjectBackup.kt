package com.toposcribe.data.backup

import androidx.room.withTransaction
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.AdjustmentEntity
import com.toposcribe.data.local.entity.AlignmentEntity
import com.toposcribe.data.local.entity.AnnexEntity
import com.toposcribe.data.local.entity.CalcSnapshotEntity
import com.toposcribe.data.local.entity.ComputedPointEntity
import com.toposcribe.data.local.entity.ImportBatchEntity
import com.toposcribe.data.local.entity.LevelingObservationEntity
import com.toposcribe.data.local.entity.LevelingRunEntity
import com.toposcribe.data.local.entity.LevelingTrigObservationEntity
import com.toposcribe.data.local.entity.ObservationEditLogEntity
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import com.toposcribe.data.local.entity.PointCorrection
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.ProjectEntity
import com.toposcribe.data.local.entity.SourceObservationRef
import com.toposcribe.data.local.entity.StationSetupEntity
import com.toposcribe.data.local.entity.TraverseEntity
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Respaldo COMPLETO de un proyecto en un único .zip: todas las tablas del proyecto en JSON y, dentro
 * del mismo archivo, el CONTENIDO de las fotografías de puntos y de los anexos (no solo sus
 * nombres). La restauración crea un proyecto nuevo con identificadores nuevos y rutas válidas en el
 * dispositivo de destino; cada archivo se verifica con su huella SHA-256 antes de aceptar el respaldo.
 */
const val BACKUP_FORMAT = "toposcribe-project-backup"
const val BACKUP_FORMAT_VERSION = 1

@Serializable
data class BackupFileRef(val path: String, val sha256: String, val size: Long)

@Serializable
data class BackupManifest(
    val format: String = BACKUP_FORMAT,
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val dbVersion: Int,
    val appVersion: String,
    val createdAt: String,
    val projectName: String,
    val counts: Map<String, Int>,
    val files: List<BackupFileRef>,
)

@Serializable
data class ProjectBackupData(
    val project: ProjectEntity,
    val points: List<PointEntity> = emptyList(),
    val stationSetups: List<StationSetupEntity> = emptyList(),
    val traverses: List<TraverseEntity> = emptyList(),
    val planimetryObservations: List<PlanimetryObservationEntity> = emptyList(),
    val levelingRuns: List<LevelingRunEntity> = emptyList(),
    val levelingObservations: List<LevelingObservationEntity> = emptyList(),
    val levelingTrigObservations: List<LevelingTrigObservationEntity> = emptyList(),
    val adjustments: List<AdjustmentEntity> = emptyList(),
    val computedPoints: List<ComputedPointEntity> = emptyList(),
    val editLog: List<ObservationEditLogEntity> = emptyList(),
    val alignments: List<AlignmentEntity> = emptyList(),
    val annexes: List<AnnexEntity> = emptyList(),
    val importBatches: List<ImportBatchEntity> = emptyList(),
    val calcSnapshots: List<CalcSnapshotEntity> = emptyList(),
)

data class BackupExportResult(val manifest: BackupManifest, val warnings: List<String>)
data class BackupImportResult(val projectId: Long, val projectName: String, val counts: Map<String, Int>, val warnings: List<String>)

class BackupException(message: String) : Exception(message)

private val backupJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Resuelve la ruta de un archivo guardado aunque el directorio de datos haya cambiado (otro dispositivo/usuario). */
fun resolveStoredFile(path: String?, dir: File): File? {
    if (path.isNullOrBlank()) return null
    val direct = File(path)
    if (direct.isFile) return direct
    val byName = File(dir, direct.name)
    return if (byName.isFile) byName else null
}

class ProjectBackupService(
    private val db: TopoScribeDatabase,
    private val photosDir: File,
    private val annexesDir: File,
    private val appVersion: String,
) {
    // -----------------------------------------------------------------------------------------
    // Exportar
    // -----------------------------------------------------------------------------------------
    suspend fun export(projectId: Long, out: OutputStream): BackupExportResult {
        val project = db.projectDao().getById(projectId) ?: throw BackupException("El proyecto no existe.")
        val warnings = mutableListOf<String>()
        val files = mutableListOf<BackupFileRef>()
        val zip = ZipOutputStream(out.buffered())
        val addedPaths = HashMap<String, String>() // ruta origen -> ruta en el zip

        fun addFile(source: File, zipPath: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            var size = 0L
            zip.putNextEntry(ZipEntry(zipPath))
            source.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n); zip.write(buf, 0, n); size += n
                }
            }
            zip.closeEntry()
            files += BackupFileRef(zipPath, md.digest().joinToString("") { "%02x".format(it) }, size)
            return zipPath
        }

        fun photoRef(path: String?, owner: String): String? {
            if (path.isNullOrBlank()) return null
            val file = resolveStoredFile(path, photosDir)
            if (file == null) { warnings += "La fotografía de $owner no se encontró en el dispositivo y no se incluyó."; return null }
            return addedPaths.getOrPut(file.absolutePath) { addFile(file, "files/photos/${files.size + 1}_${file.name}") }
        }

        val points = db.pointDao().listByProject(projectId)
        val pointCode = points.associate { it.id to it.code }
        val pointIds = pointCode.keys
        val planObs = db.planimetryObservationDao().listSnapshot(projectId)
        val runs = db.levelingRunDao().listSnapshot(projectId)
        val levelObs = runs.flatMap { db.levelingObservationDao().listSnapshot(it.id) }
        val traverses = db.traverseDao().listSnapshot(projectId)

        val pointsOut = points.map { it.copy(sketchPhotoPath = photoRef(it.sketchPhotoPath, "el punto ${it.code}")) }
        val planObsOut = planObs.filter { it.occupiedPointId in pointIds && it.targetPointId in pointIds }
            .map { it.copy(photoPath = photoRef(it.photoPath, "la observación ${it.id}")) }
        val levelObsOut = levelObs.filter { it.pointId in pointIds }.map { it.copy(photoPath = photoRef(it.photoPath, "la lectura ${it.id}")) }
        val annexes = db.annexDao().listByProject(projectId)
        val annexesOut = annexes.map { a ->
            val f = File(annexesDir, a.storedFile)
            if (f.isFile) {
                a.copy(storedFile = addedPaths.getOrPut(f.absolutePath) { addFile(f, "files/annexes/${a.storedFile}") })
            } else {
                warnings += "El archivo del anexo «${a.title}» no se encontró en el dispositivo y no se pudo incluir."
                a.copy(storedFile = "")
            }
        }

        val logs = buildList {
            for (p in points) addAll(db.observationEditLogDao().listFor("points", p.id))
            for (o in planObsOut) addAll(db.observationEditLogDao().listFor("planimetry_observations", o.id))
            for (o in levelObsOut) addAll(db.observationEditLogDao().listFor("leveling_observations", o.id))
        }

        val data = ProjectBackupData(
            project = project,
            points = pointsOut,
            stationSetups = db.stationSetupDao().listSnapshot(projectId).filter { it.occupiedPointId in pointIds },
            traverses = traverses,
            planimetryObservations = planObsOut,
            levelingRuns = runs,
            levelingObservations = levelObsOut,
            levelingTrigObservations = db.levelingTrigObservationDao().listSnapshot(projectId),
            adjustments = traverses.flatMap { db.adjustmentDao().listByTraverse(it.id) },
            computedPoints = db.computedPointDao().listCurrentSnapshot(projectId),
            editLog = logs,
            alignments = db.alignmentDao().listSnapshot(projectId),
            annexes = annexesOut,
            importBatches = db.importBatchDao().listByProject(projectId),
            calcSnapshots = db.calcSnapshotDao().listByProject(projectId),
        )
        val counts = linkedMapOf(
            "points" to data.points.size, "stationSetups" to data.stationSetups.size,
            "planimetryObservations" to data.planimetryObservations.size, "levelingRuns" to data.levelingRuns.size,
            "levelingObservations" to data.levelingObservations.size, "computedPoints" to data.computedPoints.size,
            "editLog" to data.editLog.size, "alignments" to data.alignments.size, "annexes" to data.annexes.size,
            "files" to files.size,
        )
        zip.putNextEntry(ZipEntry("data.json"))
        zip.write(backupJson.encodeToString(data).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        val manifest = BackupManifest(
            dbVersion = TopoScribeDatabase.VERSION, appVersion = appVersion, createdAt = Instant.now().toString(),
            projectName = project.name, counts = counts, files = files,
        )
        zip.putNextEntry(ZipEntry("manifest.json"))
        zip.write(backupJson.encodeToString(manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.finish()
        zip.flush()
        return BackupExportResult(manifest, warnings)
    }

    // -----------------------------------------------------------------------------------------
    // Importar / restaurar
    // -----------------------------------------------------------------------------------------
    suspend fun import(input: InputStream, workDir: File): BackupImportResult {
        val tmp = File(workDir, "restore_${UUID.randomUUID()}").apply { mkdirs() }
        val copied = mutableListOf<File>()
        try {
            // 1) Extraer a un directorio temporal (con protección contra rutas fuera del directorio).
            ZipInputStream(input.buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val target = File(tmp, e.name).canonicalFile
                    if (!target.path.startsWith(tmp.canonicalPath + File.separator)) throw BackupException("El archivo de respaldo contiene rutas no válidas.")
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zin.copyTo(it) }
                }
            }
            val manifestFile = File(tmp, "manifest.json")
            val dataFile = File(tmp, "data.json")
            if (!manifestFile.isFile || !dataFile.isFile) throw BackupException("No es un respaldo de TopoScribe Pro (falta manifest.json o data.json).")
            val manifest = try { backupJson.decodeFromString<BackupManifest>(manifestFile.readText()) }
            catch (e: Exception) { throw BackupException("El manifiesto del respaldo está dañado.") }
            if (manifest.format != BACKUP_FORMAT) throw BackupException("El archivo no es un respaldo de proyecto de TopoScribe Pro.")
            if (manifest.formatVersion > BACKUP_FORMAT_VERSION) throw BackupException("El respaldo es de una versión más nueva de la aplicación; actualice la app para restaurarlo.")

            // 2) Verificar la integridad de cada archivo.
            for (ref in manifest.files) {
                val f = File(tmp, ref.path)
                if (!f.isFile) throw BackupException("Falta el archivo ${ref.path} dentro del respaldo.")
                val md = MessageDigest.getInstance("SHA-256")
                f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
                if (md.digest().joinToString("") { "%02x".format(it) } != ref.sha256) throw BackupException("El archivo ${ref.path} está dañado (la huella no coincide).")
            }
            val data = try { backupJson.decodeFromString<ProjectBackupData>(dataFile.readText()) }
            catch (e: Exception) { throw BackupException("Los datos del respaldo están dañados: ${e.message}") }

            // 3) Copiar los archivos a las carpetas de la app con nombres nuevos.
            photosDir.mkdirs(); annexesDir.mkdirs()
            val stamp = System.currentTimeMillis()
            val photoMap = HashMap<String, String>()
            fun restorePhoto(zipPath: String?): String? {
                if (zipPath.isNullOrBlank()) return null
                return photoMap.getOrPut(zipPath) {
                    val src = File(tmp, zipPath)
                    val ext = src.extension.ifBlank { "jpg" }
                    val dst = File(photosDir, "imp_${stamp}_${photoMap.size + 1}.$ext")
                    src.copyTo(dst, overwrite = false); copied += dst
                    dst.absolutePath
                }
            }
            val annexMap = HashMap<String, String>()
            fun restoreAnnex(zipPath: String): String {
                if (zipPath.isBlank()) return ""
                return annexMap.getOrPut(zipPath) {
                    val src = File(tmp, zipPath)
                    val dst = File(annexesDir, "${UUID.randomUUID()}.${src.extension.ifBlank { "bin" }}")
                    src.copyTo(dst, overwrite = false); copied += dst
                    dst.name
                }
            }

            val warnings = mutableListOf<String>()
            val newProjectId = db.withTransaction {
                val existingNames = db.projectDao().listAll().map { it.name }.toSet()
                val name = if (data.project.name in existingNames) "${data.project.name} (restaurado)" else data.project.name
                val pid = db.projectDao().insert(data.project.copy(id = 0, name = name, isDemo = false))

                val pointMap = HashMap<Long, Long>()
                for (p in data.points) pointMap[p.id] = db.pointDao().insert(p.copy(id = 0, projectId = pid, sketchPhotoPath = restorePhoto(p.sketchPhotoPath)))
                fun pm(id: Long?): Long? = id?.let { pointMap[it] }

                val setupMap = HashMap<Long, Long>()
                for (s in data.stationSetups) {
                    val occ = pm(s.occupiedPointId) ?: continue
                    setupMap[s.id] = db.stationSetupDao().insert(s.copy(id = 0, projectId = pid, occupiedPointId = occ, backsightPointId = pm(s.backsightPointId)))
                }
                val traverseMap = HashMap<Long, Long>()
                for (t in data.traverses) traverseMap[t.id] = db.traverseDao().insert(t.copy(id = 0, projectId = pid, startPointId = pm(t.startPointId), endPointId = pm(t.endPointId)))
                val planMap = HashMap<Long, Long>()
                for (o in data.planimetryObservations) {
                    val occ = pm(o.occupiedPointId) ?: continue
                    val tgt = pm(o.targetPointId) ?: continue
                    planMap[o.id] = db.planimetryObservationDao().insert(
                        o.copy(id = 0, projectId = pid, occupiedPointId = occ, targetPointId = tgt,
                            traverseId = o.traverseId?.let { traverseMap[it] }, stationSetupId = o.stationSetupId?.let { setupMap[it] },
                            photoPath = restorePhoto(o.photoPath)),
                    )
                }
                val runMap = HashMap<Long, Long>()
                for (r in data.levelingRuns) {
                    val start = pm(r.startBmPointId) ?: continue
                    runMap[r.id] = db.levelingRunDao().insert(r.copy(id = 0, projectId = pid, startBmPointId = start, endBmPointId = pm(r.endBmPointId)))
                }
                val levelObsMap = HashMap<Long, Long>()
                for (o in data.levelingObservations) {
                    val run = runMap[o.levelingRunId] ?: continue
                    val pt = pm(o.pointId) ?: continue
                    levelObsMap[o.id] = db.levelingObservationDao().insert(o.copy(id = 0, levelingRunId = run, pointId = pt, photoPath = restorePhoto(o.photoPath)))
                }
                val trigMap = HashMap<Long, Long>()
                for (o in data.levelingTrigObservations) {
                    val occ = pm(o.occupiedPointId) ?: continue
                    val tgt = pm(o.targetPointId) ?: continue
                    trigMap[o.id] = db.levelingTrigObservationDao().insert(o.copy(id = 0, projectId = pid, occupiedPointId = occ, targetPointId = tgt))
                }
                val adjMap = HashMap<Long, Long>()
                for (a in data.adjustments) {
                    val tr = traverseMap[a.traverseId] ?: continue
                    adjMap[a.id] = db.adjustmentDao().insert(
                        a.copy(id = 0, traverseId = tr, perPointCorrections = a.perPointCorrections.mapNotNull { c -> pm(c.pointId)?.let { PointCorrection(it, c.correctionE, c.correctionN) } }),
                    )
                }
                for (c in data.computedPoints) {
                    val pt = pm(c.pointId) ?: continue
                    val refs = c.sourceObservationIds.mapNotNull { r ->
                        val n = when (r.table) {
                            "planimetry_observations" -> planMap[r.id]
                            "leveling_observations" -> levelObsMap[r.id]
                            "leveling_trig_observations" -> trigMap[r.id]
                            else -> null
                        }
                        n?.let { SourceObservationRef(r.table, it) }
                    }
                    db.computedPointDao().insert(c.copy(id = 0, projectId = pid, pointId = pt, adjustmentId = c.adjustmentId?.let { adjMap[it] }, sourceObservationIds = refs))
                }
                for (l in data.editLog) {
                    val newRow = when (l.tableName) {
                        "points" -> pointMap[l.rowId]
                        "planimetry_observations" -> planMap[l.rowId]
                        "leveling_observations" -> levelObsMap[l.rowId]
                        else -> null
                    } ?: continue
                    db.observationEditLogDao().insert(l.copy(id = 0, rowId = newRow))
                }
                for (a in data.alignments) db.alignmentDao().insert(a.copy(id = 0, projectId = pid, pointSequence = a.pointSequence.mapNotNull { pointMap[it] }))
                for (a in data.annexes) {
                    if (a.storedFile.isBlank()) { warnings += "El anexo «${a.title}» no traía su archivo; se restauró solo la descripción." }
                    db.annexDao().insert(a.copy(id = 0, projectId = pid, pointId = pm(a.pointId), storedFile = restoreAnnex(a.storedFile)))
                }
                for (b in data.importBatches) db.importBatchDao().insert(b.copy(id = 0, projectId = pid))
                for (s in data.calcSnapshots) {
                    val subject = if (s.kind == "ALTIMETRIA") runMap[s.subjectId] ?: continue else s.subjectId
                    db.calcSnapshotDao().upsert(s.copy(id = 0, projectId = pid, subjectId = subject))
                }
                pid
            }
            return BackupImportResult(newProjectId, db.projectDao().getById(newProjectId)?.name ?: data.project.name, manifest.counts, warnings)
        } catch (e: Exception) {
            copied.forEach { it.delete() }
            if (e is BackupException) throw e
            throw BackupException("No se pudo restaurar el respaldo: ${e.message ?: e::class.simpleName}")
        } finally {
            tmp.deleteRecursively()
        }
    }
}
