package com.toposcribe.calc

import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

data class AngularClosureResult(
    val sumMeasuredDeg: Double,
    val theoreticalSumDeg: Double,
    val misclosureDeg: Double,
    val correctionPerAngleDeg: Double,
    val balancedAnglesDeg: List<Double>,
)

/**
 * Cierre angular de una poligonal cerrada: cierre = Σángulos medidos - (n-2)·180°.
 * Caso B1 verificado: triángulo 60°00'00",60°00'00",60°00'30" -> cierre +30", corrección -10"/ángulo
 * -> balanceados 59°59'50",59°59'50",60°00'20".
 */
fun computeAngularClosureClosedPolygon(measuredAnglesDeg: List<Double>): AngularClosureResult {
    val n = measuredAnglesDeg.size
    val sumMeasured = measuredAnglesDeg.sum()
    val theoreticalSum = (n - 2) * 180.0
    val misclosure = sumMeasured - theoreticalSum
    val correctionPerAngle = -misclosure / n
    val balanced = measuredAnglesDeg.map { it + correctionPerAngle }
    return AngularClosureResult(sumMeasured, theoreticalSum, misclosure, correctionPerAngle, balanced)
}

/**
 * Encadena azimutes a partir de un azimut de partida y los ángulos (ya balanceados) de cada
 * estación: Az_siguiente = Az_anterior + 180° + ángulo_interior (normalizado a [0,360)).
 * balancedAnglesDeg[0] es el ángulo medido en la primera estación (usado solo para el cierre
 * angular de una poligonal cerrada) y NO se usa aquí, porque el primer tramo parte del azimut de
 * arranque dado directamente, no de un tramo anterior dentro de la poligonal.
 */
fun chainAzimuths(startAzimuthDeg: Double, balancedAnglesDeg: List<Double>): List<Double> {
    val azimuths = mutableListOf(normalizeAzimuth(startAzimuthDeg))
    for (i in 1 until balancedAnglesDeg.size) {
        val next = normalizeAzimuth(azimuths[i - 1] + 180.0 + balancedAnglesDeg[i])
        azimuths.add(next)
    }
    return azimuths
}

data class LegDelta(val deltaE: Double, val deltaN: Double, val horizontalDistance: Double)

fun legDelta(azimuthDeg: Double, horizontalDistance: Double): LegDelta {
    val azRad = toRadians(azimuthDeg)
    return LegDelta(
        deltaE = horizontalDistance * sin(azRad),
        deltaN = horizontalDistance * cos(azRad),
        horizontalDistance = horizontalDistance,
    )
}

data class StationCoordinate(val east: Double, val north: Double)

data class LinearClosureResult(
    /** Una coordenada bruta por tramo, en orden -- NO incluye la estación de partida. */
    val rawStations: List<StationCoordinate>,
    val fE: Double,
    val fN: Double,
    val fL: Double,
    val perimeter: Double,
    val relativePrecisionDenominator: Double,
    val relativePrecisionLabel: String,
)

/**
 * Cierre lineal: propaga coordenadas brutas desde el punto de partida sumando los ΔE/ΔN de cada
 * tramo, y compara la posición final bruta contra la posición final conocida (el mismo punto de
 * partida en una poligonal cerrada, o el punto de amarre conocido en una abierta amarrada).
 * fE, fN = error de cierre por eje; fL = error de cierre lineal; precisión relativa = perímetro/fL.
 * Caso B2 verificado: fE=+0.050,fN=-0.030,fL=0.0583,perímetro=1200.06 -> precisión ≈1:20,580.
 */
fun computeLinearClosure(
    legDeltas: List<LegDelta>,
    startEast: Double,
    startNorth: Double,
    knownEndEast: Double,
    knownEndNorth: Double,
): LinearClosureResult {
    val rawStations = mutableListOf<StationCoordinate>()
    var east = startEast
    var north = startNorth
    for (leg in legDeltas) {
        east += leg.deltaE
        north += leg.deltaN
        rawStations.add(StationCoordinate(east, north))
    }

    val fE = east - knownEndEast
    val fN = north - knownEndNorth
    val fL = sqrt(fE * fE + fN * fN)
    val perimeter = legDeltas.sumOf { it.horizontalDistance }
    val relativePrecisionDenominator = if (fL > 0.0) perimeter / fL else Double.POSITIVE_INFINITY
    val relativePrecisionLabel = if (fL > 0.0 && relativePrecisionDenominator.isFinite()) {
        "1:${Math.round(relativePrecisionDenominator)}"
    } else {
        "1:∞ (cierre exacto)"
    }
    return LinearClosureResult(rawStations, fE, fN, fL, perimeter, relativePrecisionDenominator, relativePrecisionLabel)
}
