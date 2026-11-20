package com.toposcribe.io

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.toposcribe.calc.PlanData
import com.toposcribe.calc.ReportData
import com.toposcribe.calc.ReportLevelingCheck
import com.toposcribe.calc.ReportPhoto
import com.toposcribe.calc.ReportProjectInfo
import com.toposcribe.calc.ReportTraverseClosure
import java.io.ByteArrayOutputStream

/**
 * Generador del informe PDF, en Kotlin puro de Android (android.graphics.pdf.PdfDocument, sin
 * librerías de terceros). Replica EXACTAMENTE las 4 secciones ya verificadas en la versión web
 * (PDFKit/pdfmake, ver server/src/importExport/pdfReport.ts en topografia-app): 1) Memoria
 * descriptiva, 2) Memoria de cálculo, 3) Planos, 4) Panel fotográfico.
 *
 * Nota sobre fuentes y Unicode: la versión web usaba Helvetica estándar + WinAnsiEncoding, que NO
 * soporta letras griegas (Δ, Σ, α...) ni el signo menos Unicode (−) -- se corrompían en el PDF
 * exportado y hubo que armar sanitizeForPdf() (ver PdfLayoutHelpers.kt) para reemplazarlos por
 * texto ASCII legible antes de pasarlos a PDFKit. Acá dibujamos con Canvas.drawText sobre un
 * Typeface de un .ttf real (Inter), que sí tiene buena cobertura Unicode y probablemente no
 * necesita el saneo -- pero se aplica igual como red de seguridad barata para el caso en que se
 * corra sin esa fuente cargada (fallback a Typeface.DEFAULT, que podría no tener esos glifos).
 */
fun buildReportPdf(
    data: ReportData,
    regularTypeface: Typeface,
    boldTypeface: Typeface,
    monoTypeface: Typeface,
): ByteArray {
    val document = PdfDocument()
    val fonts = PdfFonts(regularTypeface, boldTypeface, monoTypeface)
    val cursor = PdfCursor(document)

    drawTitleAndBanner(cursor, fonts, data)
    drawSection1DescriptiveMemory(cursor, fonts, data.project)
    drawSection2CalculationMemory(cursor, fonts, data)
    drawSection3Plans(cursor, fonts, data.planData)
    drawSection4PhotoPanel(cursor, fonts, data.photos)

    cursor.finish()
    val output = ByteArrayOutputStream()
    document.writeTo(output)
    document.close()
    return output.toByteArray()
}

/** Cualquier campo nulo o vacío se imprime literalmente así -- nunca se omite la fila ni se
 * inventa contenido (principio de fondo de toda la app: nunca se inventa un dato). */
private fun pending(value: String?): String {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) "(pendiente de completar)" else trimmed
}

// ---------------------------------------------------------------------------------------------
// Título + banner de datos de demostración
// ---------------------------------------------------------------------------------------------

private fun drawTitleAndBanner(cursor: PdfCursor, fonts: PdfFonts, data: ReportData) {
    cursor.drawTextLine("Informe de levantamiento topográfico", MARGIN, fonts.title)
    cursor.y += 2f
    cursor.drawWrappedParagraph(data.project.name, MARGIN, CONTENT_WIDTH, fonts.subtitle)
    cursor.y += 8f
    if (data.project.isDemo) {
        drawWarningBanner(cursor, fonts, "DATOS DE DEMOSTRACIÓN — este proyecto no representa un levantamiento real.")
    }
}

private fun drawWarningBanner(cursor: PdfCursor, fonts: PdfFonts, text: String) {
    val sanitized = sanitizeForPdf(text)
    val paint = fonts.warning
    val innerWidth = (CONTENT_WIDTH - 16f).toInt()
    val layout = buildStaticLayout(sanitized, paint, innerWidth)
    val boxHeight = layout.height + 12f
    cursor.ensureSpace(boxHeight + 10f)
    val top = cursor.y
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xff, 0xf4, 0xd6); style = Paint.Style.FILL }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x8a, 0x53, 0x00)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    cursor.canvas.drawRect(MARGIN, top, MARGIN + CONTENT_WIDTH, top + boxHeight, bgPaint)
    cursor.canvas.drawRect(MARGIN, top, MARGIN + CONTENT_WIDTH, top + boxHeight, borderPaint)
    cursor.canvas.save()
    cursor.canvas.translate(MARGIN + 8f, top + 6f)
    layout.draw(cursor.canvas)
    cursor.canvas.restore()
    cursor.y = top + boxHeight + 10f
}

// ---------------------------------------------------------------------------------------------
// 1. Memoria descriptiva
// ---------------------------------------------------------------------------------------------

private fun drawSection1DescriptiveMemory(cursor: PdfCursor, fonts: PdfFonts, project: ReportProjectInfo) {
    cursor.drawSectionHeader("1. Memoria descriptiva", fonts)

    // "Estación total — Leica TS06": instrumentTypeLabel siempre presente, el modelo concreto
    // (instrument) se agrega solo si viene informado. Se copia a un val local porque Kotlin no
    // permite smart-cast de una propiedad nullable declarada en otro módulo (:calc) aunque sea val.
    val instrument = project.instrument
    val instrumentText = if (!instrument.isNullOrBlank()) {
        "${project.instrumentTypeLabel} — ${instrument.trim()}"
    } else {
        project.instrumentTypeLabel
    }

    val rows = listOf(
        "Finalidad" to project.purpose,
        "Ubicación" to project.location,
        "Características del lugar" to project.siteCharacteristics,
        "Fecha" to project.date,
        "Responsable(s)" to project.responsible,
        "Instrumento" to instrumentText,
        "Sistema de coordenadas" to project.coordinateSystemText,
        "Unidades" to "${project.unitsDistance} / ${project.unitsAngle}",
        "Procedimiento de campo" to project.fieldProcedure,
        "Observaciones" to project.observations,
    )

    cursor.drawTable(
        fonts,
        columns = listOf(TableColumn("", 0.32f), TableColumn("", 0.68f)),
        rows = rows.map { (label, value) -> listOf(label, pending(value)) },
        showHeader = false,
    )
}

// ---------------------------------------------------------------------------------------------
// 2. Memoria de cálculo
// ---------------------------------------------------------------------------------------------

private fun drawSection2CalculationMemory(cursor: PdfCursor, fonts: PdfFonts, data: ReportData) {
    cursor.drawSectionHeader("2. Memoria de cálculo", fonts)

    if (data.formulasUsed.isNotEmpty()) {
        cursor.drawSubHeader("Metodología (fórmulas usadas en este proyecto)", fonts)
        for (formula in data.formulasUsed) {
            cursor.drawBulletItem("${formula.labelEs}: ${formula.formulaText} (${formula.reference})", fonts.body)
        }
        cursor.y += 4f
    }

    cursor.drawSubHeader("Tabla de resultados", fonts)
    val resultColumns = listOf(
        TableColumn("Código", 0.10f),
        TableColumn("Descripción", 0.20f),
        TableColumn("Este", 0.12f, mono = true),
        TableColumn("Norte", 0.12f, mono = true),
        TableColumn("Cota", 0.12f, mono = true),
        TableColumn("Tipo", 0.10f),
        TableColumn("Origen", 0.24f),
    )
    val resultRows = data.results.map { r ->
        listOf(
            r.code,
            r.description?.trim()?.takeIf { it.isNotEmpty() } ?: "—",
            fmt(r.east),
            fmt(r.north),
            fmt(r.elevation),
            r.roleLabel,
            r.origin,
        )
    }
    cursor.drawTable(fonts, resultColumns, resultRows, showHeader = true)
    cursor.y += 6f

    if (data.levelingChecks.isNotEmpty() || data.traverseClosures.isNotEmpty()) {
        cursor.drawSubHeader("Comprobaciones y cierres", fonts)
    }

    for (check in data.levelingChecks) {
        drawLevelingCheck(cursor, fonts, check)
    }
    for (closure in data.traverseClosures) {
        drawTraverseClosure(cursor, fonts, closure)
    }
}

/** Distingue deliberadamente dos comprobaciones distintas: la aritmética (ΣBS-ΣFS = cota final -
 * inicial) es una identidad que siempre se cumple si la libreta está bien sumada; el cierre de
 * campo contra una cota de cierre conocida es la verificación real de error de campo, y solo
 * existe si hay una cota de cierre independiente -- si no la hay, se explica por qué en vez de
 * inventar o callar el resultado. */
private fun drawLevelingCheck(cursor: PdfCursor, fonts: PdfFonts, check: ReportLevelingCheck) {
    val diff = check.finalElevation - check.startElevation
    val checkLine = "Nivelación \"${check.runName}\": ΣBS=${fmt(check.sumBacksight)}, ΣFS=${fmt(check.sumForesight)}, " +
        "ΣBS−ΣFS=${fmt(check.checkValue)}; cota final − inicial=${fmt(diff)} (${fmt(check.startElevation)} → ${fmt(check.finalElevation)}). " +
        if (check.arithmeticCheckOk) "Comprobación de libreta conforme." else "Comprobación de libreta NO conforme: revisar la libreta."
    cursor.drawWrappedParagraph(checkLine, MARGIN, CONTENT_WIDTH, fonts.body)
    cursor.y += 2f

    val closureLine = if (check.fieldMisclosure != null) {
        "Cierre de campo contra cota de cierre conocida (${fmt(check.knownEndElevation)}): error = ${fmt(check.fieldMisclosure)}."
    } else {
        "No se calculó cierre de campo: esta nivelación no tiene una cota de cierre conocida independiente."
    }
    cursor.drawWrappedParagraph(closureLine, MARGIN, CONTENT_WIDTH, fonts.body)
    cursor.y += 6f
}

private fun drawTraverseClosure(cursor: PdfCursor, fonts: PdfFonts, closure: ReportTraverseClosure) {
    var line = "Poligonal \"${closure.traverseName}\" (${closure.typeLabel}): fE=${fmt(closure.fE)}, fN=${fmt(closure.fN)}, " +
        "fL=${fmt(closure.fL)}, precisión relativa ${closure.relativePrecision}"
    if (closure.angularMisclosureDeg != null) {
        line += ", cierre angular=${fmt(closure.angularMisclosureDeg, 5)}°"
    }
    cursor.drawWrappedParagraph(line, MARGIN, CONTENT_WIDTH, fonts.body)
    cursor.y += 2f

    if (closure.stations.isNotEmpty()) {
        cursor.drawWrappedParagraph(
            "Coordenadas originales (bruto) y corregidas (ajuste Bowditch) por estación:",
            MARGIN,
            CONTENT_WIDTH,
            fonts.bodyItalic,
        )
        cursor.y += 2f
        val columns = listOf(
            TableColumn("Punto", 0.16f),
            TableColumn("Este bruto", 0.21f, mono = true),
            TableColumn("Norte bruto", 0.21f, mono = true),
            TableColumn("Este corregido", 0.21f, mono = true),
            TableColumn("Norte corregido", 0.21f, mono = true),
        )
        val rows = closure.stations.map { s ->
            listOf(s.pointCode, fmt(s.rawEast), fmt(s.rawNorth), fmt(s.adjustedEast), fmt(s.adjustedNorth))
        }
        cursor.drawTable(fonts, columns, rows, showHeader = true)
    }
    cursor.y += 6f
}

// ---------------------------------------------------------------------------------------------
// 3. Planos
// ---------------------------------------------------------------------------------------------

private fun drawSection3Plans(cursor: PdfCursor, fonts: PdfFonts, planData: PlanData) {
    cursor.drawSectionHeader("3. Planos", fonts)

    if (planData.points.isEmpty()) {
        cursor.drawWrappedParagraph(
            "Este proyecto no tiene puntos con coordenadas calculadas todavía.",
            MARGIN,
            CONTENT_WIDTH,
            fonts.body,
        )
    } else {
        drawPlanDrawing(cursor, fonts, planData)
    }

    if (planData.pointsWithoutCoords > 0) {
        cursor.y += 2f
        cursor.drawWrappedParagraph(
            "${planData.pointsWithoutCoords} punto(s) sin coordenadas calculadas no se muestran.",
            MARGIN,
            CONTENT_WIDTH,
            fonts.caption,
        )
    }
}

/** Dibuja puntos + líneas de planData con UNA ÚNICA escala uniforme calculada desde el bounding
 * box de los puntos (nunca escalas X/Y independientes, deformaría el dibujo). Si todos los puntos
 * comparten la misma coordenada en un eje (línea perfectamente vertical/horizontal, o un solo
 * punto), ese eje degenerado simplemente no participa en el cálculo de la escala -- se sigue
 * usando un único número de escala, solo que elegido a partir del eje que sí tiene extensión. */
private fun drawPlanDrawing(cursor: PdfCursor, fonts: PdfFonts, planData: PlanData) {
    val drawHeight = 440f
    cursor.ensureSpace(drawHeight + 8f)
    val top = cursor.y
    val left = MARGIN
    val width = CONTENT_WIDTH

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xcc, 0xcc, 0xcc)
        style = Paint.Style.STROKE
        strokeWidth = 0.75f
    }
    cursor.canvas.drawRect(left, top, left + width, top + drawHeight, borderPaint)

    val points = planData.points
    val minE = points.minOf { it.east }
    val maxE = points.maxOf { it.east }
    val minN = points.minOf { it.north }
    val maxN = points.maxOf { it.north }
    val pad = 24f
    val rawW = maxE - minE
    val rawH = maxN - minN

    val scaleCandidates = mutableListOf<Float>()
    if (rawW > 1e-9) scaleCandidates.add((width - 2 * pad) / rawW.toFloat())
    if (rawH > 1e-9) scaleCandidates.add((drawHeight - 2 * pad) / rawH.toFloat())
    val scale = scaleCandidates.minOrNull() ?: 1f

    val contentW = rawW.toFloat() * scale
    val contentH = rawH.toFloat() * scale
    val offsetX = left + pad + ((width - 2 * pad) - contentW) / 2f
    val offsetY = top + pad + ((drawHeight - 2 * pad) - contentH) / 2f

    fun toX(e: Double) = offsetX + (e - minE).toFloat() * scale
    fun toY(n: Double) = offsetY + contentH - (n - minN).toFloat() * scale // Norte hacia arriba: Y de canvas invertida.

    val pointById = points.associateBy { it.pointId }
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x33, 0x33, 0x33)
        style = Paint.Style.STROKE
        strokeWidth = 0.8f
    }
    for (planLine in planData.lines) {
        val a = pointById[planLine.fromPointId] ?: continue
        val b = pointById[planLine.toPointId] ?: continue
        cursor.canvas.drawLine(toX(a.east), toY(a.north), toX(b.east), toY(b.north), linePaint)
    }

    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1a, 0x1a, 0x1a); style = Paint.Style.FILL }
    for (p in points) {
        val px = toX(p.east)
        val py = toY(p.north)
        cursor.canvas.drawCircle(px, py, 2f, dotPaint)
        cursor.canvas.drawText(sanitizeForPdf(p.code), px + 4f, py - 4f, fonts.planLabel)
    }

    cursor.y = top + drawHeight + 6f
}

// ---------------------------------------------------------------------------------------------
// 4. Panel fotográfico
// ---------------------------------------------------------------------------------------------

private fun drawSection4PhotoPanel(cursor: PdfCursor, fonts: PdfFonts, photos: List<ReportPhoto>) {
    cursor.drawSectionHeader("4. Panel fotográfico", fonts)

    if (photos.isEmpty()) {
        cursor.drawWrappedParagraph("Este proyecto no tiene puntos registrados.", MARGIN, CONTENT_WIDTH, fonts.body)
        return
    }

    for (photo in photos) {
        cursor.y += 6f
        cursor.drawRichParagraph(buildCaptionTokens(photo, fonts), MARGIN, CONTENT_WIDTH)
        cursor.y += 2f

        // null, archivo inexistente o no decodificable = SIN foto; nunca se inventa/reemplaza por
        // una imagen de relleno.
        val bitmap = decodeBitmapFitted(photo.photoFilePath)
        if (bitmap != null) {
            cursor.drawFittedBitmap(bitmap, IMAGE_BOX_W, IMAGE_BOX_H)
            bitmap.recycle()
            val caption = photo.caption // val local: smart-cast no aplica a propiedades de otro módulo.
            if (!caption.isNullOrBlank()) {
                cursor.drawWrappedParagraph(caption, MARGIN, CONTENT_WIDTH, fonts.caption)
            }
        } else {
            cursor.drawWrappedParagraph("Sin fotografía registrada.", MARGIN, CONTENT_WIDTH, fonts.bodyItalic)
        }
        cursor.y += 4f
    }
}

private fun buildCaptionTokens(photo: ReportPhoto, fonts: PdfFonts): List<RichToken> {
    val tokens = mutableListOf<RichToken>()
    sanitizeForPdf(photo.code).split(" ").filter { it.isNotEmpty() }.forEach {
        tokens.add(RichToken(it, fonts.bodyBold))
    }

    val description = photo.description // vals locales: smart-cast no aplica a propiedades de otro módulo.
    val segment = photo.segment
    val restParts = mutableListOf<String>()
    if (!description.isNullOrBlank()) restParts.add("— ${description.trim()}")
    if (!segment.isNullOrBlank()) restParts.add("· Tramo: ${segment.trim()}")
    if (photo.chainage != null) restParts.add("· Progresiva: ${fmt(photo.chainage)}")

    if (restParts.isNotEmpty()) {
        sanitizeForPdf(restParts.joinToString(" ")).split(" ").filter { it.isNotEmpty() }.forEach {
            tokens.add(RichToken(it, fonts.body))
        }
    }
    return tokens
}
