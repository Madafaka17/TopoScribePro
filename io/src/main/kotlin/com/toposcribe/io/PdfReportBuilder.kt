package com.toposcribe.io

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.toposcribe.calc.AnnexFileKind
import com.toposcribe.calc.PlanLayer
import com.toposcribe.calc.ReportAnnex
import com.toposcribe.calc.ReportData
import com.toposcribe.calc.ReportLevelRun
import com.toposcribe.calc.ReportRadiationStation
import com.toposcribe.calc.ResultState
import java.io.ByteArrayOutputStream
import java.io.File

/** Máximo de páginas de un PDF anexo que se incorporan al informe (el resto queda en el respaldo). */
const val MAX_EMBEDDED_PDF_PAGES_PER_ANNEX = 30

/** Qué pasó con cada anexo al armar el PDF: nunca se dice «adjunto» si no lo está. */
data class AnnexEmbedStatus(
    val annex: ReportAnnex,
    val embedded: Boolean,
    val pagesEmbedded: Int,
    val totalPages: Int?,
    val note: String,
)

data class PdfBuildResult(val bytes: ByteArray, val pageCount: Int, val annexStatuses: List<AnnexEmbedStatus>)

private val SECTION_TITLES = listOf(
    "1. Portada y datos generales",
    "2. Memoria descriptiva",
    "3. Libretas de campo originales",
    "4. Memoria de cálculo de altimetría",
    "5. Memoria de cálculo de planimetría por radiación",
    "6. Tabla final de puntos y perfil longitudinal",
    "7. Plano y curvas de nivel",
    "8. Panel fotográfico por punto",
    "9. Anexos",
    "10. Observaciones, datos pendientes y limitaciones",
)

private class Ctx(
    val data: ReportData,
    val fonts: PdfFonts,
    val cursor: PdfCursor,
    val heavy: Boolean,
    val sectionPages: MutableMap<String, Int>,
    val tocPages: Map<String, Int>,
    val annexStatuses: List<AnnexEmbedStatus>,
)

/**
 * Informe de levantamiento topográfico, en dos pasadas: la primera mide (sin decodificar imágenes)
 * para conocer el total de páginas y la página de cada sección; la segunda dibuja con «Página N de T»
 * y el contenido con números de página reales.
 */
fun buildReportPdf(data: ReportData, regular: Typeface, bold: Typeface, mono: Typeface): PdfBuildResult {
    val fonts = PdfFonts(regular, bold, mono)
    val statuses = evaluateAnnexes(data.annexes)
    val footer = "Informe de levantamiento topográfico · ${data.project.name.ifBlank { "(proyecto sin nombre)" }}"

    // Pasada 1 (medición)
    val sections1 = LinkedHashMap<String, Int>()
    val doc1 = PdfDocument()
    val c1 = PdfCursor(doc1, fonts, footer, null)
    renderAll(Ctx(data, fonts, c1, false, sections1, emptyMap(), statuses))
    c1.finish()
    val total = c1.pageCount
    doc1.close()

    // Pasada 2 (definitiva)
    val doc = PdfDocument()
    val sections2 = LinkedHashMap<String, Int>()
    val cursor = PdfCursor(doc, fonts, footer, total)
    renderAll(Ctx(data, fonts, cursor, true, sections2, sections1, statuses))
    cursor.finish()
    val out = ByteArrayOutputStream()
    doc.writeTo(out)
    doc.close()
    return PdfBuildResult(out.toByteArray(), total, statuses)
}

// ---------------------------------------------------------------------------------------------

private fun pending(value: String?): String {
    val t = value?.trim()
    return if (t.isNullOrEmpty()) "(pendiente de completar)" else t
}

private fun Ctx.startSection(title: String, newPage: Boolean = true) {
    if (newPage && cursor.y > MARGIN + 1f) cursor.newPage()
    sectionPages[title] = cursor.pageNumber
    cursor.drawSectionHeader(title, fonts)
}

private fun renderAll(ctx: Ctx) {
    drawCover(ctx)
    drawDescriptiveMemory(ctx)
    drawFieldBooks(ctx)
    drawLevelingMemory(ctx)
    drawRadiationMemory(ctx)
    drawFinalTableAndProfile(ctx)
    drawPlanSection(ctx)
    drawPhotoPanel(ctx)
    drawAnnexes(ctx)
    drawObservations(ctx)
}

// 1. Portada ------------------------------------------------------------------------------------

private fun drawCover(ctx: Ctx) = with(ctx) {
    val p = data.project
    sectionPages[SECTION_TITLES[0]] = 1
    cursor.y = MARGIN + 30f
    cursor.drawTextLine("Informe de levantamiento topográfico", MARGIN, fonts.title)
    cursor.y += 4f
    cursor.drawWrappedParagraph(p.name.ifBlank { "(proyecto sin nombre)" }, MARGIN, CONTENT_WIDTH, fonts.subtitle)
    cursor.y += 10f
    if (p.isDemo) cursor.drawBanner("PROYECTO MARCADO COMO DEMOSTRACIÓN — no representa un levantamiento real.", fonts)
    if (data.planState == ResultState.DESACTUALIZADO || data.levelRuns.any { it.state == ResultState.DESACTUALIZADO }) {
        cursor.drawBanner("ATENCIÓN: hay resultados DESACTUALIZADOS (se modificaron lecturas después del último cálculo). Las cifras guardadas pueden no corresponder a las lecturas vigentes.", fonts)
    }
    cursor.drawSubHeader("Datos generales", fonts)
    cursor.drawTable(
        fonts, listOf(TableColumn("", 0.30f), TableColumn("", 0.70f)),
        listOf(
            listOf("Proyecto", pending(p.name)),
            listOf("Objetivo del levantamiento", pending(p.purpose)),
            listOf("Ubicación", pending(p.location)),
            listOf("Fecha del levantamiento", pending(p.date)),
            listOf("Responsable(s)", pending(p.responsible)),
            listOf("Instrumentos", p.instruments),
            listOf("Sistema de coordenadas", pending(p.coordinateSystem)),
            listOf("Unidades", "Distancias en ${p.unitsDistance}; ángulos en ${p.unitsAngle}"),
            listOf("Informe generado", data.generatedAt),
            listOf("Aplicación", "TopoScribe Pro ${data.appVersion}"),
        ),
        showHeader = false,
    )
    cursor.y += 10f
    cursor.drawSubHeader("Contenido", fonts)
    val rows = SECTION_TITLES.map { t -> listOf(t, (tocPages[t] ?: sectionPages[t] ?: 0).takeIf { it > 0 }?.toString() ?: "") }
    cursor.drawTable(fonts, listOf(TableColumn("", 0.88f), TableColumn("", 0.12f, alignRight = true)), rows, showHeader = false)
}

// 2. Memoria descriptiva --------------------------------------------------------------------------

private fun drawDescriptiveMemory(ctx: Ctx) = with(ctx) {
    val p = data.project
    startSection(SECTION_TITLES[1])
    cursor.drawWrappedParagraph(
        "Los campos que el responsable no completó se indican como «pendiente de completar»: la aplicación no inventa información del lugar ni del trabajo.",
        MARGIN, CONTENT_WIDTH, fonts.caption,
    )
    cursor.y += 4f
    cursor.drawTable(
        fonts, listOf(TableColumn("", 0.30f), TableColumn("", 0.70f)),
        listOf(
            listOf("Objetivo", pending(p.purpose)),
            listOf("Ubicación", pending(p.location)),
            listOf("Descripción del terreno", pending(p.terrainDescription)),
            listOf("Procedimiento de campo realizado", pending(p.fieldProcedure)),
            listOf("Instrumentos utilizados", p.instruments),
            listOf("Responsable(s)", pending(p.responsible)),
            listOf("Fecha", pending(p.date)),
            listOf("Sistema de coordenadas", pending(p.coordinateSystem)),
            listOf("Observaciones generales", pending(p.observations)),
        ),
        showHeader = false,
    )
}

// 3. Libretas originales ---------------------------------------------------------------------------

private fun drawFieldBooks(ctx: Ctx) = with(ctx) {
    startSection(SECTION_TITLES[2])
    cursor.drawWrappedParagraph("Datos tal como se ingresaron en campo. Las lecturas corregidas muestran el valor original y el vigente.", MARGIN, CONTENT_WIDTH, fonts.caption)

    cursor.drawSubHeader("3.1 Libreta de nivelación (nivel de ingeniero)", fonts)
    if (data.levelRuns.isEmpty()) {
        cursor.drawWrappedParagraph("No hay nivelaciones registradas.", MARGIN, CONTENT_WIDTH, fonts.bodyItalic)
    }
    for (run in data.levelRuns) {
        cursor.keepTogether(60f)
        cursor.drawWrappedParagraph(
            "Nivelación «${run.name}» — BM inicial ${run.startBmCode} (cota ${fmt(run.startElevation)})" +
                (run.endBmCode?.let { " — BM final $it" + (run.knownEndElevation?.let { k -> " (cota ${fmt(k)})" } ?: "") } ?: ""),
            MARGIN, CONTENT_WIDTH, fonts.bodyBold,
        )
        cursor.drawTable(
            fonts,
            listOf(TableColumn("N°", 0.06f), TableColumn("Est.", 0.06f), TableColumn("Punto", 0.14f), TableColumn("Tipo", 0.07f),
                TableColumn("Lectura", 0.12f, mono = true, alignRight = true), TableColumn("Dist. parcial", 0.12f, mono = true, alignRight = true),
                TableColumn("P. cambio", 0.08f), TableColumn("Observación / corrección", 0.35f)),
            run.inputRows.map { r ->
                listOf(
                    r.sequence.toString(), r.station.toString(), r.pointCode, r.typeLabel, fmt(r.reading),
                    if (r.partialDistance == null) "—" else fmt(r.partialDistance),
                    if (r.turningPoint) "Sí" else "",
                    listOfNotNull(r.notes?.takeIf { it.isNotBlank() }, r.correction).joinToString(" · "),
                )
            },
        )
        cursor.y += 6f
    }

    cursor.drawSubHeader("3.2 Libreta de radiación (teodolito)", fonts)
    if (data.radiationStations.isEmpty()) cursor.drawWrappedParagraph("No hay estaciones con radiaciones registradas.", MARGIN, CONTENT_WIDTH, fonts.bodyItalic)
    for (st in data.radiationStations) {
        cursor.keepTogether(70f)
        cursor.drawWrappedParagraph(
            "Estación ${st.stationCode} — ${stationCoordText(st)} — ${st.coordinateOrigin}" + (st.instrumentHeight?.let { " — altura del instrumento ${fmt(it)}" } ?: ""),
            MARGIN, CONTENT_WIDTH, fonts.bodyBold,
        )
        cursor.drawWrappedParagraph("Orientación: ${st.orientationText}", MARGIN, CONTENT_WIDTH, fonts.body)
        cursor.drawTable(
            fonts,
            listOf(TableColumn("Punto", 0.12f), TableColumn("Lectura original (horizontal · vertical · distancia)", 0.46f), TableColumn("Descripción", 0.22f), TableColumn("Corrección", 0.20f)),
            st.rows.map { r -> listOf(r.code, r.originalReading, r.description?.takeIf { it.isNotBlank() } ?: "—", r.correction ?: "") },
        )
        cursor.y += 6f
    }
}

private fun stationCoordText(st: ReportRadiationStation): String =
    if (st.stationEast == null || st.stationNorth == null) "coordenadas no conocidas" else "E = ${fmt(st.stationEast)}, N = ${fmt(st.stationNorth)}"

// 4. Memoria de cálculo de altimetría ---------------------------------------------------------------

private fun drawLevelingMemory(ctx: Ctx) = with(ctx) {
    startSection(SECTION_TITLES[3])
    cursor.drawWrappedParagraph("HI = cota de referencia + VA   ·   Cota = HI − VI (o − VD)   ·   Comprobación: Σ VA − Σ VD = cota final − cota inicial.", MARGIN, CONTENT_WIDTH, fonts.bodyMono)
    if (data.levelRuns.isEmpty()) cursor.drawWrappedParagraph("No hay nivelaciones calculadas.", MARGIN, CONTENT_WIDTH, fonts.bodyItalic)
    for (run in data.levelRuns) {
        cursor.drawSubHeader("Nivelación «${run.name}»", fonts)
        if (run.state != ResultState.ACTUALIZADO) cursor.drawBanner("Estado del cálculo: ${run.state.labelEs}.", fonts)
        cursor.drawTable(
            fonts,
            listOf(TableColumn("Est.", 0.05f), TableColumn("Punto", 0.11f), TableColumn("Tipo", 0.06f), TableColumn("Lectura", 0.10f, mono = true, alignRight = true),
                TableColumn("Dist. parcial", 0.10f, mono = true, alignRight = true), TableColumn("Dist. acum.", 0.10f, mono = true, alignRight = true),
                TableColumn("Operación", 0.30f, mono = true), TableColumn("Cota", 0.12f, mono = true, alignRight = true)),
            run.calcRows.map { r ->
                listOf(r.station.toString(), r.pointCode, r.typeLabel, fmt(r.reading),
                    if (r.partialDistance == null) "—" else fmt(r.partialDistance),
                    if (r.cumulativeDistance == null) "—" else fmt(r.cumulativeDistance),
                    r.operation, fmt(r.elevation))
            },
        )
        cursor.y += 4f
        val delta = run.finalElevation - run.startElevation
        cursor.drawBulletItem("Σ VA = ${fmt(run.sumBacksight)};  Σ VD = ${fmt(run.sumForesight)};  Σ VA − Σ VD = ${fmt(run.sumBacksight - run.sumForesight)}", fonts.body)
        cursor.drawBulletItem("Diferencia de cotas calculadas = ${fmt(run.finalElevation)} − ${fmt(run.startElevation)} = ${fmt(delta)}", fonts.body)
        cursor.drawBulletItem(
            if (run.arithmeticCheckOk) "Comprobación aritmética CONFORME (la suma de vistas coincide con la diferencia de cotas)."
            else "Comprobación aritmética NO CONFORME: la suma de vistas no coincide con la diferencia de cotas; revise la libreta.",
            if (run.arithmeticCheckOk) fonts.body else fonts.bodyBold,
        )
        cursor.drawBulletItem(
            if (run.misclosure != null && run.knownEndElevation != null)
                "Error de cierre contra ${run.endBmCode ?: "el BM final"} (cota conocida ${fmt(run.knownEndElevation)}): ${fmt(run.finalElevation)} − ${fmt(run.knownEndElevation)} = ${fmt(run.misclosure)}."
            else "No existe BM final de cota conocida: el cierre contra control externo no puede comprobarse.",
            fonts.body,
        )
        run.warnings.forEach { cursor.drawBulletItem("Advertencia: $it", fonts.pending) }
        cursor.y += 6f
    }
}

// 5. Memoria de cálculo de planimetría --------------------------------------------------------------

private fun drawRadiationMemory(ctx: Ctx) = with(ctx) {
    startSection(SECTION_TITLES[4])
    cursor.drawWrappedParagraph("A = azimut desde el norte en sentido horario · ΔE = d·sen(A) · ΔN = d·cos(A) · E₂ = E₁ + ΔE · N₂ = N₁ + ΔN", MARGIN, CONTENT_WIDTH, fonts.bodyMono)
    if (data.planState != ResultState.ACTUALIZADO) cursor.drawBanner("Estado del cálculo de planimetría: ${data.planState.labelEs}.", fonts)
    if (data.radiationStations.isEmpty()) cursor.drawWrappedParagraph("No hay radiaciones calculadas.", MARGIN, CONTENT_WIDTH, fonts.bodyItalic)
    for (st in data.radiationStations) {
        cursor.drawSubHeader("Estación ${st.stationCode}", fonts)
        cursor.drawWrappedParagraph("${stationCoordText(st)} — ${st.coordinateOrigin}. Orientación: ${st.orientationText}", MARGIN, CONTENT_WIDTH, fonts.body)
        cursor.y += 3f
        cursor.drawTable(
            fonts,
            listOf(TableColumn("Punto", 0.10f), TableColumn("Azimut", 0.20f, mono = true), TableColumn("d usada", 0.12f, mono = true, alignRight = true),
                TableColumn("ΔE", 0.12f, mono = true, alignRight = true), TableColumn("ΔN", 0.12f, mono = true, alignRight = true),
                TableColumn("Este", 0.17f, mono = true, alignRight = true), TableColumn("Norte", 0.17f, mono = true, alignRight = true)),
            st.rows.map { r -> listOf(r.code, r.azimuthText ?: "—", fmt(r.distanceUsed), fmt(r.deltaE), fmt(r.deltaN), fmt(r.east), fmt(r.north)) },
        )
        cursor.y += 4f
        cursor.drawSubHeader("Operaciones con valores sustituidos", fonts)
        for (r in st.rows) {
            cursor.keepTogether(fonts.bodyMono.fontSpacing * 3 + 4f)
            cursor.drawTextLine("${r.code}${r.description?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}", MARGIN, fonts.bodyBold)
            if (r.errors.isNotEmpty()) r.errors.forEach { cursor.drawWrappedParagraph("No calculado: $it", MARGIN + 8f, CONTENT_WIDTH - 8f, fonts.pending) }
            r.formulaLines.forEach { cursor.drawWrappedParagraph(it, MARGIN + 8f, CONTENT_WIDTH - 8f, fonts.bodyMono) }
            cursor.y += 3f
        }
    }
}

// 6. Tabla final y perfil longitudinal ------------------------------------------------------------------

private fun drawFinalTableAndProfile(ctx: Ctx) = with(ctx) {
    startSection(SECTION_TITLES[5])
    cursor.drawSubHeader("6.1 Tabla final de puntos", fonts)
    cursor.drawWrappedParagraph("Valores calculados a partir de las lecturas; la columna «Origen de la cota» indica si procede del nivel de ingeniero o de un control conocido.", MARGIN, CONTENT_WIDTH, fonts.caption)
    cursor.y += 2f
    cursor.drawTable(
        fonts,
        listOf(TableColumn("Código", 0.10f), TableColumn("Descripción", 0.22f), TableColumn("Procedimiento", 0.12f), TableColumn("Este", 0.14f, mono = true, alignRight = true),
            TableColumn("Norte", 0.14f, mono = true, alignRight = true), TableColumn("Cota", 0.12f, mono = true, alignRight = true), TableColumn("Origen de la cota", 0.16f)),
        data.points.map { r -> listOf(r.code, r.description?.takeIf { it.isNotBlank() } ?: "—", r.procedure, fmt(r.east), fmt(r.north), fmt(r.elevation), r.elevationSource) },
    )
    cursor.y += 8f
    cursor.drawSubHeader("6.2 Perfil longitudinal", fonts)
    if (data.levelRuns.isEmpty()) cursor.drawWrappedParagraph("No hay nivelaciones: no se dibuja perfil longitudinal.", MARGIN, CONTENT_WIDTH, fonts.bodyItalic)
    for (run in data.levelRuns) {
        cursor.keepTogether(280f)
        cursor.drawTextLine("Nivelación «${run.name}»", MARGIN, fonts.bodyBold)
        val prof = run.profile
        if (!prof.valid) {
            cursor.drawBanner("Perfil no dibujado: ${prof.reason ?: "datos insuficientes"}", fonts)
            if (prof.incomplete.isNotEmpty()) {
                cursor.drawTable(fonts, listOf(TableColumn("Punto incompleto", 0.3f), TableColumn("Falta", 0.7f)), prof.incomplete.map { listOf(it.code, it.missing) })
            }
            continue
        }
        val h = 220f
        val rect = RectF(MARGIN, cursor.y, MARGIN + CONTENT_WIDTH, cursor.y + h)
        val plotWidthMm = (CONTENT_WIDTH - 60f) * 25.4 / 72.0
        var scales: ProfileScales? = null
        if (heavy) scales = ProfileRenderer.draw(cursor.canvas, prof, data.project.unitsDistance, rect, PlanStyle(1f, fonts.regularTypeface), plotWidthMm)
        else scales = ProfileRenderer.scalesFor(prof, plotWidthMm, plotWidthMm * (h - 38f) / (CONTENT_WIDTH - 60f))
        cursor.y += h + 2f
        cursor.drawWrappedParagraph(scales?.text() ?: "", MARGIN, CONTENT_WIDTH, fonts.caption)
        cursor.y += 4f
    }
}

// 7. Plano y curvas ---------------------------------------------------------------------------------------

private fun drawPlanSection(ctx: Ctx) = with(ctx) {
    startSection(SECTION_TITLES[6])
    val plan = data.plan
    if (data.planState != ResultState.ACTUALIZADO) cursor.drawBanner("Estado del cálculo de planimetría: ${data.planState.labelEs}.", fonts)
    if (plan.points.isEmpty()) {
        cursor.drawWrappedParagraph("Este proyecto no tiene puntos con coordenadas calculadas: no hay plano que mostrar.", MARGIN, CONTENT_WIDTH, fonts.bodyItalic)
    } else {
        val h = 470f
        cursor.ensureSpace(h + 10f)
        val rect = RectF(MARGIN, cursor.y, MARGIN + CONTENT_WIDTH, cursor.y + h)
        val bounds = plan.bounds()!!
        val vp = PlanRenderer.fit(bounds, rect, 42f)
        if (heavy) {
            cursor.canvas.drawRect(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xcc, 0xcc, 0xcc); style = Paint.Style.STROKE; strokeWidth = 0.75f })
            val style = PlanStyle(1f, fonts.regularTypeface)
            val layers = plan.availableLayers()
            PlanRenderer.draw(cursor.canvas, plan, layers, vp, rect, style)
            PlanRenderer.drawFurniture(cursor.canvas, plan, layers, vp, rect, style, PlanRenderer.scaleDenominator(vp.scale, if (plan.unitsDistance == "ft") 0.3048 else 1.0))
        }
        cursor.y += h + 6f
        if (plan.pointsWithoutCoordinates > 0) cursor.drawWrappedParagraph("${plan.pointsWithoutCoordinates} punto(s) sin coordenadas calculadas no se muestran en el plano.", MARGIN, CONTENT_WIDTH, fonts.caption)
    }
    cursor.drawSubHeader("Curvas de nivel", fonts)
    val c = data.contourSummary
    if (c.applicable) {
        cursor.drawBulletItem("Equidistancia ${fmt(c.equidistance)} ${data.project.unitsDistance}; ${c.levels} nivel(es); ${c.usedPoints} punto(s) con Este, Norte y cota.", fonts.body)
        cursor.drawBulletItem("Método: ${c.method}.", fonts.body)
        cursor.drawBulletItem("Capas del plano: " + PlanLayer.entries.filter { it in plan.availableLayers() }.joinToString { it.labelEs } + ".", fonts.body)
    } else {
        cursor.drawBanner("No se generaron curvas de nivel: ${c.reason ?: "datos insuficientes"}", fonts)
    }
}

// 8. Panel fotográfico -------------------------------------------------------------------------------------

private const val PHOTO_BOX_W = 300f
private const val PHOTO_BOX_H = 225f

private fun drawPhotoPanel(ctx: Ctx) = with(ctx) {
    startSection(SECTION_TITLES[7])
    if (data.photos.isEmpty()) { cursor.drawWrappedParagraph("Este proyecto no tiene puntos registrados.", MARGIN, CONTENT_WIDTH, fonts.bodyItalic); return@with }
    cursor.drawWrappedParagraph("Puntos en orden de código. Las fotografías se muestran completas, sin recortes ni deformación.", MARGIN, CONTENT_WIDTH, fonts.caption)
    for (entry in data.photos) {
        val file = entry.photoFilePath?.let { File(it) }
        val size = file?.let { imageDisplaySize(it) }
        cursor.keepTogether(if (size != null) 40f + PHOTO_BOX_H else 40f)
        cursor.y += 6f
        val title = entry.code + (entry.description?.takeIf { it.isNotBlank() }?.let { " — ${it.trim()}" } ?: "")
        cursor.drawWrappedParagraph(title, MARGIN, CONTENT_WIDTH, fonts.bodyBold)
        cursor.y += 2f
        if (size != null) {
            drawFittedImage(this, file!!, size, PHOTO_BOX_W, PHOTO_BOX_H)
            entry.caption?.takeIf { it.isNotBlank() }?.let { cursor.drawWrappedParagraph("Pie de foto: $it", MARGIN, CONTENT_WIDTH, fonts.caption) }
        } else {
            cursor.drawWrappedParagraph("Sin fotografía registrada", MARGIN, CONTENT_WIDTH, fonts.bodyItalic)
        }
        cursor.y += 4f
    }
}

/** Reserva el alto exacto que ocuparía la imagen encajada; la decodifica y dibuja solo en la pasada definitiva. */
private fun drawFittedImage(ctx: Ctx, file: File, size: Pair<Int, Int>, maxW: Float, maxH: Float, maxSide: Int = 1400) {
    val scale = minOf(maxW / size.first, maxH / size.second)
    val w = size.first * scale; val h = size.second * scale
    ctx.cursor.ensureSpace(h)
    if (ctx.heavy) {
        val bmp = decodeOriented(file, maxSide)
        if (bmp != null) {
            val dst = RectF(MARGIN, ctx.cursor.y, MARGIN + w, ctx.cursor.y + h)
            ctx.cursor.canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            ctx.cursor.canvas.drawRect(dst, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xcc, 0xcc, 0xcc); style = Paint.Style.STROKE; strokeWidth = 0.5f })
            bmp.recycle()
        }
    }
    ctx.cursor.y += h + 2f
}

// 9. Anexos ----------------------------------------------------------------------------------------------------

internal fun evaluateAnnexes(annexes: List<ReportAnnex>): List<AnnexEmbedStatus> = annexes.map { a ->
    val file = File(a.filePath)
    when {
        !file.isFile -> AnnexEmbedStatus(a, false, 0, null, "No incorporado: el archivo no se encontró en el dispositivo.")
        a.kind == AnnexFileKind.IMAGE -> {
            if (imageDisplaySize(file) != null) AnnexEmbedStatus(a, true, 1, 1, "Incorporado al PDF (1 página).")
            else AnnexEmbedStatus(a, false, 0, null, "No incorporado: la imagen no se pudo leer (archivo dañado). Se conserva en el respaldo.")
        }
        a.kind == AnnexFileKind.PDF -> {
            val n = PdfFiles.pageCount(file)
            when {
                n == null -> AnnexEmbedStatus(a, false, 0, null, "No incorporado: el PDF no se pudo abrir (dañado o protegido con contraseña). Se conserva en el respaldo.")
                n > MAX_EMBEDDED_PDF_PAGES_PER_ANNEX -> AnnexEmbedStatus(a, true, MAX_EMBEDDED_PDF_PAGES_PER_ANNEX, n,
                    "Incorporado parcialmente: primeras $MAX_EMBEDDED_PDF_PAGES_PER_ANNEX de $n páginas; el documento completo está en el respaldo.")
                else -> AnnexEmbedStatus(a, true, n, n, "Incorporado al PDF ($n página${if (n == 1) "" else "s"}).")
            }
        }
        else -> AnnexEmbedStatus(a, false, 0, null, "No incorporado: este formato no se puede insertar en el PDF. Se conserva como archivo descargable en el respaldo del proyecto.")
    }
}

private fun drawAnnexes(ctx: Ctx) = with(ctx) {
    startSection(SECTION_TITLES[8])
    cursor.drawSubHeader("Índice de anexos", fonts)
    if (annexStatuses.isEmpty()) {
        cursor.drawWrappedParagraph("Este proyecto no tiene anexos registrados.", MARGIN, CONTENT_WIDTH, fonts.bodyItalic)
        return@with
    }
    cursor.drawTable(
        fonts,
        listOf(TableColumn("N°", 0.05f), TableColumn("Título", 0.20f), TableColumn("Archivo original", 0.18f), TableColumn("Tipo", 0.10f),
            TableColumn("Fecha", 0.09f), TableColumn("Relación", 0.12f), TableColumn("Estado en este PDF", 0.26f)),
        annexStatuses.map { s ->
            val a = s.annex
            listOf(a.number.toString(), a.title, a.originalName, a.typeLabel, a.addedAt.take(10), a.relatedTo ?: "—", s.note)
        },
    )
    cursor.y += 6f
    for (s in annexStatuses.filter { !it.annex.description.isNullOrBlank() }) {
        cursor.drawBulletItem("Anexo ${s.annex.number} — ${s.annex.title}: ${s.annex.description!!.trim()}", fonts.body)
    }

    // Contenido incorporable: una página por imagen / por página de PDF
    for (s in annexStatuses.filter { it.embedded }) {
        val a = s.annex
        val file = File(a.filePath)
        when (a.kind) {
            AnnexFileKind.IMAGE -> {
                cursor.newPage()
                annexPageHeader(this, a, "")
                val size = imageDisplaySize(file)
                if (size != null) drawFittedImage(this, file, size, CONTENT_WIDTH, BODY_BOTTOM - cursor.y - 6f, 2000)
            }
            AnnexFileKind.PDF -> {
                val pages = (0 until s.pagesEmbedded).toList()
                if (heavy) {
                    PdfFiles.renderPages(file, pages, (CONTENT_WIDTH / 72f * 150f).toInt()) { i, bmp ->
                        cursor.newPage()
                        annexPageHeader(this, a, "página ${i + 1} de ${s.totalPages}")
                        if (bmp != null) {
                            cursor.drawFittedBitmap(bmp, CONTENT_WIDTH, BODY_BOTTOM - cursor.y - 6f, centered = true)
                            bmp.recycle()
                        } else cursor.drawWrappedParagraph("No se pudo dibujar esta página del documento.", MARGIN, CONTENT_WIDTH, fonts.pending)
                    }
                } else {
                    pages.forEach { _ -> cursor.newPage() } // medición: una página por página incorporada
                }
            }
            AnnexFileKind.OTHER -> Unit
        }
    }
}

private fun annexPageHeader(ctx: Ctx, a: ReportAnnex, extra: String) = with(ctx) {
    ctx.sectionPages.putIfAbsent("anexo-${a.number}", cursor.pageNumber)
    cursor.drawTextLine("Anexo ${a.number} — ${a.title}" + if (extra.isNotEmpty()) " ($extra)" else "", MARGIN, fonts.subHeader)
    cursor.drawWrappedParagraph("${a.originalName} · ${a.typeLabel} · incorporado el ${a.addedAt.take(10)}" + (a.relatedTo?.let { " · relacionado con $it" } ?: ""), MARGIN, CONTENT_WIDTH, fonts.caption)
    cursor.y += 4f
}

// 10. Observaciones, datos pendientes y limitaciones ---------------------------------------------------------------

private fun drawObservations(ctx: Ctx) = with(ctx) {
    startSection(SECTION_TITLES[9])
    cursor.drawSubHeader("Observaciones del responsable", fonts)
    cursor.drawWrappedParagraph(pending(data.project.observations), MARGIN, CONTENT_WIDTH, fonts.body)
    cursor.drawSubHeader("Datos pendientes y limitaciones detectados por la aplicación", fonts)
    if (data.pendingItems.isEmpty()) cursor.drawWrappedParagraph("No se detectaron datos pendientes.", MARGIN, CONTENT_WIDTH, fonts.body)
    data.pendingItems.forEach { cursor.drawBulletItem(it, fonts.body) }
}
