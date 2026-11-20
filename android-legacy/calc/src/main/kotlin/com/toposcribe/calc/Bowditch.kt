package com.toposcribe.calc

enum class TraverseType { CERRADA_POLIGONO, ABIERTA_AMARRADA, ABIERTA_LIBRE }

data class BowditchApplicabilityInput(val traverseType: TraverseType, val bothEndsKnown: Boolean)

data class BowditchApplicability(val applicable: Boolean, val reason: String?)

/**
 * Guarda de aplicabilidad: la compensación de Bowditch solo aplica a una poligonal cerrada, o a
 * una abierta amarrada con ambos extremos conocidos. Nunca se inventa una compensación para una
 * poligonal abierta libre -- se explica por qué no se puede, en español.
 */
fun checkBowditchApplicability(input: BowditchApplicabilityInput): BowditchApplicability = when {
    input.traverseType == TraverseType.CERRADA_POLIGONO -> BowditchApplicability(true, null)
    input.traverseType == TraverseType.ABIERTA_AMARRADA && input.bothEndsKnown ->
        BowditchApplicability(true, null)
    input.traverseType == TraverseType.ABIERTA_AMARRADA ->
        BowditchApplicability(false, "La poligonal abierta amarrada necesita que ambos extremos sean puntos de control con coordenadas conocidas para calcular el cierre y compensar.")
    else ->
        BowditchApplicability(false, "Una poligonal abierta libre no tiene un punto de cierre conocido: no se puede calcular un error de cierre ni compensar. Los resultados se muestran sin compensar.")
}

data class LegCorrection(val correctionE: Double, val correctionN: Double)
data class BowditchResult(val adjustedStations: List<StationCoordinate>, val corrections: List<LegCorrection>)

/**
 * corrección_E_i = -fE·(d_i/perímetro), corrección_N_i análoga. Sobre el caso B2: correcciones
 * (-0.0125,+0.0075),(-0.0167,+0.01),(-0.0208,+0.0125) -- suman exactamente -fE/-fN.
 */
fun applyBowditch(
    legDeltas: List<LegDelta>,
    fE: Double,
    fN: Double,
    perimeter: Double,
    startEast: Double,
    startNorth: Double,
): BowditchResult {
    val corrections = legDeltas.map { leg ->
        val ratio = leg.horizontalDistance / perimeter
        LegCorrection(correctionE = -fE * ratio, correctionN = -fN * ratio)
    }

    val stations = mutableListOf<StationCoordinate>()
    var east = startEast
    var north = startNorth
    for (i in legDeltas.indices) {
        east += legDeltas[i].deltaE + corrections[i].correctionE
        north += legDeltas[i].deltaN + corrections[i].correctionN
        stations.add(StationCoordinate(east, north))
    }
    return BowditchResult(stations, corrections)
}
