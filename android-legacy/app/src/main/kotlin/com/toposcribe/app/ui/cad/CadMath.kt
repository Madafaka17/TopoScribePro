package com.toposcribe.app.ui.cad

import com.toposcribe.calc.PlanPoint

data class UniformScale(val scale: Float, val minEast: Double, val minNorth: Double, val offsetXPx: Float, val offsetYPx: Float)

/**
 * Una ÚNICA escala uniforme para ambos ejes desde el bounding box de los puntos (nunca escalas
 * X/Y independientes -- deformaría ángulos/formas). Mismo criterio que makeLinearScale de la app
 * web y que CadMath del plan.
 */
fun computeUniformScale(points: List<PlanPoint>, canvasWidthPx: Float, canvasHeightPx: Float, marginPx: Float = 32f): UniformScale {
    if (points.isEmpty()) return UniformScale(1f, 0.0, 0.0, marginPx, marginPx)

    var minE = Double.POSITIVE_INFINITY
    var maxE = Double.NEGATIVE_INFINITY
    var minN = Double.POSITIVE_INFINITY
    var maxN = Double.NEGATIVE_INFINITY
    for (p in points) {
        if (p.east < minE) minE = p.east
        if (p.east > maxE) maxE = p.east
        if (p.north < minN) minN = p.north
        if (p.north > maxN) maxN = p.north
    }
    if (minE == maxE) { minE -= 1.0; maxE += 1.0 }
    if (minN == maxN) { minN -= 1.0; maxN += 1.0 }

    val availW = canvasWidthPx - 2 * marginPx
    val availH = canvasHeightPx - 2 * marginPx
    val scale = minOf(availW / (maxE - minE).toFloat(), availH / (maxN - minN).toFloat())

    val usedW = (maxE - minE).toFloat() * scale
    val usedH = (maxN - minN).toFloat() * scale
    val offsetX = marginPx + (availW - usedW) / 2f
    val offsetY = marginPx + (availH - usedH) / 2f

    return UniformScale(scale, minE, minN, offsetX, offsetY)
}

/** Mundo (Este/Norte) -> pantalla (px). Y de pantalla crece hacia abajo, Norte hacia arriba -- se invierte. */
fun UniformScale.toScreenX(east: Double): Float = offsetXPx + ((east - minEast) * scale).toFloat()
fun UniformScale.toScreenY(north: Double, canvasHeightPx: Float): Float = canvasHeightPx - offsetYPx - ((north - minNorth) * scale).toFloat()
