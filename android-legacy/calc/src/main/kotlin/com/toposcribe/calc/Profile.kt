package com.toposcribe.calc

import kotlin.math.sqrt

data class AxisPoint(val pointId: Long, val east: Double, val north: Double)
data class ProfileCandidate(val pointId: Long, val east: Double, val north: Double, val elevation: Double?)

data class AxisSegment(val a: AxisPoint, val b: AxisPoint, val startChainage: Double, val length: Double)

/** Construye los segmentos del eje con su progresiva acumulada de inicio. */
fun buildAxisSegments(axisPoints: List<AxisPoint>): List<AxisSegment> {
    val segments = mutableListOf<AxisSegment>()
    var chainage = 0.0
    for (i in 0 until axisPoints.size - 1) {
        val a = axisPoints[i]
        val b = axisPoints[i + 1]
        val length = sqrt((b.east - a.east) * (b.east - a.east) + (b.north - a.north) * (b.north - a.north))
        segments.add(AxisSegment(a, b, chainage, length))
        chainage += length
    }
    return segments
}

data class ProjectionResult(val chainage: Double, val offset: Double)

/** Proyecta un punto sobre el segmento más cercano del eje; devuelve progresiva y distancia de desvío (offset). */
fun projectOntoAxis(point: ProfileCandidate, segments: List<AxisSegment>): ProjectionResult? {
    if (segments.isEmpty()) return null
    var best: ProjectionResult? = null
    for (seg in segments) {
        val dx = seg.b.east - seg.a.east
        val dy = seg.b.north - seg.a.north
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) continue
        var t = ((point.east - seg.a.east) * dx + (point.north - seg.a.north) * dy) / lenSq
        t = t.coerceIn(0.0, 1.0)
        val projE = seg.a.east + t * dx
        val projN = seg.a.north + t * dy
        val offset = sqrt((point.east - projE) * (point.east - projE) + (point.north - projN) * (point.north - projN))
        val chainage = seg.startChainage + t * seg.length
        if (best == null || offset < best.offset) {
            best = ProjectionResult(chainage, offset)
        }
    }
    return best
}

data class ProfileSample(val pointId: Long, val chainage: Double, val elevation: Double, val offset: Double)

data class ProfileResult(
    val applicable: Boolean,
    val reason: String?,
    val samples: List<ProfileSample>,
    val totalLength: Double,
)

/**
 * Perfil longitudinal: progresiva acumulada a lo largo de un eje definido por el usuario,
 * proyectando puntos fuera de eje con una tolerancia de offset. Guardas: el eje necesita al
 * menos 2 puntos, y se requieren al menos 2 puntos con cota dentro de la tolerancia.
 */
fun computeProfile(
    axisPoints: List<AxisPoint>,
    candidates: List<ProfileCandidate>,
    offsetTolerance: Double,
): ProfileResult {
    if (axisPoints.size < 2) {
        return ProfileResult(false, "El eje necesita al menos 2 puntos para definir una dirección.", emptyList(), 0.0)
    }
    val segments = buildAxisSegments(axisPoints)
    val totalLength = segments.sumOf { it.length }

    val samples = candidates.mapNotNull { candidate ->
        val elevation = candidate.elevation ?: return@mapNotNull null
        val projection = projectOntoAxis(candidate, segments) ?: return@mapNotNull null
        if (projection.offset > offsetTolerance) return@mapNotNull null
        ProfileSample(candidate.pointId, projection.chainage, elevation, projection.offset)
    }.sortedBy { it.chainage }

    if (samples.size < 2) {
        return ProfileResult(
            false,
            "Se requieren al menos 2 puntos con cota dentro de la tolerancia de offset (${offsetTolerance} m) de este eje.",
            samples,
            totalLength,
        )
    }

    return ProfileResult(true, null, samples, totalLength)
}
