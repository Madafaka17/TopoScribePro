package com.toposcribe.calc

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

data class ElevatedPoint(val id: Long, val east: Double, val north: Double, val elevation: Double)
data class EdgePoint(val east: Double, val north: Double)

/**
 * Interpola la posición E/N donde un nivel cruza la arista pA-pB, con t=(level-zA)/(zB-zA).
 * Función pura, testeada exactamente -- misma firma/criterio que interpolateEdgeAtLevel en el
 * server Node. Devuelve null si el nivel no cruza esta arista (zA==zB o level fuera de rango).
 */
fun interpolateEdgeAtLevel(pA: ElevatedPoint, pB: ElevatedPoint, level: Double): EdgePoint? {
    if (pA.elevation == pB.elevation) return null
    val lo = minOf(pA.elevation, pB.elevation)
    val hi = maxOf(pA.elevation, pB.elevation)
    if (level < lo || level > hi) return null
    val t = (level - pA.elevation) / (pB.elevation - pA.elevation)
    return EdgePoint(
        east = pA.east + t * (pB.east - pA.east),
        north = pA.north + t * (pB.north - pA.north),
    )
}

data class ContourSegment(val level: Double, val a: EdgePoint, val b: EdgePoint)

data class ContourResult(
    val applicable: Boolean,
    val reason: String?,
    val equidistance: Double,
    val method: String,
    val levels: List<Double>,
    val segments: List<ContourSegment>,
)

private const val MIN_POINTS = 4

private fun areCollinear(points: List<ElevatedPoint>): Boolean {
    if (points.size < 3) return true
    val (p0, p1) = points
    val dx = p1.east - p0.east
    val dy = p1.north - p0.north
    if (dx == 0.0 && dy == 0.0) return areCollinear(points.drop(1))
    return points.drop(2).all { p ->
        val cross = dx * (p.north - p0.north) - dy * (p.east - p0.east)
        abs(cross) < 1e-6
    }
}

/**
 * Curvas de nivel: Delaunay sobre puntos con E/N/cota; por cada arista de triángulo cruzada por
 * un nivel, interpola el punto con interpolateEdgeAtLevel. Guarda: se requieren al menos 4 puntos
 * con coordenadas y cota, no colineales.
 */
fun computeContours(points: List<ElevatedPoint>, equidistance: Double): ContourResult {
    if (points.size < MIN_POINTS) {
        return ContourResult(false, "Se requieren al menos 4 puntos con coordenadas y cota no colineales.", equidistance, "Delaunay + interpolación lineal", emptyList(), emptyList())
    }
    if (areCollinear(points)) {
        return ContourResult(false, "Los puntos disponibles están alineados: no se puede triangular una superficie.", equidistance, "Delaunay + interpolación lineal", emptyList(), emptyList())
    }

    val byId = points.associateBy { it.id }
    val triPoints = points.map { TriPoint(it.id, it.east, it.north) }
    val triangles = Delaunay.triangulate(triPoints)
    if (triangles.isEmpty()) {
        return ContourResult(false, "No se pudo triangular la superficie con los puntos disponibles.", equidistance, "Delaunay + interpolación lineal", emptyList(), emptyList())
    }

    val minElev = points.minOf { it.elevation }
    val maxElev = points.maxOf { it.elevation }
    val firstLevel = ceil(minElev / equidistance) * equidistance
    val levels = generateSequence(firstLevel) { it + equidistance }
        .takeWhile { it <= maxElev }
        .toList()

    val segments = mutableListOf<ContourSegment>()
    for (tri in triangles) {
        val a = byId.getValue(tri.a.id)
        val b = byId.getValue(tri.b.id)
        val c = byId.getValue(tri.c.id)
        for (level in levels) {
            val crossings = listOfNotNull(
                interpolateEdgeAtLevel(a, b, level),
                interpolateEdgeAtLevel(b, c, level),
                interpolateEdgeAtLevel(c, a, level),
            )
            if (crossings.size == 2) {
                segments.add(ContourSegment(level, crossings[0], crossings[1]))
            }
        }
    }

    return ContourResult(true, null, equidistance, "Delaunay + interpolación lineal por arista", levels, segments)
}
