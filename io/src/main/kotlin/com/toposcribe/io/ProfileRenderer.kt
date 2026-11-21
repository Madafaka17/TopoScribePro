package com.toposcribe.io

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.toposcribe.calc.LevelingProfile
import com.toposcribe.calc.niceScaleDenominator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

data class ProfileScales(val horizontalDenominator: Double, val verticalDenominator: Double) {
    val exaggeration: Double get() = horizontalDenominator / verticalDenominator
    fun text(): String = String.format(Locale.US, "Escala horizontal 1:%.0f · vertical 1:%.0f (exageración vertical ×%.1f, válida impresa en A4)", horizontalDenominator, verticalDenominator, exaggeration)
}

/**
 * Dibuja el perfil longitudinal (distancia acumulada en X, cota en Y) con ejes, cuadrícula, códigos
 * de punto y unidades. Lo usan la pantalla de Altimetría y el PDF, así que ambos son idénticos.
 * Solo dibuja si el perfil es válido; en otro caso no pinta nada (la UI explica el motivo).
 */
object ProfileRenderer {
    const val NOMINAL_PLOT_WIDTH_MM = 150.0

    /** Escalas redondas para un área de dibujo de [plotWidthMm] × [plotHeightMm] milímetros de papel. */
    fun scalesFor(profile: LevelingProfile, plotWidthMm: Double, plotHeightMm: Double): ProfileScales {
        val h = niceScaleDenominator(max(profile.totalLength, 1e-6), plotWidthMm)
        val range = max(profile.maxElevation - profile.minElevation, 0.01)
        val v = niceScaleDenominator(range * 1.15, plotHeightMm)
        return ProfileScales(h, v)
    }

    /** Escalas que se usarán al dibujar en un rectángulo de [rectWidth]×[rectHeight] px (misma geometría que [draw]). */
    fun scalesForRect(profile: LevelingProfile, rectWidth: Float, rectHeight: Float, u: Float, plotWidthMm: Double): ProfileScales? {
        if (!profile.valid || profile.points.size < 2) return null
        val plotW = rectWidth - 60 * u
        val plotH = rectHeight - 38 * u
        if (plotW <= 10f || plotH <= 10f) return null
        return scalesFor(profile, plotWidthMm, plotWidthMm * plotH / plotW)
    }

    /**
     * @param rect       zona total disponible (incluye márgenes de ejes)
     * @param plotWidthMm ancho en milímetros de papel que tendrá el área de trazado (para la escala)
     * @return las escalas empleadas, o null si el perfil no es válido (no se dibuja nada)
     */
    fun draw(canvas: Canvas, profile: LevelingProfile, units: String, rect: RectF, style: PlanStyle, plotWidthMm: Double): ProfileScales? {
        if (!profile.valid || profile.points.size < 2) return null
        val u = style.unit
        val left = rect.left + 46 * u; val right = rect.right - 14 * u
        val top = rect.top + 8 * u; val bottom = rect.bottom - 30 * u
        val plotW = right - left; val plotH = bottom - top
        if (plotW <= 10f || plotH <= 10f) return null
        val plotHeightMm = plotWidthMm * plotH / plotW
        val scales = scalesFor(profile, plotWidthMm, plotHeightMm)

        val spanX = scales.horizontalDenominator * plotWidthMm / 1000.0
        val spanY = scales.verticalDenominator * plotHeightMm / 1000.0
        val xMin = 0.0
        val yRange = profile.maxElevation - profile.minElevation
        val yMin = profile.minElevation - (spanY - yRange) / 2.0
        fun px(d: Double) = (left + (d - xMin) / spanX * plotW).toFloat()
        fun py(z: Double) = (bottom - (z - yMin) / spanY * plotH).toFloat()

        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xDD, 0xE1, 0xE8); strokeWidth = 0.5f * u; this.style = Paint.Style.STROKE }
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1F, 0x24, 0x30); strokeWidth = 1f * u; this.style = Paint.Style.STROKE }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1F, 0x24, 0x30); textSize = 7f * u; style.typeface?.let { typeface = it } }
        val boldTxt = Paint(txt).apply { typeface = Typeface.create(style.typeface ?: Typeface.DEFAULT, Typeface.BOLD) }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x00, 0x63, 0x98); strokeWidth = 1.6f * u; this.style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xA3, 0x39, 0x00); this.style = Paint.Style.FILL }

        // Cuadrícula y marcas
        val stepX = PlanRenderer.niceStep(spanX / 8.0)
        var gx = ceil(xMin / stepX) * stepX
        while (gx <= xMin + spanX + 1e-9) {
            canvas.drawLine(px(gx), top, px(gx), bottom, grid)
            val label = fmtTick(gx, stepX)
            canvas.drawText(label, px(gx) - txt.measureText(label) / 2, bottom + 10 * u, txt)
            gx += stepX
        }
        val stepY = PlanRenderer.niceStep(spanY / 5.0)
        var gy = ceil(yMin / stepY) * stepY
        while (gy <= yMin + spanY + 1e-9) {
            canvas.drawLine(left, py(gy), right, py(gy), grid)
            val label = fmtTick(gy, stepY)
            canvas.drawText(label, left - 4 * u - txt.measureText(label), py(gy) + 2.5f * u, txt)
            gy += stepY
        }
        canvas.drawRect(left, top, right, bottom, axis)
        canvas.drawText("Distancia acumulada ($units)", left + plotW / 2 - boldTxt.measureText("Distancia acumulada ($units)") / 2, bottom + 23 * u, boldTxt)
        canvas.save()
        canvas.rotate(-90f, rect.left + 8 * u, top + plotH / 2)
        canvas.drawText("Cota ($units)", rect.left + 8 * u - boldTxt.measureText("Cota ($units)") / 2, top + plotH / 2, boldTxt)
        canvas.restore()

        // Perfil
        val path = Path()
        profile.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.cumulativeDistance), py(p.elevation)) else path.lineTo(px(p.cumulativeDistance), py(p.elevation)) }
        canvas.drawPath(path, line)
        val showElev = profile.points.size <= 14
        profile.points.forEachIndexed { i, p ->
            val x = px(p.cumulativeDistance); val y = py(p.elevation)
            canvas.drawCircle(x, y, 2.6f * u, dot)
            val above = i % 2 == 0
            val code = p.code
            val tx = (x - txt.measureText(code) / 2).coerceIn(left, right - txt.measureText(code))
            canvas.drawText(code, tx, if (above) y - 5 * u else y + 11 * u, boldTxt)
            if (showElev) {
                val z = String.format(Locale.US, "%.3f", p.elevation)
                val zx = (x - txt.measureText(z) / 2).coerceIn(left, right - txt.measureText(z))
                canvas.drawText(z, zx, if (above) y - 12.5f * u else y + 18.5f * u, txt)
            }
        }
        return scales
    }

    private fun fmtTick(v: Double, step: Double): String {
        val decimals = when { step >= 1 -> 0; step >= 0.1 -> 1; step >= 0.01 -> 2; else -> 3 }
        return String.format(Locale.US, "%.${decimals}f", if (abs(v) < 1e-9) 0.0 else v)
    }
}
