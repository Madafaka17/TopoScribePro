package com.toposcribe.calc

import kotlin.math.abs

/**
 * Triangulación de Delaunay propia (Bowyer-Watson incremental), sin dependencias externas --
 * mismo criterio que ya usó el server Node para DXF ("escritor propio minimalista en vez de una
 * dependencia de terceros" cuando el subconjunto necesario es chico y estable). El dominio son
 * cientos/pocos miles de puntos de un levantamiento, no una malla GIS masiva, así que O(n²) en el
 * peor caso es aceptable.
 */
data class TriPoint(val id: Long, val x: Double, val y: Double)
data class Triangle(val a: TriPoint, val b: TriPoint, val c: TriPoint)

private data class Edge(val p1: TriPoint, val p2: TriPoint) {
    // Una arista es la misma independientemente del orden de sus extremos.
    override fun equals(other: Any?): Boolean {
        if (other !is Edge) return false
        return (p1 == other.p1 && p2 == other.p2) || (p1 == other.p2 && p2 == other.p1)
    }

    override fun hashCode(): Int = p1.hashCode() + p2.hashCode()
}

object Delaunay {

    /** Punto dentro del circuncírculo del triángulo (ABC debe estar orientado antihorario). */
    private fun inCircumcircle(tri: Triangle, p: TriPoint): Boolean {
        val ax = tri.a.x - p.x
        val ay = tri.a.y - p.y
        val bx = tri.b.x - p.x
        val by = tri.b.y - p.y
        val cx = tri.c.x - p.x
        val cy = tri.c.y - p.y

        val det = (ax * ax + ay * ay) * (bx * cy - cx * by) -
            (bx * bx + by * by) * (ax * cy - cx * ay) +
            (cx * cx + cy * cy) * (ax * by - bx * ay)

        return det > 1e-9
    }

    private fun signedArea2(a: TriPoint, b: TriPoint, c: TriPoint): Double =
        (b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)

    private fun ccw(tri: Triangle): Triangle =
        if (signedArea2(tri.a, tri.b, tri.c) < 0) Triangle(tri.a, tri.c, tri.b) else tri

    /**
     * Perturbación determinística mínima (variante de "simulation of simplicity"): sin esto, 4
     * puntos exactamente cocirculares (p. ej. las esquinas de una grilla regular de relleno
     * topográfico, un patrón de campo muy común) pueden quedar con una prueba de incírculo que da
     * cero exacto -- el punto no se detecta "dentro" de ningún circuncírculo y se descartaría en
     * silencio en vez de triangularse. El jitter solo afecta qué triángulos se forman (la
     * topología); Contours.kt siempre vuelve a buscar las coordenadas ORIGINALES por id para la
     * geometría final, así que la salida nunca queda desplazada.
     */
    private fun jitter(points: List<TriPoint>): List<TriPoint> = points.map { p ->
        val seed = p.id * 2654435761L
        val jx = ((seed and 0xFFFF).toDouble() / 0xFFFF - 0.5) * 1e-7
        val jy = (((seed ushr 16) and 0xFFFF).toDouble() / 0xFFFF - 0.5) * 1e-7
        TriPoint(p.id, p.x + jx, p.y + jy)
    }

    /** Triangula una nube de puntos E/N. Devuelve lista vacía si hay menos de 3 puntos o son colineales. */
    fun triangulate(rawPoints: List<TriPoint>): List<Triangle> {
        if (rawPoints.size < 3) return emptyList()
        val points = jitter(rawPoints)

        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val dx = maxX - minX
        val dy = maxY - minY
        val deltaMax = maxOf(dx, dy, 1.0)
        val midX = (minX + maxX) / 2.0
        val midY = (minY + maxY) / 2.0

        // Súper-triángulo que envuelve holgadamente a todos los puntos.
        val p1 = TriPoint(-1, midX - 20 * deltaMax, midY - deltaMax)
        val p2 = TriPoint(-2, midX, midY + 20 * deltaMax)
        val p3 = TriPoint(-3, midX + 20 * deltaMax, midY - deltaMax)
        var triangles = mutableListOf(ccw(Triangle(p1, p2, p3)))

        for (point in points) {
            val badTriangles = triangles.filter { inCircumcircle(it, point) }
            if (badTriangles.isEmpty()) continue // punto duplicado o degenerado: se omite, no rompe la malla

            val polygonEdges = mutableListOf<Edge>()
            for (tri in badTriangles) {
                val edges = listOf(Edge(tri.a, tri.b), Edge(tri.b, tri.c), Edge(tri.c, tri.a))
                for (edge in edges) {
                    val sharedByAnother = badTriangles.any { other ->
                        other !== tri && listOf(Edge(other.a, other.b), Edge(other.b, other.c), Edge(other.c, other.a)).contains(edge)
                    }
                    if (!sharedByAnother) polygonEdges.add(edge)
                }
            }

            triangles = triangles.filterNot { badTriangles.contains(it) }.toMutableList()
            for (edge in polygonEdges) {
                triangles.add(ccw(Triangle(edge.p1, edge.p2, point)))
            }
        }

        // Se descartan los triángulos que todavía comparten un vértice con el súper-triángulo.
        val superIds = setOf(-1L, -2L, -3L)
        return triangles.filterNot { tri ->
            superIds.contains(tri.a.id) || superIds.contains(tri.b.id) || superIds.contains(tri.c.id)
        }
    }
}
