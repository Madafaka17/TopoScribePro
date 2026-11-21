package com.toposcribe.io

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.toposcribe.calc.MASTER_CONTOUR_EVERY
import com.toposcribe.calc.PlanBounds
import com.toposcribe.calc.PlanLayer
import com.toposcribe.calc.PlanModel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Tamaños relativos: en el PDF 1 unidad = 1 pt; en pantalla se usa la densidad del dispositivo. */
class PlanStyle(val unit: Float = 1f, val typeface: Typeface? = null)

/** Transformación mundo (Este, Norte) → lienzo. Una única escala para ambos ejes (nunca deforma). */
data class PlanViewport(val scale: Float, val minE: Double, val minN: Double, val offX: Float, val bottomY: Float) {
    fun x(e: Double): Float = offX + ((e - minE) * scale).toFloat()
    fun y(n: Double): Float = bottomY - ((n - minN) * scale).toFloat()
    fun worldE(px: Float): Double = (px - offX) / scale + minE
    fun worldN(py: Float): Double = (bottomY - py) / scale + minN

    /** Aplica zoom y desplazamiento de pantalla sobre este encuadre. */
    fun zoomed(zoom: Float, panX: Float, panY: Float): PlanViewport =
        PlanViewport(scale * zoom, minE, minN, offX * zoom + panX, bottomY * zoom + panY)
}

object PlanRenderer {
    private val COLOR_POINT = Color.rgb(0x1F, 0x24, 0x30)
    private val COLOR_STATION = Color.rgb(0xC6, 0x28, 0x28)
    private val COLOR_RADIATION = Color.rgb(0x0E, 0x74, 0x90)
    private val COLOR_LINE = Color.rgb(0x1D, 0x4E, 0xD8)
    private val COLOR_TIN = Color.rgb(0xB8, 0xBE, 0xC9)
    private val COLOR_CONTOUR = Color.rgb(0x9A, 0x6B, 0x3A)
    private val COLOR_MASTER = Color.rgb(0x6E, 0x42, 0x14)
    private val COLOR_COTA = Color.rgb(0x15, 0x80, 0x3D)

    /** Encuadre que hace caber todo el plano en [rect] con margen, con una sola escala y centrado. */
    fun fit(bounds: PlanBounds, rect: RectF, margin: Float): PlanViewport {
        val w = max(bounds.width, 1e-6)
        val h = max(bounds.height, 1e-6)
        // Con un único punto (o puntos alineados) el eje sin extensión no limita la escala.
        val availW = rect.width() - 2 * margin
        val availH = rect.height() - 2 * margin
        val candidates = mutableListOf<Float>()
        if (bounds.width > 1e-9) candidates += (availW / w).toFloat()
        if (bounds.height > 1e-9) candidates += (availH / h).toFloat()
        val scale = candidates.minOrNull() ?: 1f
        val usedW = (bounds.width * scale).toFloat()
        val usedH = (bounds.height * scale).toFloat()
        val offX = rect.left + margin + (availW - usedW) / 2f
        val bottom = rect.bottom - margin - (availH - usedH) / 2f
        return PlanViewport(scale, bounds.minE, bounds.minN, offX, bottom)
    }

    /** Denominador de la escala numérica 1:N para un encuadre impreso donde 1 unidad de mundo = [ptPerUnit] pt. */
    fun scaleDenominator(ptPerUnit: Float, metersPerUnit: Double = 1.0): Double {
        if (ptPerUnit <= 0f) return 0.0
        val paperMmPerUnit = ptPerUnit * 25.4 / 72.0
        return 1000.0 * metersPerUnit / paperMmPerUnit
    }

    fun draw(canvas: Canvas, model: PlanModel, layers: Set<PlanLayer>, vp: PlanViewport, clip: RectF, style: PlanStyle) {
        val u = style.unit
        val active = layers intersect model.availableLayers()
        canvas.save()
        canvas.clipRect(clip)
        fun paint(color: Int, width: Float, fill: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = width * u; this.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun text(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size * u; style.typeface?.let { typeface = it }
        }
        val byId = model.pointById

        if (PlanLayer.TRIANGULACION in active) {
            val p = paint(COLOR_TIN, 0.5f)
            val drawn = HashSet<Pair<Long, Long>>()
            for (t in model.contours!!.triangles) for ((a, b) in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                if (!drawn.add(if (a < b) a to b else b to a)) continue
                val pa = byId[a] ?: continue
                val pb = byId[b] ?: continue
                canvas.drawLine(vp.x(pa.east), vp.y(pa.north), vp.x(pb.east), vp.y(pb.north), p)
            }
        }
        if (PlanLayer.CURVAS in active) {
            val c = model.contours!!
            val levels = c.levels.sorted()
            val minor = paint(COLOR_CONTOUR, 0.6f)
            val major = paint(COLOR_MASTER, 1.3f)
            val label = text(COLOR_MASTER, 6.5f)
            for (poly in c.polylines) {
                val idx = levels.indexOfFirst { abs(it - poly.level) < 1e-9 }
                val master = idx >= 0 && idx % MASTER_CONTOUR_EVERY == 0
                val path = Path()
                poly.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(vp.x(p.east), vp.y(p.north)) else path.lineTo(vp.x(p.east), vp.y(p.north)) }
                if (poly.closed) path.close()
                canvas.drawPath(path, if (master) major else minor)
                if (master && poly.points.size >= 2) {
                    val mid = poly.points[poly.points.size / 2]
                    canvas.drawText(String.format(java.util.Locale.US, "%.2f", poly.level).trimEnd('0').trimEnd('.'), vp.x(mid.east) + 2 * u, vp.y(mid.north) - 2 * u, label)
                }
            }
        }
        if (PlanLayer.LINEAS in active) {
            val p = paint(COLOR_LINE, 1.1f)
            for (l in model.polylines) {
                val v = model.polylinePoints(l)
                if (v.size < 2) continue
                val path = Path()
                v.forEachIndexed { i, pt -> if (i == 0) path.moveTo(vp.x(pt.east), vp.y(pt.north)) else path.lineTo(vp.x(pt.east), vp.y(pt.north)) }
                if (l.closed && v.size > 2) path.close()
                canvas.drawPath(path, p)
            }
        }
        if (PlanLayer.RADIACIONES in active) {
            val p = paint(COLOR_RADIATION, 0.7f).apply { pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * u, 2 * u), 0f) }
            for (r in model.radiations) {
                val a = byId[r.stationPointId] ?: continue
                val b = byId[r.targetPointId] ?: continue
                canvas.drawLine(vp.x(a.east), vp.y(a.north), vp.x(b.east), vp.y(b.north), p)
            }
        }
        if (PlanLayer.PUNTOS in active) {
            val dot = paint(COLOR_POINT, 1f, fill = true)
            for (pt in model.points) {
                if (pt.isStation && PlanLayer.ESTACION in active) continue
                canvas.drawCircle(vp.x(pt.east), vp.y(pt.north), 2.4f * u, dot)
            }
        }
        if (PlanLayer.ESTACION in active) {
            val tri = paint(COLOR_STATION, 1.2f, fill = true)
            for (pt in model.points.filter { it.isStation }) {
                val cx = vp.x(pt.east); val cy = vp.y(pt.north); val r = 5f * u
                val path = Path().apply { moveTo(cx, cy - r); lineTo(cx - r * 0.87f, cy + r * 0.5f); lineTo(cx + r * 0.87f, cy + r * 0.5f); close() }
                canvas.drawPath(path, tri)
            }
        }
        if (PlanLayer.CODIGOS in active) {
            val t = text(COLOR_POINT, 7f)
            for (pt in model.points) canvas.drawText(pt.code, vp.x(pt.east) + 4 * u, vp.y(pt.north) - 3 * u, t)
        }
        if (PlanLayer.COTAS in active) {
            val t = text(COLOR_COTA, 6f)
            for (pt in model.points) {
                val z = pt.elevation ?: continue
                canvas.drawText(String.format(java.util.Locale.US, "%.3f", z), vp.x(pt.east) + 4 * u, vp.y(pt.north) + 8 * u, t)
            }
        }
        canvas.restore()
    }

    /** Norte, escala gráfica, leyenda y sistema de coordenadas: se dibujan fijos sobre [rect]. */
    fun drawFurniture(canvas: Canvas, model: PlanModel, layers: Set<PlanLayer>, vp: PlanViewport, rect: RectF, style: PlanStyle, numericScale: Double? = null) {
        val u = style.unit
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_POINT; strokeWidth = 1.2f * u; this.style = Paint.Style.STROKE }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_POINT; this.style = Paint.Style.FILL }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_POINT; textSize = 7.5f * u; style.typeface?.let { typeface = it } }
        val bold = Paint(txt).apply { typeface = Typeface.create(style.typeface ?: Typeface.DEFAULT, Typeface.BOLD) }

        // Flecha norte (arriba a la derecha)
        val nx = rect.right - 22 * u; val ny = rect.top + 34 * u
        canvas.drawLine(nx, ny + 18 * u, nx, ny - 14 * u, ink)
        val head = Path().apply { moveTo(nx, ny - 18 * u); lineTo(nx - 5 * u, ny - 6 * u); lineTo(nx + 5 * u, ny - 6 * u); close() }
        canvas.drawPath(head, fill)
        canvas.drawText("N", nx - 3 * u, ny - 22 * u, bold)

        // Escala gráfica (abajo a la derecha)
        val targetPx = 70f * u
        val worldLen = niceLength((targetPx / vp.scale).toDouble())
        val barPx = (worldLen * vp.scale).toFloat()
        val bx2 = rect.right - 12 * u; val bx1 = bx2 - barPx; val by = rect.bottom - 18 * u
        canvas.drawLine(bx1, by, bx2, by, ink)
        canvas.drawLine(bx1, by - 3 * u, bx1, by + 3 * u, ink); canvas.drawLine(bx2, by - 3 * u, bx2, by + 3 * u, ink)
        canvas.drawLine((bx1 + bx2) / 2, by - 2 * u, (bx1 + bx2) / 2, by + 2 * u, ink)
        val unitLabel = model.unitsDistance
        val lenLabel = (if (worldLen >= 1) String.format(java.util.Locale.US, "%.0f", worldLen) else String.format(java.util.Locale.US, "%.2f", worldLen)) + " $unitLabel"
        canvas.drawText("0", bx1 - 2 * u, by - 5 * u, txt)
        canvas.drawText(lenLabel, bx2 - txt.measureText(lenLabel) + 2 * u, by - 5 * u, txt)
        if (numericScale != null && numericScale > 0) {
            val s = "Escala 1:" + String.format(java.util.Locale.US, "%.0f", numericScale)
            canvas.drawText(s, bx2 - bold.measureText(s), by + 11 * u, bold)
        }

        // Leyenda (abajo a la izquierda) y sistema de coordenadas
        val entries = buildList {
            val active = layers intersect model.availableLayers()
            if (PlanLayer.PUNTOS in active) add("●" to "Puntos")
            if (PlanLayer.ESTACION in active) add("▲" to "Estación")
            if (PlanLayer.RADIACIONES in active) add("- -" to "Radiaciones")
            if (PlanLayer.LINEAS in active) add("—" to "Líneas del usuario")
            if (PlanLayer.TRIANGULACION in active) add("—" to "Triangulación (TIN)")
            if (PlanLayer.CURVAS in active) add("—" to "Curvas de nivel (maestras cada $MASTER_CONTOUR_EVERY)")
        }
        val lineH = 9.5f * u
        var y = rect.bottom - 8 * u - lineH * entries.size
        val colors = mapOf("Puntos" to COLOR_POINT, "Estación" to COLOR_STATION, "Radiaciones" to COLOR_RADIATION, "Líneas del usuario" to COLOR_LINE, "Triangulación (TIN)" to COLOR_TIN)
        for ((sym, label) in entries) {
            val c = colors[label] ?: COLOR_CONTOUR
            canvas.drawText(sym, rect.left + 8 * u, y + 7 * u, Paint(txt).apply { color = c })
            canvas.drawText(label, rect.left + 24 * u, y + 7 * u, txt)
            y += lineH
        }
        val sys = "Sistema: ${model.coordinateSystem} · Unidades: ${model.unitsDistance}"
        canvas.drawText(sys, rect.left + 8 * u, rect.top + 12 * u, txt)
    }

    /** Longitud «redonda» (1, 2, 5 × 10^n) menor o igual a [raw]. */
    fun niceLength(raw: Double): Double {
        if (raw <= 0 || raw.isNaN() || raw.isInfinite()) return 1.0
        val p = 10.0.pow(floor(log10(raw)))
        val f = raw / p
        return p * when { f >= 5 -> 5.0; f >= 2 -> 2.0; else -> 1.0 }
    }

    /** Paso redondo para cuadrículas. */
    fun niceStep(raw: Double): Double {
        if (raw <= 0.0 || raw.isNaN() || raw.isInfinite()) return 1.0
        val p = 10.0.pow(floor(log10(raw)))
        val f = raw / p
        return p * when { f < 1.5 -> 1.0; f < 3.5 -> 2.0; f < 7.5 -> 5.0; else -> 10.0 }
    }

    /** Punto del plano más cercano a un toque (en px), o null si ninguno está a menos de [maxPx]. */
    fun nearestPoint(model: PlanModel, vp: PlanViewport, px: Float, py: Float, maxPx: Float): com.toposcribe.calc.PlanPoint? {
        var best: com.toposcribe.calc.PlanPoint? = null
        var bestD = Float.MAX_VALUE
        for (p in model.points) {
            val dx = vp.x(p.east) - px; val dy = vp.y(p.north) - py
            val d = sqrt(dx * dx + dy * dy)
            if (d < bestD) { bestD = d; best = p }
        }
        return if (bestD <= maxPx) best else null
    }
}
