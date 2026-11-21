package com.toposcribe.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.toposcribe.calc.ReadingType
import com.toposcribe.data.backup.BackupException
import com.toposcribe.data.backup.ProjectBackupService
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.AnnexEntity
import com.toposcribe.data.local.entity.AnnexKind
import com.toposcribe.data.local.entity.CalcSnapshotEntity
import com.toposcribe.data.local.entity.ComputedPointEntity
import com.toposcribe.data.local.entity.LevelingObservationEntity
import com.toposcribe.data.local.entity.LevelingRunEntity
import com.toposcribe.data.local.entity.ObservationEditLogEntity
import com.toposcribe.data.local.entity.PlanimetryMethod
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.ProjectEntity
import com.toposcribe.data.local.entity.SourceObservationRef
import com.toposcribe.data.local.entity.StationSetupEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Respaldo y restauración de punta a punta: se exporta un proyecto con fotografías y anexos a un .zip,
 * y se restaura en OTRA base de datos con OTRAS carpetas (como en un dispositivo distinto o una
 * instalación limpia). Se comprueba que el CONTENIDO de los archivos viaja, no solo sus nombres.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private lateinit var src: TopoScribeDatabase
    private lateinit var dst: TopoScribeDatabase
    private lateinit var root: File
    private val photoBytes = ByteArray(5000) { (it * 7 % 251).toByte() }
    private val pdfBytes = "%PDF-1.4 contenido de prueba del anexo PDF".toByteArray() + ByteArray(300) { 3 }
    private val croquisBytes = ByteArray(2048) { (it % 199).toByte() }

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        src = Room.inMemoryDatabaseBuilder(ctx, TopoScribeDatabase::class.java).allowMainThreadQueries().build()
        dst = Room.inMemoryDatabaseBuilder(ctx, TopoScribeDatabase::class.java).allowMainThreadQueries().build()
        root = File(ctx.cacheDir, "backup-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() { src.close(); dst.close(); root.deleteRecursively() }

    private fun service(db: TopoScribeDatabase, tag: String) =
        ProjectBackupService(db, File(root, "$tag/photos"), File(root, "$tag/annexes"), "test")

    private suspend fun seed(): Long {
        File(root, "A/photos").mkdirs(); File(root, "A/annexes").mkdirs()
        val photoA = File(root, "A/photos/pt_A.jpg").apply { writeBytes(photoBytes) }
        File(root, "A/annexes/croquis.jpg").writeBytes(croquisBytes)
        File(root, "A/annexes/ficha.pdf").writeBytes(pdfBytes)
        val pid = src.projectDao().insert(ProjectEntity(name = "Proyecto real", purpose = "Lote", location = "Lima", createdAt = "2026-01-01T00:00:00Z"))
        val bm = src.pointDao().insert(PointEntity(projectId = pid, code = "BM-1", isControl = true, fixedElevation = 100.0, createdAt = "t"))
        val est = src.pointDao().insert(PointEntity(projectId = pid, code = "EST-1", fixedEast = 500.0, fixedNorth = 1000.0, sketchPhotoPath = photoA.absolutePath, photoCaption = "Vista norte", createdAt = "t"))
        val p1 = src.pointDao().insert(PointEntity(projectId = pid, code = "P1", description = "Poste", createdAt = "t"))
        val p2 = src.pointDao().insert(PointEntity(projectId = pid, code = "P2", createdAt = "t"))
        val setup = src.stationSetupDao().insert(StationSetupEntity(projectId = pid, occupiedPointId = est, instrumentHeightAi = 1.5, backsightPointLabel = "P2", baseAzimuthDeg = 10, baseAzimuthMin = 0, baseAzimuthSec = 0.0, baseAzimuthDecimal = 10.0, backsightReadingDeg = 0.0, backsightPointId = p2, createdAt = "t"))
        val obs = src.planimetryObservationDao().insert(PlanimetryObservationEntity(projectId = pid, occupiedPointId = est, targetPointId = p1, method = PlanimetryMethod.ACIMUT_DISTANCIA, horizontalDistance = 10.0, azimuth = 90.0, stationSetupId = setup, createdAt = "t"))
        val run = src.levelingRunDao().insert(LevelingRunEntity(projectId = pid, name = "Niv 1", startBmPointId = bm, createdAt = "t"))
        val l1 = src.levelingObservationDao().insert(LevelingObservationEntity(levelingRunId = run, sequenceOrder = 1, pointId = bm, readingType = ReadingType.BS, readingValue = 1.25, createdAt = "t"))
        val l2 = src.levelingObservationDao().insert(LevelingObservationEntity(levelingRunId = run, sequenceOrder = 2, pointId = p1, readingType = ReadingType.FS, readingValue = 0.85, partialDistance = 20.0, createdAt = "t"))
        src.computedPointDao().insert(ComputedPointEntity(projectId = pid, pointId = p1, east = 510.0, north = 1000.0, method = "radiacion", formulaId = "RADIACION", sourceObservationIds = listOf(SourceObservationRef("planimetry_observations", obs)), computedAt = "t"))
        src.computedPointDao().insert(ComputedPointEntity(projectId = pid, pointId = p1, elevation = 100.4, method = "nivelacion_geometrica", formulaId = "NIVELACION-DIFERENCIAL", sourceObservationIds = listOf(SourceObservationRef("leveling_observations", l2)), computedAt = "t"))
        src.observationEditLogDao().insert(ObservationEditLogEntity(tableName = "leveling_observations", rowId = l2, field = "reading_value", oldValue = "0.860", newValue = "0.850", editedAt = "t"))
        src.alignmentDao().insert(com.toposcribe.data.local.entity.AlignmentEntity(projectId = pid, name = "Linea", pointSequence = listOf(est, p1), closed = true, createdAt = "t"))
        src.annexDao().insert(AnnexEntity(projectId = pid, originalName = "croquis_campo.jpg", title = "Croquis", description = "Croquis del lote", mimeType = "image/jpeg", kind = AnnexKind.CROQUIS, sizeBytes = croquisBytes.size.toLong(), storedFile = "croquis.jpg", addedAt = "2026-01-02T00:00:00Z", pointId = est, reportSection = "Planimetría", sortOrder = 1))
        src.annexDao().insert(AnnexEntity(projectId = pid, originalName = "ficha.pdf", title = "Ficha de campo", mimeType = "application/pdf", kind = AnnexKind.FICHA_CAMPO, sizeBytes = pdfBytes.size.toLong(), storedFile = "ficha.pdf", addedAt = "2026-01-02T00:00:00Z", sortOrder = 2))
        src.calcSnapshotDao().upsert(CalcSnapshotEntity(projectId = pid, kind = "ALTIMETRIA", subjectId = run, inputHash = "abc123", computedAt = "t"))
        assertNotNull(l1)
        return pid
    }

    private fun exportToBytes(pid: Long): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        val r = service(src, "A").export(pid, out)
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
        out.toByteArray()
    }

    @Test
    fun exportaEImportaConFotosYAnexosEnOtroDispositivo() = runBlocking {
        val pid = seed()
        val zipBytes = exportToBytes(pid)
        // El zip contiene el CONTENIDO de los archivos
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { z -> while (true) { val e = z.nextEntry ?: break; names += e.name } }
        assertTrue(names.toString(), names.contains("manifest.json") && names.contains("data.json"))
        assertEquals(3, names.count { it.startsWith("files/") }) // 1 foto de punto + 2 anexos

        // Restaurar en OTRA base con OTRAS carpetas
        val res = service(dst, "B").import(ByteArrayInputStream(zipBytes), File(root, "B/work"))
        assertEquals("Proyecto real", res.projectName)
        val np = res.projectId

        val points = dst.pointDao().listByProject(np)
        assertEquals(listOf("BM-1", "EST-1", "P1", "P2"), points.map { it.code })
        val est = points.first { it.code == "EST-1" }
        // la foto sigue asociada al MISMO punto, ahora en la carpeta del dispositivo B, con el mismo contenido
        assertEquals("Vista norte", est.photoCaption)
        val photo = File(est.sketchPhotoPath!!)
        assertTrue(photo.absolutePath, photo.absolutePath.startsWith(File(root, "B/photos").absolutePath))
        assertArrayEquals(photoBytes, photo.readBytes())
        assertTrue(points.filter { it.code != "EST-1" }.all { it.sketchPhotoPath == null })

        // anexos: metadatos, orden, relaciones y bytes
        val annexes = dst.annexDao().listByProject(np)
        assertEquals(listOf("Croquis", "Ficha de campo"), annexes.map { it.title })
        assertEquals(listOf(1, 2), annexes.map { it.sortOrder })
        assertEquals("croquis_campo.jpg", annexes[0].originalName)
        assertEquals(est.id, annexes[0].pointId) // relación con el punto REMAPEADO
        assertEquals("Planimetría", annexes[0].reportSection)
        assertArrayEquals(croquisBytes, File(File(root, "B/annexes"), annexes[0].storedFile).readBytes())
        assertArrayEquals(pdfBytes, File(File(root, "B/annexes"), annexes[1].storedFile).readBytes())

        // resto de datos con ids remapeados
        val newBm = points.first { it.code == "BM-1" }
        val runs = dst.levelingRunDao().listSnapshot(np)
        assertEquals(1, runs.size); assertEquals(newBm.id, runs[0].startBmPointId)
        val lobs = dst.levelingObservationDao().listSnapshot(runs[0].id)
        assertEquals(listOf(1.25, 0.85), lobs.map { it.readingValue }); assertEquals(20.0, lobs[1].partialDistance!!, 0.0)
        val newP1 = points.first { it.code == "P1" }
        val log = dst.observationEditLogDao().listFor("leveling_observations", lobs[1].id)
        assertEquals("0.860", log.single().oldValue) // el historial viaja y apunta a la lectura restaurada
        val plan = dst.planimetryObservationDao().listSnapshot(np).single()
        assertEquals(newP1.id, plan.targetPointId)
        val setup = dst.stationSetupDao().getLatest(np)!!
        assertEquals(est.id, setup.occupiedPointId); assertEquals(points.first { it.code == "P2" }.id, setup.backsightPointId)
        assertEquals(plan.stationSetupId, setup.id)
        val computed = dst.computedPointDao().listCurrentSnapshot(np)
        assertEquals(2, computed.size)
        assertEquals(plan.id, computed.first { it.method == "radiacion" }.sourceObservationIds.single().id)
        assertEquals(lobs[1].id, computed.first { it.method == "nivelacion_geometrica" }.sourceObservationIds.single().id)
        val line = dst.alignmentDao().listSnapshot(np).single()
        assertEquals(listOf(est.id, newP1.id), line.pointSequence); assertTrue(line.closed)
        val snap = dst.calcSnapshotDao().listByProject(np).single()
        assertEquals(runs[0].id, snap.subjectId); assertEquals("abc123", snap.inputHash)
    }

    @Test
    fun restaurarDosVecesCreaProyectosIndependientes() = runBlocking {
        val zip = exportToBytes(seed())
        val s = service(dst, "B")
        val a = s.import(ByteArrayInputStream(zip), File(root, "B/work"))
        val b = s.import(ByteArrayInputStream(zip), File(root, "B/work"))
        assertNotEquals(a.projectId, b.projectId)
        assertEquals("Proyecto real", a.projectName); assertEquals("Proyecto real (restaurado)", b.projectName)
        val pa = dst.pointDao().getByCode(a.projectId, "EST-1")!!.sketchPhotoPath
        val pb = dst.pointDao().getByCode(b.projectId, "EST-1")!!.sketchPhotoPath
        assertNotEquals(pa, pb) // cada copia tiene su propio archivo
        assertTrue(File(pa!!).isFile && File(pb!!).isFile)
    }

    @Test
    fun unRespaldoAlteradoSeRechazaYNoDejaDatosASedias() = runBlocking {
        val zip = exportToBytes(seed())
        // alterar un byte del contenido de la foto dentro del zip
        val tampered = ByteArrayOutputStream()
        ZipOutputStream(tampered).use { zo ->
            ZipInputStream(ByteArrayInputStream(zip)).use { zi ->
                while (true) {
                    val e = zi.nextEntry ?: break
                    var data = zi.readBytes()
                    if (e.name.startsWith("files/photos/")) data = data.copyOf().also { it[10] = (it[10] + 1).toByte() }
                    zo.putNextEntry(ZipEntry(e.name)); zo.write(data); zo.closeEntry()
                }
            }
        }
        try {
            service(dst, "B").import(ByteArrayInputStream(tampered.toByteArray()), File(root, "B/work"))
            fail("debía rechazar el respaldo alterado")
        } catch (e: BackupException) {
            assertTrue(e.message, e.message!!.contains("dañado"))
        }
        assertEquals(0, dst.projectDao().listAll().size)
    }

    @Test
    fun unArchivoQueNoEsUnRespaldoSeRechazaConMensaje() = runBlocking {
        val notBackup = ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("otro.txt")); z.write(1); z.closeEntry() } }.toByteArray()
        try { service(dst, "B").import(ByteArrayInputStream(notBackup), File(root, "B/work")); fail() }
        catch (e: BackupException) { assertTrue(e.message, e.message!!.contains("No es un respaldo")) }
        try { service(dst, "B").import(ByteArrayInputStream(byteArrayOf(1, 2, 3)), File(root, "B/work")); fail() }
        catch (e: BackupException) { assertNotNull(e.message) }
    }

    @Test
    fun siFaltaElArchivoDeUnAnexoElRespaldoLoAdvierte() = runBlocking {
        val pid = seed()
        File(root, "A/annexes/ficha.pdf").delete()
        val out = ByteArrayOutputStream()
        val r = service(src, "A").export(pid, out)
        assertTrue(r.warnings.any { it.contains("Ficha de campo") })
    }
}
