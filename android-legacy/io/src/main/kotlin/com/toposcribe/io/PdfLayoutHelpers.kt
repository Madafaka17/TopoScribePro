package com.toposcribe.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.util.Locale
import kotlin.math.min

/**
 * Utilidades de bajo nivel para armar el PDF con android.graphics.pdf.PdfDocument a mano (sin
 * librerías de terceros): manejo de página/cursor Y, estilos de texto reutilizables, saneo de
 * caracteres Unicode problemáticos, formateo numérico y un motor de tablas genérico. La lógica de
 * CONTENIDO (qué dice cada sección) vive en PdfReportBuilder.kt; este archivo solo sabe dibujar.
 */

internal const val PAGE_WIDTH = 595f // A4 en puntos (72 pt/in), igual a la versión web (pageMargins/tamaño de pdfmake).
internal const val PAGE_HEIGHT = 842f
internal const val MARGIN = 36f
internal const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN

internal const val IMAGE_BOX_W = 220f
internal const val IMAGE_BOX_H = 160f

// ---------------------------------------------------------------------------------------------
// Saneo de caracteres Unicode (red de seguridad, ver PdfReportBuilder.kt para el porqué completo)
// ---------------------------------------------------------------------------------------------

private val PDF_SAFE_REPLACEMENTS: Map<Char, String> = mapOf(
    'Δ' to "d", 'δ' to "d",
    'Σ' to "Sum ", 'σ' to "sum ",
    'α' to "alfa", 'β' to "beta", 'γ' to "gamma", 'θ' to "theta", 'π' to "pi",
    '−' to "-", '→' to "->", '←' to "<-",
    '′' to "'", '″' to "\"",
)

/** Reemplaza solo el puñado de caracteres que se sabe que fallaban con fuentes sin buena cobertura
 * Unicode (ver comentario largo en PdfReportBuilder.kt). El resto de Unicode (acentos, ñ, °, —,
 * etc.) se deja intacto. */
internal fun sanitizeForPdf(text: String): String {
    if (text.none { PDF_SAFE_REPLACEMENTS.containsKey(it) }) return text
    val sb = StringBuilder(text.length)
    for (ch in text) {
        val replacement = PDF_SAFE_REPLACEMENTS[ch]
        if (replacement != null) sb.append(replacement) else sb.append(ch)
    }
    return sb.toString()
}

/** Formatea un valor numérico opcional con punto decimal fijo (Locale.US, nunca coma decimal
 * dependiente del locale del dispositivo), o un guion largo "—" si es null -- igual convención que
 * la versión web (fmt() en pdfReport.ts). */
internal fun fmt(value: Double?, digits: Int = 3): String =
    if (value == null) "—" else String.format(Locale.US, "%.${digits}f", value)

// ---------------------------------------------------------------------------------------------
// Estilos de texto
// ---------------------------------------------------------------------------------------------

/** Paletas de Paint reutilizables por tipo de texto, construidas una sola vez a partir de los 3
 * Typeface recibidos desde :app. monoTypeface SOLO se usa en bodyMono (columnas numéricas/
 * coordenadas) -- nunca como tipografía general, siguiendo la misma convención del resto de la app. */
internal class PdfFonts(regular: Typeface, bold: Typeface, mono: Typeface) {
    private fun make(tf: Typeface, size: Float, color: Int = Color.BLACK, italic: Boolean = false): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = size
            this.color = color
            if (italic) textSkewX = -0.25f // Inter/mono no traen un archivo itálico propio: cursiva falsa vía skew.
        }

    val title = make(bold, 16f)
    val subtitle = make(regular, 12f)
    val sectionHeader = make(bold, 13f)
    val subHeader = make(bold, 11f)
    val body = make(regular, 8f)
    val bodyBold = make(bold, 8f)
    val bodyItalic = make(regular, 8f, italic = true)
    val bodyMono = make(mono, 8f)
    val caption = make(regular, 8f, color = Color.rgb(0x5b, 0x64, 0x72), italic = true)
    val warning = make(bold, 10f, color = Color.rgb(0x8a, 0x53, 0x00))
    val tableHeader = make(bold, 8f)
    val planLabel = make(regular, 7f)

    val hairline: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xdd, 0xdd, 0xdd)
        strokeWidth = 0.5f
    }
}

// ---------------------------------------------------------------------------------------------
// Paginación: cursor Y + página de PdfDocument
// ---------------------------------------------------------------------------------------------

/** Maneja el cursor Y y el ciclo de vida de startPage/finishPage de PdfDocument. Quien dibuja
 * llama ensureSpace(altoDelBloque) antes de cada bloque (encabezado, fila de tabla, imagen, línea
 * de texto); si no entra antes del margen inferior, se cierra la página actual y se abre una nueva. */
internal class PdfCursor(private val document: PdfDocument) {
    var y: Float = MARGIN
    private var pageNumber = 1
    private var page: PdfDocument.Page = newPdfPage()
    val canvas: Canvas get() = page.canvas

    private fun newPdfPage(): PdfDocument.Page {
        val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageNumber).create()
        return document.startPage(info)
    }

    fun newPage() {
        document.finishPage(page)
        pageNumber += 1
        page = newPdfPage()
        y = MARGIN
    }

    /** Devuelve true si tuvo que abrir una página nueva (para que drawTable pueda repetir el
     * encabezado de la tabla en la página siguiente). */
    fun ensureSpace(height: Float): Boolean {
        if (y + height > PAGE_HEIGHT - MARGIN) {
            newPage()
            return true
        }
        return false
    }

    fun finish() {
        document.finishPage(page)
    }
}

// ---------------------------------------------------------------------------------------------
// Texto: línea simple, párrafo con wrap consciente de paginación, viñetas, línea con tramo en negrita
// ---------------------------------------------------------------------------------------------

internal fun buildStaticLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
    val w = width.coerceAtLeast(1)
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .build()
}

internal fun PdfCursor.drawTextLine(text: String, x: Float, paint: TextPaint) {
    ensureSpace(paint.fontSpacing)
    canvas.drawText(sanitizeForPdf(text), x, y - paint.ascent(), paint)
    y += paint.fontSpacing
}

internal fun PdfCursor.drawSectionHeader(text: String, fonts: PdfFonts) {
    y += 10f
    drawTextLine(text, MARGIN, fonts.sectionHeader)
    y += 4f
}

internal fun PdfCursor.drawSubHeader(text: String, fonts: PdfFonts) {
    y += 6f
    drawTextLine(text, MARGIN, fonts.subHeader)
    y += 2f
}

/** Párrafo con wrap de palabras dentro de maxWidth, dibujado línea por línea y con chequeo de
 * paginación POR LÍNEA (no por párrafo entero) -- así un campo de texto libre largo (Observaciones,
 * por ejemplo) puede fluir correctamente entre dos páginas en vez de desbordar el margen inferior. */
internal fun PdfCursor.drawWrappedParagraph(text: String, x: Float, maxWidth: Float, paint: TextPaint) {
    val sanitized = sanitizeForPdf(text)
    if (sanitized.isEmpty()) return
    val layout = buildStaticLayout(sanitized, paint, maxWidth.toInt())
    val lineHeight = paint.fontSpacing
    for (i in 0 until layout.lineCount) {
        val start = layout.getLineStart(i)
        val end = layout.getLineEnd(i)
        val lineText = sanitized.substring(start, end).trimEnd()
        ensureSpace(lineHeight)
        canvas.drawText(lineText, x, y - paint.ascent(), paint)
        y += lineHeight
    }
}

/** Ítem de lista con viñeta "•" y sangría colgante en las líneas de continuación si el texto hace wrap. */
internal fun PdfCursor.drawBulletItem(text: String, paint: TextPaint) {
    val bulletIndent = 14f
    val textX = MARGIN + bulletIndent
    val maxWidth = CONTENT_WIDTH - bulletIndent
    val sanitized = sanitizeForPdf(text)
    val layout = buildStaticLayout(sanitized, paint, maxWidth.toInt())
    val lineHeight = paint.fontSpacing
    for (i in 0 until layout.lineCount) {
        val start = layout.getLineStart(i)
        val end = layout.getLineEnd(i)
        val lineText = sanitized.substring(start, end).trimEnd()
        ensureSpace(lineHeight)
        if (i == 0) canvas.drawText("•", MARGIN, y - paint.ascent(), paint)
        canvas.drawText(lineText, textX, y - paint.ascent(), paint)
        y += lineHeight
    }
    y += 1f
}

/** Un fragmento de texto con su propio Paint, para armar una línea con parte en negrita y el resto
 * en texto normal (p. ej. "**CÓDIGO** — descripción..." del panel fotográfico). */
internal data class RichToken(val text: String, val paint: TextPaint)

/** Dibuja una secuencia de tokens (ya saneados por el llamador) como un párrafo con wrap de
 * palabras, cada token con su propio Paint, avanzando x y haciendo saltos de línea/página según
 * haga falta. Se usa para mezclar negrita + texto normal en una misma línea lógica. */
internal fun PdfCursor.drawRichParagraph(tokens: List<RichToken>, x: Float, maxWidth: Float) {
    if (tokens.isEmpty()) return
    val lineHeight = tokens.maxOf { it.paint.fontSpacing }
    val spaceWidth = tokens.first().paint.measureText(" ")
    ensureSpace(lineHeight)
    var cursorX = x
    var atLineStart = true
    for (token in tokens) {
        val tokenWidth = token.paint.measureText(token.text)
        val needed = (if (atLineStart) 0f else spaceWidth) + tokenWidth
        if (!atLineStart && cursorX + needed > x + maxWidth) {
            y += lineHeight
            ensureSpace(lineHeight)
            cursorX = x
            atLineStart = true
        }
        if (!atLineStart) cursorX += spaceWidth
        canvas.drawText(token.text, cursorX, y - token.paint.ascent(), token.paint)
        cursorX += tokenWidth
        atLineStart = false
    }
    y += lineHeight
}

// ---------------------------------------------------------------------------------------------
// Tabla genérica (clave-valor, resultados, estaciones de poligonal)
// ---------------------------------------------------------------------------------------------

internal data class TableColumn(val header: String, val weight: Float, val mono: Boolean = false)

/** Motor de tabla genérico: columnas proporcionales al ancho de contenido, cada celda puede
 * envolver en varias líneas (la altura de fila es la celda más alta), y si una fila cae justo en
 * el borde de página se repite el encabezado en la página siguiente para no perder el contexto. */
internal fun PdfCursor.drawTable(fonts: PdfFonts, columns: List<TableColumn>, rows: List<List<String>>, showHeader: Boolean) {
    val colWidths = columns.map { it.weight * CONTENT_WIDTH }
    val colX = FloatArray(columns.size)
    var acc = MARGIN
    for (i in columns.indices) {
        colX[i] = acc
        acc += colWidths[i]
    }
    val padX = 4f
    val padY = 3f

    fun drawHeaderRow() {
        val paint = fonts.tableHeader
        ensureSpace(paint.fontSpacing + 2 * padY)
        val top = y
        for (i in columns.indices) {
            canvas.drawText(sanitizeForPdf(columns[i].header), colX[i] + padX, top + padY - paint.ascent(), paint)
        }
        y = top + paint.fontSpacing + 2 * padY
        canvas.drawLine(MARGIN, y, MARGIN + CONTENT_WIDTH, y, fonts.hairline)
    }

    if (showHeader) drawHeaderRow()

    for (row in rows) {
        val cellPaints = columns.map { if (it.mono) fonts.bodyMono else fonts.body }
        val layouts = row.mapIndexed { i, text ->
            val w = (colWidths[i] - 2 * padX).toInt()
            buildStaticLayout(sanitizeForPdf(text), cellPaints[i], w)
        }
        val rowHeight = layouts.indices.maxOf { i -> layouts[i].lineCount * cellPaints[i].fontSpacing } + 2 * padY
        val brokePage = ensureSpace(rowHeight)
        if (brokePage && showHeader) drawHeaderRow()
        val top = y
        for (i in layouts.indices) {
            canvas.save()
            canvas.translate(colX[i] + padX, top + padY)
            layouts[i].draw(canvas)
            canvas.restore()
        }
        y = top + rowHeight
        canvas.drawLine(MARGIN, y, MARGIN + CONTENT_WIDTH, y, fonts.hairline)
    }
}

// ---------------------------------------------------------------------------------------------
// Imágenes (panel fotográfico): decodificación defensiva + dibujo preservando proporción
// ---------------------------------------------------------------------------------------------

private const val IMAGE_DECODE_TARGET_W = 660 // ~3x la caja de 220pt, para que no se vea pixelada al imprimir/zoom.
private const val IMAGE_DECODE_TARGET_H = 480

/** Intenta decodificar una foto de forma defensiva: archivo inexistente, no legible, corrupto o
 * que se queda sin memoria al decodificar nunca tira abajo el informe completo -- simplemente se
 * trata como "sin fotografía" (nunca se inventa/reemplaza por un placeholder). Usa inSampleSize
 * para no cargar fotos de cámara a resolución completa en memoria innecesariamente. */
internal fun decodeBitmapFitted(path: String?): Bitmap? {
    if (path.isNullOrBlank()) return null
    return try {
        val file = File(path)
        if (!file.isFile) return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOpts)
        val w = boundsOpts.outWidth
        val h = boundsOpts.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= IMAGE_DECODE_TARGET_W && h / (sample * 2) >= IMAGE_DECODE_TARGET_H) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, opts)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

/** Dibuja el bitmap encajado dentro de maxW x maxH preservando su proporción original (nunca
 * deforma: la escala es única para ambos ejes, igual que el plano de la sección 3). */
internal fun PdfCursor.drawFittedBitmap(bitmap: Bitmap, maxW: Float, maxH: Float) {
    val scale = min(maxW / bitmap.width, maxH / bitmap.height)
    val w = bitmap.width * scale
    val h = bitmap.height * scale
    ensureSpace(h)
    val dst = RectF(MARGIN, y, MARGIN + w, y + h)
    canvas.drawBitmap(bitmap, null, dst, null)
    y += h + 2f
}
