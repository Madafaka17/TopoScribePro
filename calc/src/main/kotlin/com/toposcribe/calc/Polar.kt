package com.toposcribe.calc

import kotlin.math.cos
import kotlin.math.sin

data class PolarCoordinatesInput(
    val eastFrom: Double,
    val northFrom: Double,
    val azimuthDeg: Double,
    val horizontalDistance: Double,
)

data class PolarCoordinatesResult(val east: Double, val north: Double)

/** Radiación: E1 = E0 + Dh·sin(Az), N1 = N0 + Dh·cos(Az). Caso A1 verificado: Az=45°,Dh=100 -> E1=N1=1070.711. */
fun computePolarCoordinates(input: PolarCoordinatesInput): PolarCoordinatesResult {
    val azRad = toRadians(input.azimuthDeg)
    val deltaE = input.horizontalDistance * sin(azRad)
    val deltaN = input.horizontalDistance * cos(azRad)
    return PolarCoordinatesResult(
        east = input.eastFrom + deltaE,
        north = input.northFrom + deltaN,
    )
}

/** Dh = Ds·sin(Z), con Z = ángulo cenital medido desde la vertical. */
fun horizontalDistanceFromZenith(slopeDistance: Double, zenithDeg: Double): Double =
    slopeDistance * sin(toRadians(zenithDeg))

/** Dh = Ds·cos(α), con α = ángulo vertical medido desde la horizontal. */
fun horizontalDistanceFromVerticalAngle(slopeDistance: Double, verticalAngleDeg: Double): Double =
    slopeDistance * cos(toRadians(verticalAngleDeg))

/** ΔElev = Ds·cos(Z) + hi - ht. Caso A2 verificado: Ds=50,Z=80°,hi=1.5,ht=1.2 -> Dh=49.240, ΔElev=8.982. */
fun elevationDiffFromZenith(slopeDistance: Double, zenithDeg: Double, instrumentHeight: Double, targetHeight: Double): Double =
    slopeDistance * cos(toRadians(zenithDeg)) + instrumentHeight - targetHeight

/** ΔElev = Ds·sin(α) + hi - ht. */
fun elevationDiffFromVerticalAngle(slopeDistance: Double, verticalAngleDeg: Double, instrumentHeight: Double, targetHeight: Double): Double =
    slopeDistance * sin(toRadians(verticalAngleDeg)) + instrumentHeight - targetHeight

/** ΔElev = Dh·cot(Z) + hi - ht, cuando se conoce la distancia horizontal en vez de la inclinada. */
fun elevationDiffFromZenithUsingHorizontal(horizontalDistance: Double, zenithDeg: Double, instrumentHeight: Double, targetHeight: Double): Double {
    val zRad = toRadians(zenithDeg)
    val cot = cos(zRad) / sin(zRad)
    return horizontalDistance * cot + instrumentHeight - targetHeight
}

/** ΔElev = Dh·tan(α) + hi - ht, cuando se conoce la distancia horizontal en vez de la inclinada. */
fun elevationDiffFromVerticalAngleUsingHorizontal(horizontalDistance: Double, verticalAngleDeg: Double, instrumentHeight: Double, targetHeight: Double): Double {
    val aRad = toRadians(verticalAngleDeg)
    val tan = sin(aRad) / cos(aRad)
    return horizontalDistance * tan + instrumentHeight - targetHeight
}
