package com.toposcribe.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.util.Locale
import kotlin.math.min

/**
 * Utilidades de bajo nivel para armar el PDF con android.graphics.pdf.PdfDocument (sin librerías de
 * terceros): cursor Y con paginación y pie de página numerado, estilos de texto, saneo de
 * caracteres, tablas con repetición de encabezado e imágenes encajadas SIN deformar ni recortar.
 * El CONTENIDO de cada sección vive en PdfReportBuilder.kt.
 */

internal const val PAGE_WIDTH = 595f // A4 en puntos (72 pt/in)
internal const val PAGE_HEIGHT = 842f
internal const val MARGIN = 36f
internal const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
internal const val FOOTER_HEIGHT = 22f
internal const val BODY_BOTTOM = PAGE_HEIGHT - MARGIN - FOOTER_HEIGHT

private val PDF_SAFE_REPLACEMENTS: Map<Char, String> = mapOf(
    'Δ' to "d", 'δ' to "d", 'Σ' to "Sum ", 'σ' to "sum ",
    'α' to "alfa", 'β' to "beta", 'γ' to "gamma", 'θ' to "theta", 'π' to "pi",
    '−' to "-", '→' to "->", '←' to "<-", '′' to "'", '″' to "\"",
)

/**
 * Red de seguridad: reemplaza los pocos caracteres que fallan si la fuente embebida no los trae.
 * Con Inter (cobertura completa de griego y signos matemáticos) no se aplica; solo se usa si el
 * tipo de letra cargado es el del sistema.
 */
internal fun sanitizeForPdf(text: String): String {
    if (text.none { PDF_SAFE_REPLACEMENTS.containsKey(it) }) return text
    val sb = StringBuilder(text.length)
    for (ch in text) sb.append(PDF_SAFE_REPLACEMENTS[ch] ?: ch.toString())
    return sb.toString()
}

internal fun fmt(value: Double?, digits: Int = 3): String =
    if (value == null) "—" else String.format(Locale.US, "%.${digits}f", value)

internal class PdfFonts(regular: Typeface, bold: Typeface, mono: Typeface) {
    private fun make(tf: Typeface, size: Float, color: Int = Color.BLACK, italic: Boolean = false): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = size; this.color = color
            if (italic) textSkewX = -0.25f
        }

    val regularTypeface = regular
    val title = make(bold, 22f)
    val subtitle = make(regular, 13f)
    val sectionHeader = make(bold, 13f)
    val subHeader = make(bold, 10.5f)
    val body = make(regular, 8f)
    val bodyBold = make(bold, 8f)
    val bodyItalic = make(regular, 8f, italic = true)
    val bodyMono = make(mono, 7.5f)
    val caption = make(regular, 8f, color = Color.rgb(0x5b, 0x64, 0x72), italic = true)
    val warning = make(bold, 9f, color = Color.rgb(0x8a, 0x53, 0x00))
    val tableHeader = make(bold, 8f)
    val footer = make(regular, 7f, color = Color.rgb(0x5b, 0x64, 0x72))
    val pending = make(regular, 8f, color = Color.rgb(0xa3, 0x39, 0x00), italic = true)

    val hairline: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xdd, 0xdd, 0xdd); strokeWidth = 0.5f }
}

/**
 * Cursor Y + ciclo de vida de las páginas. Cada página termina con un pie «Informe… · Página N de T».
 * [totalPages] es null en la primera pasada (se imprime «?») y el total real en la segunda.
 */
internal class PdfCursor(
    private val document: PdfDocument,
    private val fonts: PdfFonts,
    private val footerLeft: String,
    private val totalPages: Int?,
) {
    var y: Float = MARGIN
    var pageNumber = 1
        private set
    private var page: PdfDocument.Page = newPdfPage()
    val canvas: Canvas get() = page.canvas
    val pageCount: Int get() = pageNumber

    private fun newPdfPage(): PdfDocument.Page {
        val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageNumber).create()
        return document.startPage(info)
    }

    private fun drawFooter() {
        val c = page.canvas
        val lineY = PAGE_HEIGHT - MARGIN - FOOTER_HEIGHT + 6f
        c.drawLine(MARGIN, lineY, PAGE_WIDTH - MARGIN, lineY, fonts.hairline)
        val textY = lineY + 11f
        c.drawText(sanitizeForPdf(footerLeft), MARGIN, textY, fonts.footer)
        val right = "Página $pageNumber de ${totalPages ?: "?"}"
        c.drawText(right, PAGE_WIDTH - MARGIN - fonts.footer.measureText(right), textY, fonts.footer)
    }

    fun newPage() {
        drawFooter()
        document.finishPage(page)
        pageNumber += 1
        page = newPdfPage()
        y = MARGIN
    }

    /** true si tuvo que abrir una página nueva (para repetir encabezados de tabla). */
    fun ensureSpace(height: Float): Boolean {
        if (y + height > BODY_BOTTOM) { newPage(); return true }
        return false
    }

    /** Si lo que queda de página es menos de [height], salta de página (para no dejar un título huérfano). */
    fun keepTogether(height: Float) { ensureSpace(height) }

    fun finish() {
        drawFooter()
        document.finishPage(page)
    }
}

internal fun buildStaticLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
    val w = width.coerceAtLeast(1)
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
}

internal fun PdfCursor.drawTextLine(text: String, x: Float, paint: TextPaint) {
    ensureSpace(paint.fontSpacing)
    canvas.drawText(sanitizeForPdf(text), x, y - paint.ascent(), paint)
    y += paint.fontSpacing
}

internal fun PdfCursor.drawSectionHeader(text: String, fonts: PdfFonts) {
    ensureSpace(fonts.sectionHeader.fontSpacing + 40f)
    y += 10f
    drawTextLine(text, MARGIN, fonts.sectionHeader)
    canvas.drawLine(MARGIN, y + 1f, MARGIN + CONTENT_WIDTH, y + 1f, fonts.hairline)
    y += 6f
}

internal fun PdfCursor.drawSubHeader(text: String, fonts: PdfFonts) {
    ensureSpace(fonts.subHeader.fontSpacing + 28f)
    y += 6f
    drawTextLine(text, MARGIN, fonts.subHeader)
    y += 2f
}

/** Párrafo con wrap, con control de paginación POR LÍNEA para que un texto largo fluya entre páginas. */
internal fun PdfCursor.drawWrappedParagraph(text: String, x: Float, maxWidth: Float, paint: TextPaint) {
    val sanitized = sanitizeForPdf(text)
    if (sanitized.isEmpty()) return
    val layout = buildStaticLayout(sanitized, paint, maxWidth.toInt())
    val lineHeight = paint.fontSpacing
    for (i in 0 until layout.lineCount) {
        val lineText = sanitized.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd()
        ensureSpace(lineHeight)
        canvas.drawText(lineText, x, y - paint.ascent(), paint)
        y += lineHeight
    }
}

internal fun PdfCursor.drawBulletItem(text: String, paint: TextPaint) {
    val bulletIndent = 12f
    val sanitized = sanitizeForPdf(text)
    val layout = buildStaticLayout(sanitized, paint, (CONTENT_WIDTH - bulletIndent).toInt())
    val lineHeight = paint.fontSpacing
    for (i in 0 until layout.lineCount) {
        val lineText = sanitized.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd()
        ensureSpace(lineHeight)
        if (i == 0) canvas.drawText("•", MARGIN, y - paint.ascent(), paint)
        canvas.drawText(lineText, MARGIN + bulletIndent, y - paint.ascent(), paint)
        y += lineHeight
    }
    y += 1f
}

internal data class TableColumn(val header: String, val weight: Float, val mono: Boolean = false, val alignRight: Boolean = false)

/** Tabla genérica: columnas proporcionales, celdas con wrap, encabezado repetido en cada página. */
internal fun PdfCursor.drawTable(fonts: PdfFonts, columns: List<TableColumn>, rows: List<List<String>>, showHeader: Boolean = true) {
    val total = columns.sumOf { it.weight.toDouble() }.toFloat()
    val colWidths = columns.map { it.weight / total * CONTENT_WIDTH }
    val colX = FloatArray(columns.size)
    var acc = MARGIN
    for (i in columns.indices) { colX[i] = acc; acc += colWidths[i] }
    val padX = 3f
    val padY = 2.5f

    fun drawHeaderRow() {
        val paint = fonts.tableHeader
        val layouts = columns.mapIndexed { i, c -> buildStaticLayout(sanitizeForPdf(c.header), paint, (colWidths[i] - 2 * padX).toInt()) }
        val h = layouts.maxOf { it.lineCount } * paint.fontSpacing + 2 * padY
        ensureSpace(h)
        val top = y
        for (i in columns.indices) {
            canvas.save(); canvas.translate(colX[i] + padX, top + padY); layouts[i].draw(canvas); canvas.restore()
        }
        y = top + h
        canvas.drawLine(MARGIN, y, MARGIN + CONTENT_WIDTH, y, Paint(fonts.hairline).apply { color = Color.rgb(0x88, 0x88, 0x88) })
    }

    if (showHeader) drawHeaderRow()
    for (row in rows) {
        val paints = columns.map { if (it.mono) fonts.bodyMono else fonts.body }
        val layouts = row.mapIndexed { i, text ->
            val p = paints[i]
            StaticLayout.Builder.obtain(sanitizeForPdf(text), 0, sanitizeForPdf(text).length, p, (colWidths[i] - 2 * padX).toInt().coerceAtLeast(1))
                .setAlignment(if (columns[i].alignRight) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false).build()
        }
        val rowHeight = layouts.indices.maxOf { i -> layouts[i].lineCount * paints[i].fontSpacing } + 2 * padY
        val broke = ensureSpace(rowHeight)
        if (broke && showHeader) drawHeaderRow()
        val top = y
        for (i in layouts.indices) {
            canvas.save(); canvas.translate(colX[i] + padX, top + padY); layouts[i].draw(canvas); canvas.restore()
        }
        y = top + rowHeight
        canvas.drawLine(MARGIN, y, MARGIN + CONTENT_WIDTH, y, fonts.hairline)
    }
}

/**
 * Dibuja el bitmap encajado dentro de [maxW]×[maxH] manteniendo su proporción (una sola escala para
 * ambos ejes: nunca se deforma ni se recorta). Devuelve el alto ocupado.
 */
internal fun PdfCursor.drawFittedBitmap(bitmap: Bitmap, maxW: Float, maxH: Float, centered: Boolean = false): Float {
    val scale = min(maxW / bitmap.width, maxH / bitmap.height)
    val w = bitmap.width * scale
    val h = bitmap.height * scale
    ensureSpace(h)
    val left = if (centered) MARGIN + (CONTENT_WIDTH - w) / 2f else MARGIN
    val dst = RectF(left, y, left + w, y + h)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    canvas.drawBitmap(bitmap, null, dst, paint)
    canvas.drawRect(dst, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xcc, 0xcc, 0xcc); style = Paint.Style.STROKE; strokeWidth = 0.5f })
    y += h + 2f
    return h
}

internal fun PdfCursor.drawBanner(text: String, fonts: PdfFonts, bg: Int = Color.rgb(0xff, 0xf4, 0xd6), border: Int = Color.rgb(0x8a, 0x53, 0x00)) {
    val sanitized = sanitizeForPdf(text)
    val layout = buildStaticLayout(sanitized, fonts.warning, (CONTENT_WIDTH - 16f).toInt())
    val boxHeight = layout.height + 12f
    ensureSpace(boxHeight + 8f)
    val top = y
    canvas.drawRect(MARGIN, top, MARGIN + CONTENT_WIDTH, top + boxHeight, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg; style = Paint.Style.FILL })
    canvas.drawRect(MARGIN, top, MARGIN + CONTENT_WIDTH, top + boxHeight, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = border; style = Paint.Style.STROKE; strokeWidth = 1f })
    canvas.save(); canvas.translate(MARGIN + 8f, top + 6f); layout.draw(canvas); canvas.restore()
    y = top + boxHeight + 8f
}
