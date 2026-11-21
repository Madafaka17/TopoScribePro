package com.toposcribe.calc

/**
 * Modelo único del plano: lo consumen la pantalla (Plano CAD), el PDF y el DXF, de modo que los
 * tres muestran exactamente las mismas cifras. Solo contiene puntos con Este y Norte calculados.
 */
data class PlanPoint(
    val pointId: Long,
    val code: String,
    val description: String?,
    val east: Double,
    val north: Double,
    val elevation: Double?,
    val elevationSource: ElevationSource,
    val isStation: Boolean,
)

/** Radiación estación → punto visado. */
data class PlanRadiation(val stationPointId: Long, val targetPointId: Long)

/** Línea definida por el usuario uniendo puntos en el orden elegido. */
data class PlanPolyline(val name: String, val pointIds: List<Long>, val closed: Boolean)

/** Capas del plano (pantalla y DXF). */
enum class PlanLayer(val dxfName: String, val labelEs: String, val dxfColor: Int) {
    PUNTOS("TOPO-PUNTOS", "Puntos", 7),
    CODIGOS("TOPO-CODIGOS", "Códigos", 2),
    ESTACION("TOPO-ESTACION", "Estación", 1),
    RADIACIONES("TOPO-RADIACIONES", "Radiaciones", 4),
    LINEAS("TOPO-LINEAS", "Líneas", 5),
    COTAS("TOPO-COTAS", "Cotas", 3),
    TRIANGULACION("TOPO-TRIANGULACION", "Triangulación", 8),
    CURVAS("TOPO-CURVAS", "Curvas de nivel", 6),
}

data class PlanModel(
    val projectName: String,
    val coordinateSystem: String,
    val unitsDistance: String,
    val points: List<PlanPoint>,
    val radiations: List<PlanRadiation>,
    val polylines: List<PlanPolyline>,
    /** Solo no nulo si las curvas se calcularon válidamente. */
    val contours: ContourResult?,
    val pointsWithoutCoordinates: Int,
) {
    val pointById: Map<Long, PlanPoint> by lazy { points.associateBy { it.pointId } }

    val hasElevations: Boolean get() = points.any { it.elevation != null }
    val hasValidContours: Boolean get() = contours?.applicable == true

    /** Capas que tienen algo que dibujar (las de cotas/TIN/curvas solo existen si se calcularon). */
    fun availableLayers(): Set<PlanLayer> = buildSet {
        if (points.isNotEmpty()) { add(PlanLayer.PUNTOS); add(PlanLayer.CODIGOS) }
        if (points.any { it.isStation }) add(PlanLayer.ESTACION)
        if (radiations.any { pointById.containsKey(it.stationPointId) && pointById.containsKey(it.targetPointId) }) add(PlanLayer.RADIACIONES)
        if (polylines.any { l -> l.pointIds.count { pointById.containsKey(it) } >= 2 }) add(PlanLayer.LINEAS)
        if (hasElevations) add(PlanLayer.COTAS)
        if (hasValidContours) { add(PlanLayer.TRIANGULACION); add(PlanLayer.CURVAS) }
    }

    /** Vértices de una línea del usuario, descartando los que no tienen coordenadas. */
    fun polylinePoints(line: PlanPolyline): List<PlanPoint> = line.pointIds.mapNotNull { pointById[it] }

    fun bounds(): PlanBounds? {
        if (points.isEmpty()) return null
        return PlanBounds(points.minOf { it.east }, points.minOf { it.north }, points.maxOf { it.east }, points.maxOf { it.north })
    }
}

data class PlanBounds(val minE: Double, val minN: Double, val maxE: Double, val maxN: Double) {
    val width get() = maxE - minE
    val height get() = maxN - minN
}

/** Longitud de una línea del usuario sumando sus tramos (solo con vértices que tienen coordenadas). */
fun polylineLengthOf(vertices: List<PlanPoint>, closed: Boolean): Double {
    var total = 0.0
    for (i in 0 until vertices.size - 1) total += planDistance(vertices[i].east, vertices[i].north, vertices[i + 1].east, vertices[i + 1].north)
    if (closed && vertices.size > 2) total += planDistance(vertices.last().east, vertices.last().north, vertices.first().east, vertices.first().north)
    return total
}
