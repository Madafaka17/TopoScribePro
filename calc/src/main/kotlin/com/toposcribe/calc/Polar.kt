package com.toposcribe.calc

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class PolarCoordinatesInput(
    val eastFrom: Double,
    val northFrom: Double,
    val azimuthDeg: Double,
    val horizontalDistance: Double,
)

data class PolarCoordinatesResult(val east: Double, val north: Double)

/** Radiación: E2 = E1 + d·sen(A), N2 = N1 + d·cos(A) (A medido desde el norte en sentido horario). */
fun computePolarCoordinates(input: PolarCoordinatesInput): PolarCoordinatesResult {
    val azRad = toRadians(input.azimuthDeg)
    return PolarCoordinatesResult(
        east = input.eastFrom + input.horizontalDistance * sin(azRad),
        north = input.northFrom + input.horizontalDistance * cos(azRad),
    )
}

/** Dh = Ds·sin(Z), con Z = ángulo cenital medido desde la vertical. */
fun horizontalDistanceFromZenith(slopeDistance: Double, zenithDeg: Double): Double =
    slopeDistance * sin(toRadians(zenithDeg))

/** Dh = Ds·cos(α), con α = ángulo vertical medido desde la horizontal. */
fun horizontalDistanceFromVerticalAngle(slopeDistance: Double, verticalAngleDeg: Double): Double =
    slopeDistance * cos(toRadians(verticalAngleDeg))

// ---------------------------------------------------------------------------------------------
// Radiación con teodolito: orientación, convenio angular y memoria de cálculo con valores
// sustituidos. Todo es función pura: la app solo la alimenta y la muestra/guarda.
// ---------------------------------------------------------------------------------------------

/** Sentido en que crece la lectura del círculo horizontal del teodolito. */
enum class AngleConvention(val labelEs: String) {
    HORARIO("Horario (la lectura crece hacia la derecha)"),
    ANTIHORARIO("Antihorario (la lectura crece hacia la izquierda)"),
}

/** Cómo se registró el ángulo horizontal de un punto visado. */
enum class HorizontalKind { AZIMUT, LECTURA_HORIZONTAL }

/** La distancia anotada es horizontal o inclinada (esta última exige ángulo vertical o cenital). */
enum class DistanceKind { HORIZONTAL, INCLINADA }

enum class VerticalKind { CENITAL, VERTICAL }

/**
 * Orientación de la estación: azimut conocido de la referencia (vista atrás) y lectura del círculo
 * horizontal al visarla. Con convenio horario: Az(punto) = Az(ref) + (Hz(punto) − Hz(ref)).
 */
data class StationOrientation(
    val referenceAzimuthDeg: Double,
    val referenceReadingDeg: Double,
    val convention: AngleConvention,
)

data class RadiationInput(
    val stationEast: Double,
    val stationNorth: Double,
    val orientation: StationOrientation,
    val horizontalKind: HorizontalKind,
    /** Azimut (desde el norte) o lectura del círculo horizontal, en grados decimales. */
    val horizontalAngleDeg: Double,
    val distance: Double,
    val distanceKind: DistanceKind,
    val verticalKind: VerticalKind? = null,
    val verticalAngleDeg: Double? = null,
)

data class RadiationStep(
    val azimuthDeg: Double,
    val horizontalDistance: Double,
    val deltaE: Double,
    val deltaN: Double,
    val east: Double,
    val north: Double,
    /** Líneas de la memoria de cálculo, ya con los valores sustituidos. */
    val azimuthFormula: String,
    val distanceFormula: String,
    val deltaEFormula: String,
    val deltaNFormula: String,
    val eastFormula: String,
    val northFormula: String,
)

data class RadiationOutcome(val step: RadiationStep?, val errors: List<String>)

private fun f3(v: Double): String = String.format(Locale.US, "%.3f", if (abs(v) < 5e-10) 0.0 else v)
private fun signed(v: Double): String = if (v < 0) "− ${f3(abs(v))}" else "+ ${f3(v)}"

/** Azimut de un punto a partir de su lectura horizontal y la orientación de la estación (0° ≤ Az < 360°). */
fun azimuthFromReading(readingDeg: Double, o: StationOrientation): Double {
    val delta = readingDeg - o.referenceReadingDeg
    val sign = if (o.convention == AngleConvention.HORARIO) 1.0 else -1.0
    return normalizeAzimuth(o.referenceAzimuthDeg + sign * delta)
}

/**
 * Calcula un punto radiado. No inventa datos: si falta o es inválido algún dato necesario devuelve
 * la lista de errores y ningún resultado.
 */
fun computeRadiation(input: RadiationInput): RadiationOutcome {
    val errors = mutableListOf<String>()
    if (input.distance <= 0.0 || input.distance.isNaN()) errors += "La distancia debe ser mayor que 0."
    if (input.horizontalKind == HorizontalKind.AZIMUT && (input.horizontalAngleDeg < 0.0 || input.horizontalAngleDeg > 360.0)) {
        errors += "El azimut debe estar entre 0° y 360°."
    }
    if (input.horizontalKind == HorizontalKind.LECTURA_HORIZONTAL && (input.horizontalAngleDeg < 0.0 || input.horizontalAngleDeg >= 360.0)) {
        errors += "La lectura horizontal debe estar entre 0° y 360°."
    }
    var dh = input.distance
    var distanceFormula = "d = ${f3(input.distance)} (distancia horizontal medida)"
    if (input.distanceKind == DistanceKind.INCLINADA) {
        val kind = input.verticalKind
        val v = input.verticalAngleDeg
        if (kind == null || v == null) {
            errors += "La distancia es inclinada: falta el ángulo vertical o cenital para reducirla a horizontal."
        } else if (kind == VerticalKind.CENITAL) {
            if (v <= 0.0 || v >= 180.0) errors += "El ángulo cenital debe estar entre 0° y 180° (sin incluirlos)."
            else {
                dh = horizontalDistanceFromZenith(input.distance, v)
                distanceFormula = "d = Ds·sen(Z) = ${f3(input.distance)} × sen(${formatDegreesAsDms(v)}) = ${f3(dh)}"
            }
        } else {
            if (v <= -90.0 || v >= 90.0) errors += "El ángulo vertical debe estar entre −90° y 90° (sin incluirlos)."
            else {
                dh = horizontalDistanceFromVerticalAngle(input.distance, v)
                distanceFormula = "d = Ds·cos(α) = ${f3(input.distance)} × cos(${formatDegreesAsDms(v)}) = ${f3(dh)}"
            }
        }
    }
    if (errors.isNotEmpty()) return RadiationOutcome(null, errors)

    val o = input.orientation
    val azimuth: Double
    val azimuthFormula: String
    if (input.horizontalKind == HorizontalKind.AZIMUT) {
        azimuth = normalizeAzimuth(input.horizontalAngleDeg)
        azimuthFormula = "A = ${formatDegreesAsDms(input.horizontalAngleDeg)} (azimut medido desde el norte)" +
            if (azimuth != input.horizontalAngleDeg) " → normalizado ${formatDegreesAsDms(azimuth)}" else ""
    } else {
        azimuth = azimuthFromReading(input.horizontalAngleDeg, o)
        val sign = if (o.convention == AngleConvention.HORARIO) "+" else "−"
        azimuthFormula = "A = Az_ref $sign (Hz − Hz_ref) = ${formatDegreesAsDms(o.referenceAzimuthDeg)} $sign " +
            "(${formatDegreesAsDms(input.horizontalAngleDeg)} − ${formatDegreesAsDms(o.referenceReadingDeg)}) = ${formatDegreesAsDms(azimuth)}"
    }

    val r = computePolarCoordinates(PolarCoordinatesInput(input.stationEast, input.stationNorth, azimuth, dh))
    val dE = r.east - input.stationEast
    val dN = r.north - input.stationNorth
    return RadiationOutcome(
        RadiationStep(
            azimuthDeg = azimuth,
            horizontalDistance = dh,
            deltaE = dE,
            deltaN = dN,
            east = r.east,
            north = r.north,
            azimuthFormula = azimuthFormula,
            distanceFormula = distanceFormula,
            deltaEFormula = "ΔE = d·sen(A) = ${f3(dh)} × sen(${formatDegreesAsDms(azimuth)}) = ${f3(dE)}",
            deltaNFormula = "ΔN = d·cos(A) = ${f3(dh)} × cos(${formatDegreesAsDms(azimuth)}) = ${f3(dN)}",
            eastFormula = "E₂ = E₁ + ΔE = ${f3(input.stationEast)} ${signed(dE)} = ${f3(r.east)}",
            northFormula = "N₂ = N₁ + ΔN = ${f3(input.stationNorth)} ${signed(dN)} = ${f3(r.north)}",
        ),
        emptyList(),
    )
}

/** Distancia horizontal entre dos puntos con coordenadas calculadas. */
fun planDistance(e1: Double, n1: Double, e2: Double, n2: Double): Double =
    kotlin.math.hypot(e2 - e1, n2 - n1)

/** Azimut (0..360) de la línea 1→2. */
fun planAzimuth(e1: Double, n1: Double, e2: Double, n2: Double): Double =
    normalizeAzimuth(toDegrees(kotlin.math.atan2(e2 - e1, n2 - n1)))
