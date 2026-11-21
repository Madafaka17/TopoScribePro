package com.toposcribe.calc

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

data class ElevatedPoint(val id: Long, val east: Double, val north: Double, val elevation: Double)
data class EdgePoint(val east: Double, val north: Double)

/**
 * Interpola la posición E/N donde un nivel cruza la arista pA-pB, con t=(nivel−zA)/(zB−zA).
 * Devuelve null si el nivel no cruza la arista (zA==zB o nivel fuera de rango).
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

/** Curva de nivel ya unida: secuencia de vértices a una cota. */
data class ContourPolyline(val level: Double, val points: List<EdgePoint>, val closed: Boolean)

/** Triángulo de la red (TIN) con los identificadores de punto. */
data class TinTriangle(val a: Long, val b: Long, val c: Long)

const val CONTOUR_METHOD_TEXT =
    "Triangulación de Delaunay (Bowyer-Watson) e interpolación lineal sobre las aristas de cada triángulo"

data class ContourResult(
    val applicable: Boolean,
    val reason: String?,
    val equidistance: Double,
    val method: String,
    val levels: List<Double>,
    val segments: List<ContourSegment>,
    val polylines: List<ContourPolyline> = emptyList(),
    val triangles: List<TinTriangle> = emptyList(),
    val discardedTriangles: Int = 0,
)

/** Origen de la cota de un punto, para mostrarlo y decidir si sirve para curvas de nivel. */
enum class ElevationSource(val labelEs: String, val usableForContours: Boolean) {
    NIVEL_INGENIERO("Nivel de ingeniero", true),
    CONTROL_CONOCIDO("Control conocido", true),
    OTRO("Otro cálculo (no usado)", false),
    NINGUNA("Sin cota", false),
}

data class ContourCandidate(
    val id: Long,
    val code: String,
    val east: Double?,
    val north: Double?,
    val elevation: Double?,
    val elevationSource: ElevationSource,
)

data class ExcludedPoint(val code: String, val reason: String)

data class ContourEligibility(
    val eligible: Boolean,
    val usable: List<ElevatedPoint>,
    val excluded: List<ExcludedPoint>,
    /** Qué falta exactamente (vacío si es elegible). */
    val issues: List<String>,
)

const val MIN_CONTOUR_POINTS = 4

private fun areCollinear(points: List<ElevatedPoint>): Boolean {
    if (points.size < 3) return true
    val p0 = points[0]
    val p1 = points.firstOrNull { it.east != p0.east || it.north != p0.north } ?: return true
    val dx = p1.east - p0.east
    val dy = p1.north - p0.north
    val len = hypot(dx, dy)
    return points.all { p ->
        val cross = dx * (p.north - p0.north) - dy * (p.east - p0.east)
        abs(cross) / len < 1e-6
    }
}

/**
 * Decide si hay datos suficientes para triangular. Un punto solo cuenta si tiene Este, Norte y cota
 * válidos y la cota procede del nivel de ingeniero o de un control conocido. Nunca se asigna una
 * cota a un punto que no la tiene.
 */
fun evaluateContourEligibility(candidates: List<ContourCandidate>): ContourEligibility {
    val usable = mutableListOf<ElevatedPoint>()
    val excluded = mutableListOf<ExcludedPoint>()
    val seenXY = HashMap<Pair<Long, Long>, String>()
    for (c in candidates) {
        val missing = mutableListOf<String>()
        if (c.east == null || c.east.isNaN()) missing += "Este"
        if (c.north == null || c.north.isNaN()) missing += "Norte"
        if (c.elevation == null || c.elevation.isNaN()) missing += "cota"
        if (missing.isNotEmpty()) {
            excluded += ExcludedPoint(c.code, "sin " + missing.joinToString(", "))
            continue
        }
        if (!c.elevationSource.usableForContours) {
            excluded += ExcludedPoint(c.code, "su cota no procede del nivel de ingeniero ni de un control conocido")
            continue
        }
        val key = Math.round(c.east!! * 1000) to Math.round(c.north!! * 1000)
        val dup = seenXY[key]
        if (dup != null) {
            excluded += ExcludedPoint(c.code, "misma posición E/N que $dup (se usa solo el primero)")
            continue
        }
        seenXY[key] = c.code
        usable += ElevatedPoint(c.id, c.east, c.north, c.elevation!!)
    }

    val issues = mutableListOf<String>()
    if (candidates.isEmpty()) issues += "El proyecto no tiene puntos."
    if (usable.size < MIN_CONTOUR_POINTS) {
        issues += "Hay ${usable.size} punto(s) con Este, Norte y cota válidos; se necesitan al menos $MIN_CONTOUR_POINTS."
    } else if (areCollinear(usable)) {
        issues += "Los puntos con datos completos están alineados: no se puede formar ningún triángulo."
    } else if (usable.map { it.elevation }.distinct().size < 2) {
        issues += "Todos los puntos tienen la misma cota: no hay relieve que representar."
    }
    if (excluded.isNotEmpty() && usable.size < MIN_CONTOUR_POINTS) {
        issues += "Puntos sin usar: " + excluded.joinToString("; ") { "${it.code} (${it.reason})" } + "."
    }
    return ContourEligibility(issues.isEmpty(), usable, excluded, issues)
}

private fun minInteriorAngleDeg(a: ElevatedPoint, b: ElevatedPoint, c: ElevatedPoint): Double {
    fun ang(p: ElevatedPoint, q: ElevatedPoint, r: ElevatedPoint): Double {
        val v1x = q.east - p.east; val v1y = q.north - p.north
        val v2x = r.east - p.east; val v2y = r.north - p.north
        val d = hypot(v1x, v1y) * hypot(v2x, v2y)
        if (d == 0.0) return 0.0
        return Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / d).coerceIn(-1.0, 1.0)))
    }
    return minOf(ang(a, b, c), ang(b, c, a), ang(c, a, b))
}

/** Ángulo interior mínimo bajo el cual un triángulo se descarta por degenerado. */
const val MIN_USEFUL_TRIANGLE_ANGLE_DEG = 1.0

/**
 * Curvas de nivel: Delaunay sobre puntos con E/N/cota; por cada arista de triángulo cruzada por un
 * nivel se interpola el punto de paso. Guarda: al menos 4 puntos no colineales con relieve.
 * Los triángulos degenerados (ángulo < 1°) se descartan y se cuentan.
 */
fun computeContours(points: List<ElevatedPoint>, equidistance: Double): ContourResult {
    fun fail(reason: String) = ContourResult(false, reason, equidistance, CONTOUR_METHOD_TEXT, emptyList(), emptyList())
    if (equidistance <= 0.0 || equidistance.isNaN()) return fail("La equidistancia debe ser un número mayor que 0.")
    if (points.size < MIN_CONTOUR_POINTS) return fail("Se requieren al menos $MIN_CONTOUR_POINTS puntos con coordenadas y cota no colineales.")
    if (areCollinear(points)) return fail("Los puntos disponibles están alineados: no se puede triangular una superficie.")

    val byId = points.associateBy { it.id }
    val allTriangles = Delaunay.triangulate(points.map { TriPoint(it.id, it.east, it.north) })
    if (allTriangles.isEmpty()) return fail("No se pudo triangular la superficie con los puntos disponibles.")
    val useful = allTriangles.filter {
        minInteriorAngleDeg(byId.getValue(it.a.id), byId.getValue(it.b.id), byId.getValue(it.c.id)) >= MIN_USEFUL_TRIANGLE_ANGLE_DEG
    }
    if (useful.isEmpty()) return fail("Los triángulos formados son degenerados (casi alineados): la distribución de puntos no permite una superficie útil.")

    val minElev = points.minOf { it.elevation }
    val maxElev = points.maxOf { it.elevation }
    if (minElev == maxElev) return fail("Todos los puntos tienen la misma cota: no hay curvas que trazar.")
    val firstLevel = ceil(minElev / equidistance - 1e-9) * equidistance
    val levels = generateSequence(firstLevel) { it + equidistance }
        .takeWhile { it <= maxElev + 1e-9 }
        .map { Math.round(it * 1e6) / 1e6 }
        .toList()
    if (levels.isEmpty()) {
        return fail(
            String.format(Locale.US, "Ningún múltiplo de la equidistancia %.3f cae dentro del rango de cotas (%.3f a %.3f): use una equidistancia menor.", equidistance, minElev, maxElev),
        )
    }

    val segments = mutableListOf<ContourSegment>()
    for (tri in useful) {
        val a = byId.getValue(tri.a.id)
        val b = byId.getValue(tri.b.id)
        val c = byId.getValue(tri.c.id)
        for (level in levels) {
            val crossings = listOfNotNull(
                interpolateEdgeAtLevel(a, b, level),
                interpolateEdgeAtLevel(b, c, level),
                interpolateEdgeAtLevel(c, a, level),
            ).let { raw -> raw.filterIndexed { i, p -> raw.indexOfFirst { q -> sameXY(p, q) } == i } } // vértice exacto: un solo cruce
            if (crossings.size == 2) segments.add(ContourSegment(level, crossings[0], crossings[1]))
        }
    }

    // Una arista plana a nivel exacto la comparten dos triángulos: se deja un solo segmento.
    fun key(p: EdgePoint) = Math.round(p.east * 1e6) to Math.round(p.north * 1e6)
    val unique = segments.distinctBy { s ->
        val ka = key(s.a); val kb = key(s.b)
        val (k1, k2) = if (ka.first < kb.first || (ka.first == kb.first && ka.second <= kb.second)) ka to kb else kb to ka
        Triple(s.level, k1, k2)
    }

    return ContourResult(
        applicable = true,
        reason = null,
        equidistance = equidistance,
        method = CONTOUR_METHOD_TEXT,
        levels = levels,
        segments = unique,
        polylines = chainContourSegments(unique),
        triangles = useful.map { TinTriangle(it.a.id, it.b.id, it.c.id) },
        discardedTriangles = allTriangles.size - useful.size,
    )
}

private fun sameXY(a: EdgePoint, b: EdgePoint, tol: Double = 1e-7) =
    abs(a.east - b.east) <= tol && abs(a.north - b.north) <= tol

/** Une los segmentos de cada nivel en polilíneas continuas (extremos que coinciden). */
fun chainContourSegments(segments: List<ContourSegment>): List<ContourPolyline> {
    val result = mutableListOf<ContourPolyline>()
    for ((level, group) in segments.groupBy { it.level }) {
        val remaining = group.filter { !sameXY(it.a, it.b) }.toMutableList()
        while (remaining.isNotEmpty()) {
            val first = remaining.removeAt(0)
            val chain = ArrayDeque<EdgePoint>()
            chain.addLast(first.a); chain.addLast(first.b)
            var extended = true
            while (extended) {
                extended = false
                val iter = remaining.iterator()
                while (iter.hasNext()) {
                    val s = iter.next()
                    when {
                        sameXY(s.a, chain.last()) -> { chain.addLast(s.b); iter.remove(); extended = true }
                        sameXY(s.b, chain.last()) -> { chain.addLast(s.a); iter.remove(); extended = true }
                        sameXY(s.b, chain.first()) -> { chain.addFirst(s.a); iter.remove(); extended = true }
                        sameXY(s.a, chain.first()) -> { chain.addFirst(s.b); iter.remove(); extended = true }
                    }
                }
            }
            val closed = chain.size > 3 && sameXY(chain.first(), chain.last())
            val pts = if (closed) chain.toList().dropLast(1) else chain.toList()
            result.add(ContourPolyline(level, pts, closed))
        }
    }
    return result.sortedBy { it.level }
}

/** Longitud total de una polilínea (útil para pruebas y etiquetas). */
fun polylineLength(p: ContourPolyline): Double {
    var total = 0.0
    for (i in 0 until p.points.size - 1) total += sqrt(
        (p.points[i + 1].east - p.points[i].east).let { it * it } + (p.points[i + 1].north - p.points[i].north).let { it * it },
    )
    return total
}
