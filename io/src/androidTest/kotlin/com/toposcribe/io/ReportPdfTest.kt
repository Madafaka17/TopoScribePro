package com.toposcribe.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.toposcribe.calc.AngleConvention
import com.toposcribe.calc.AnnexFileKind
import com.toposcribe.calc.DistanceKind
import com.toposcribe.calc.ElevatedPoint
import com.toposcribe.calc.ElevationSource
import com.toposcribe.calc.HorizontalKind
import com.toposcribe.calc.LevelingObservationCalcInput
import com.toposcribe.calc.PlanModel
import com.toposcribe.calc.PlanPoint
import com.toposcribe.calc.PlanPolyline
import com.toposcribe.calc.PlanRadiation
import com.toposcribe.calc.RadiationInput
import com.toposcribe.calc.ReadingType
import com.toposcribe.calc.ReportAnnex
import com.toposcribe.calc.ReportContourSummary
import com.toposcribe.calc.ReportData
import com.toposcribe.calc.ReportLevelCalcRow
import com.toposcribe.calc.ReportLevelInputRow
import com.toposcribe.calc.ReportLevelRun
import com.toposcribe.calc.ReportPhotoEntry
import com.toposcribe.calc.ReportPointRow
import com.toposcribe.calc.ReportProjectInfo
import com.toposcribe.calc.ReportRadiationRow
import com.toposcribe.calc.ReportRadiationStation
import com.toposcribe.calc.ResultState
import com.toposcribe.calc.StationOrientation
import com.toposcribe.calc.buildLevelingProfile
import com.toposcribe.calc.computeContours
import com.toposcribe.calc.computeDifferentialLeveling
import com.toposcribe.calc.computeRadiation
import com.toposcribe.calc.formatDegreesAsDms
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genera el informe PDF completo con datos de PRUEBA (rotulados como tales) y archivos reales:
 * fotografías, un croquis JPG, un PDF anexo de 2 páginas y un TXT. Escribe el PDF en la carpeta
 * externa de la app de pruebas para abrirlo luego con un lector independiente.
 */
@RunWith(AndroidJUnit4::class)
class ReportPdfTest {

    private fun jpg(file: File, w: Int, h: Int, color: Int, label: String) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; textSize = h / 10f; style = Paint.Style.STROKE; strokeWidth = 6f }
        c.drawRect(8f, 8f, w - 8f, h - 8f, p); c.drawLine(0f, 0f, w.toFloat(), h.toFloat(), p); c.drawLine(0f, h.toFloat(), w.toFloat(), 0f, p)
        p.style = Paint.Style.FILL; c.drawText(label, 30f, h / 2f, p)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private fun smallPdf(file: File, pages: Int) {
        val doc = PdfDocument()
        for (i in 1..pages) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i).create())
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = Color.BLACK }
            page.canvas.drawText("FICHA DE CAMPO (anexo PDF de prueba)", 40f, 80f, p)
            page.canvas.drawText("Hoja $i de $pages", 40f, 130f, p)
            page.canvas.drawRect(40f, 160f, 555f, 700f, Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f })
            doc.finishPage(page)
        }
        FileOutputStream(file).use { doc.writeTo(it) }; doc.close()
    }

    @Test
    fun generaInformeCompletoConFotosYAnexos() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "pdf-test").apply { deleteRecursively(); mkdirs() }
        val photoLandscape = File(dir, "foto_h.jpg").also { jpg(it, 1600, 900, Color.rgb(0x2e, 0x7d, 0x32), "FOTO P1 (apaisada 16:9)") }
        val photoPortrait = File(dir, "foto_v.jpg").also { jpg(it, 900, 1600, Color.rgb(0x15, 0x65, 0xc0), "FOTO P2 (vertical 9:16)") }
        val croquis = File(dir, "croquis.jpg").also { jpg(it, 1400, 1000, Color.rgb(0x6d, 0x4c, 0x41), "CROQUIS DE CAMPO") }
        val general = File(dir, "general.png").also { f -> val b = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888); Canvas(b).drawColor(Color.rgb(0xad, 0x14, 0x57)); FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val ficha = File(dir, "ficha.pdf").also { smallPdf(it, 2) }
        val notas = File(dir, "notas.txt").also { it.writeText("notas de campo") }
        val dañado = File(dir, "danado.pdf").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }

        // --- cálculo real con las funciones de :calc (nivelación con 2 estaciones y distancias)
        val book = listOf(
            LevelingObservationCalcInput(1, 1, 1, ReadingType.BS, 1.250, false), LevelingObservationCalcInput(2, 2, 2, ReadingType.FS, 0.850, true, 20.0),
            LevelingObservationCalcInput(3, 3, 2, ReadingType.BS, 1.100, false), LevelingObservationCalcInput(4, 4, 3, ReadingType.IS, 1.500, false, 15.0),
            LevelingObservationCalcInput(5, 5, 4, ReadingType.FS, 0.700, false, 25.0),
        )
        val codes = mapOf(1L to "BM-1", 2L to "TP-1", 3L to "P3", 4L to "P4")
        val lev = computeDifferentialLeveling(book, 100.0)
        val typeL = { t: ReadingType -> when (t) { ReadingType.BS -> "VA"; ReadingType.IS -> "VI"; ReadingType.FS -> "VD" } }
        val profile = buildLevelingProfile(lev.rows, codes)
        val level = ReportLevelRun(
            "Nivelación de prueba", "BM-1", 100.0,
            book.map { ReportLevelInputRow(it.sequenceOrder, 1, codes[it.pointId]!!, typeL(it.readingType), it.readingValue, it.partialDistance, it.isTurningPoint, null, if (it.id == 4L) "corregido: original 1.510 → vigente 1.500" else null) },
            lev.rows.map { ReportLevelCalcRow(it.stationNumber, codes[it.pointId]!!, typeL(it.readingType), it.readingValue, it.partialDistance, it.cumulativeDistance, it.operation, it.elevation) },
            lev.sumBacksight, lev.sumForesight, lev.finalElevation, lev.arithmeticCheckOk, null, null, null, ResultState.ACTUALIZADO, lev.warnings, profile,
        )
        // --- radiación real
        val orient = StationOrientation(60.0, 0.0, AngleConvention.HORARIO)
        val inputs = listOf(Triple("P1", HorizontalKind.AZIMUT, 90.0 to 10.0), Triple("P2", HorizontalKind.LECTURA_HORIZONTAL, 30.0 to 14.1), Triple("P3", HorizontalKind.AZIMUT, 200.0 to 12.0), Triple("P4", HorizontalKind.AZIMUT, 300.0 to 9.0))
        val steps = inputs.map { (code, kind, v) -> code to computeRadiation(RadiationInput(500.0, 1000.0, orient, kind, v.first, v.second, DistanceKind.HORIZONTAL)).step!! }
        val station = ReportRadiationStation(
            "EST-1", 500.0, 1000.0, "coordenadas locales declaradas", 1.55, "referencia «BM-1» con azimut ${formatDegreesAsDms(60.0, 2)}, lectura horizontal 0°00'00.00\" y convenio horario",
            steps.map { (code, s) ->
                ReportRadiationRow(code, "Punto $code", "Az/Hz ${formatDegreesAsDms(s.azimuthDeg, 2)}", null, formatDegreesAsDms(s.azimuthDeg, 2), s.horizontalDistance, s.deltaE, s.deltaN, s.east, s.north,
                    listOf(s.azimuthFormula, s.distanceFormula, s.deltaEFormula, s.deltaNFormula, s.eastFormula, s.northFormula), emptyList())
            } + ReportRadiationRow("P9", null, "Az 10°00'00.00\" · d inclinada 20.000", null, null, null, null, null, null, null, emptyList(), listOf("Falta el ángulo vertical o cenital para reducir la distancia inclinada.")),
        )
        // --- plano con curvas (los puntos radiados llevan la cota del nivel)
        val elev = mapOf("P1" to 101.0, "P2" to 103.0, "P3" to 102.0, "P4" to 100.5)
        val planPts = listOf(PlanPoint(1, "EST-1", "Estación", 500.0, 1000.0, 100.0, ElevationSource.CONTROL_CONOCIDO, true)) +
            steps.mapIndexed { i, (code, s) -> PlanPoint(10L + i, code, "Punto $code", s.east, s.north, elev[code], ElevationSource.NIVEL_INGENIERO, false) }
        val contours = computeContours(planPts.filter { it.elevation != null }.map { ElevatedPoint(it.pointId, it.east, it.north, it.elevation!!) }, 0.5)
        val plan = PlanModel("Informe de prueba", "Local (PTL)", "m", planPts, planPts.drop(1).map { PlanRadiation(1, it.pointId) }, listOf(PlanPolyline("Cerco", listOf(10, 11, 12, 13), true)), contours, 1)

        val data = ReportData(
            project = ReportProjectInfo("PRUEBA AUTOMATIZADA — no es un levantamiento real", "Verificar el informe", null, null, "2026-11-20", "Equipo de pruebas",
                "Nivel de ingeniero: (marca/modelo pendiente de completar) · Teodolito: (marca/modelo pendiente de completar)", "Local (PTL)", "m", "DMS", null, null, false),
            levelRuns = listOf(level), radiationStations = listOf(station),
            points = planPts.map { ReportPointRow(it.code, it.description, if (it.isStation) "Teodolito" else "Nivel + Teodolito", it.east, it.north, it.elevation, it.elevationSource.labelEs) } + ReportPointRow("P9", null, "Teodolito", null, null, null, "Sin cota"),
            plan = plan, planState = ResultState.ACTUALIZADO, contourSummary = ReportContourSummary(true, 0.5, contours.levels.size, contours.method, 4, null),
            photos = listOf(ReportPhotoEntry("EST-1", "Estación", null, null), ReportPhotoEntry("P1", "Esquina NE", "Vista hacia el norte", photoLandscape.absolutePath),
                ReportPhotoEntry("P2", "Esquina SE", "Foto vertical sin deformar", photoPortrait.absolutePath), ReportPhotoEntry("P3", null, null, null)),
            annexes = listOf(
                ReportAnnex(1, "Croquis de campo", "Croquis a mano alzada", "croquis_campo.jpg", "Imagen JPG", "2026-11-19T10:00:00Z", "punto P1", AnnexFileKind.IMAGE, croquis.absolutePath, croquis.length()),
                ReportAnnex(2, "Imagen general", null, "vista_general.png", "Imagen PNG", "2026-11-19T10:05:00Z", null, AnnexFileKind.IMAGE, general.absolutePath, general.length()),
                ReportAnnex(3, "Ficha de campo", "Ficha de dos hojas", "ficha.pdf", "Documento PDF", "2026-11-19T10:10:00Z", "sección Libreta de campo", AnnexFileKind.PDF, ficha.absolutePath, ficha.length()),
                ReportAnnex(4, "Notas", null, "notas.txt", "Archivo TXT", "2026-11-19T10:15:00Z", null, AnnexFileKind.OTHER, notas.absolutePath, notas.length()),
                ReportAnnex(5, "PDF dañado", null, "danado.pdf", "Documento PDF", "2026-11-19T10:20:00Z", null, AnnexFileKind.PDF, dañado.absolutePath, dañado.length()),
            ),
            pendingItems = listOf("Memoria descriptiva incompleta; pendiente de completar: ubicación, descripción del terreno.", "Nivelación «Nivelación de prueba»: sin BM final de cota conocida; el cierre contra control externo no puede comprobarse.", "P9 sin calcular: falta el ángulo vertical."),
            generatedAt = "2026-11-20 12:00", appVersion = "2.1.0",
        )
        val result = buildReportPdf(data, Typeface.DEFAULT, Typeface.DEFAULT_BOLD, Typeface.MONOSPACE)
        File(dir, "informe_prueba.pdf").writeBytes(result.bytes)

        assertTrue(String(result.bytes.copyOf(5)) == "%PDF-")
        assertTrue("páginas=${result.pageCount}", result.pageCount >= 12)
        assertEquals(result.pageCount, PdfFiles.pageCount(File(dir, "informe_prueba.pdf")))
        val byNum = result.annexStatuses.associateBy { it.annex.number }
        assertTrue(byNum.getValue(1).embedded && byNum.getValue(2).embedded)
        assertTrue(byNum.getValue(3).embedded && byNum.getValue(3).pagesEmbedded == 2)
        assertTrue(!byNum.getValue(4).embedded && byNum.getValue(4).note.contains("respaldo"))
        assertTrue(!byNum.getValue(5).embedded && byNum.getValue(5).note.contains("no se pudo abrir"))
    }
}
