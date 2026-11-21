package com.toposcribe.calc

import java.util.Locale
import kotlin.math.abs

/** BS = vista atrás (VA), IS = vista intermedia (VI), FS = vista adelante (VD). */
enum class ReadingType { BS, IS, FS }

data class LevelingObservationCalcInput(
    val id: Long,
    val sequenceOrder: Int,
    val pointId: Long,
    val readingType: ReadingType,
    val readingValue: Double,
    val isTurningPoint: Boolean,
    /** Distancia parcial desde el punto anterior del recorrido (solo se usa en VI y VD). */
    val partialDistance: Double? = null,
)

data class LevelingRowResult(
    val observationId: Long,
    val pointId: Long,
    val readingType: ReadingType,
    val readingValue: Double,
    val heightOfInstrument: Double,
    val elevation: Double,
    /** Número de estación del instrumento (1, 2, 3…: aumenta con cada vista atrás). */
    val stationNumber: Int = 0,
    val partialDistance: Double? = null,
    /** Distancia acumulada hasta este punto; null si falta alguna distancia parcial anterior. */
    val cumulativeDistance: Double? = null,
    /** Operación con valores sustituidos: «HI = 100.000 + 1.250 = 101.250» o «Cota = 101.250 − 0.850 = 100.400». */
    val operation: String = "",
    /** Esta fila es un punto de cambio (explícito, o una VD seguida de VA sobre el mismo punto). */
    val isTurningPoint: Boolean = false,
)

data class DifferentialLevelingResult(
    val rows: List<LevelingRowResult>,
    val sumBacksight: Double,
    val sumForesight: Double,
    /** Σ VA − Σ VD. */
    val checkValue: Double,
    val startElevation: Double,
    /** Cota de la última vista adelante (o la inicial si no hay ninguna). */
    val finalElevation: Double,
    val arithmeticCheckOk: Boolean,
    val warnings: List<String>,
)

private fun f3(v: Double): String = String.format(Locale.US, "%.3f", v)

/**
 * Nivelación geométrica: HI = cota de referencia + VA;  cota = HI − VI (o − VD).
 * Comprobación de libreta: Σ VA − Σ VD = cota final − cota inicial (la VI no participa).
 * Una VD seguida inmediatamente de una VA sobre el mismo punto se trata como punto de cambio aunque
 * no esté marcada, porque es la única lectura coherente (la VA debe partir de la cota de esa VD).
 */
fun computeDifferentialLeveling(
    observations: List<LevelingObservationCalcInput>,
    startElevation: Double,
): DifferentialLevelingResult {
    val sorted = observations.sortedBy { it.sequenceOrder }
    val rows = mutableListOf<LevelingRowResult>()
    val warnings = mutableListOf<String>()

    var baseline = startElevation
    var lastForesightElevation = startElevation
    var currentHi: Double? = null
    var sumBs = 0.0
    var sumFs = 0.0
    var station = 0
    var cumulative: Double? = 0.0

    for ((index, obs) in sorted.withIndex()) {
        when (obs.readingType) {
            ReadingType.BS -> {
                station += 1
                val hi = baseline + obs.readingValue
                currentHi = hi
                sumBs += obs.readingValue
                rows.add(
                    LevelingRowResult(
                        obs.id, obs.pointId, obs.readingType, obs.readingValue, hi, baseline,
                        stationNumber = station, partialDistance = null, cumulativeDistance = cumulative,
                        operation = "HI = ${f3(baseline)} + ${f3(obs.readingValue)} = ${f3(hi)}",
                    ),
                )
            }
            ReadingType.IS, ReadingType.FS -> {
                val hi = currentHi
                if (hi == null) {
                    warnings.add("La lectura ${obs.sequenceOrder} (punto ${obs.pointId}) no tiene una vista atrás previa: no se puede calcular su cota.")
                    continue
                }
                val elevation = hi - obs.readingValue
                val next = sorted.getOrNull(index + 1)
                val turning = obs.readingType == ReadingType.FS &&
                    (obs.isTurningPoint || (next != null && next.readingType == ReadingType.BS && next.pointId == obs.pointId))
                if (obs.readingType == ReadingType.FS) {
                    sumFs += obs.readingValue
                    lastForesightElevation = elevation
                    if (next != null && next.readingType == ReadingType.BS && !turning) {
                        warnings.add("La vista adelante ${obs.sequenceOrder} va seguida de otra vista atrás en un punto distinto: marque el punto de cambio o la cadena de cotas se rompe.")
                    }
                }
                if (turning) baseline = elevation
                val partial = obs.partialDistance
                cumulative = if (cumulative != null && partial != null) cumulative + partial else null
                rows.add(
                    LevelingRowResult(
                        obs.id, obs.pointId, obs.readingType, obs.readingValue, hi, elevation,
                        stationNumber = station, partialDistance = partial, cumulativeDistance = cumulative,
                        operation = "Cota = ${f3(hi)} − ${f3(obs.readingValue)} = ${f3(elevation)}",
                        isTurningPoint = turning,
                    ),
                )
            }
        }
    }

    val checkValue = sumBs - sumFs
    val expectedDelta = lastForesightElevation - startElevation
    return DifferentialLevelingResult(
        rows = rows,
        sumBacksight = sumBs,
        sumForesight = sumFs,
        checkValue = checkValue,
        startElevation = startElevation,
        finalElevation = lastForesightElevation,
        arithmeticCheckOk = abs(checkValue - expectedDelta) < 1e-6,
        warnings = warnings,
    )
}

/**
 * Error de cierre contra una cota de cierre conocida de forma independiente:
 * cota final calculada − cota conocida. Distinto de la comprobación aritmética, que es una identidad.
 */
fun computeFieldMisclosure(finalElevation: Double, knownEndElevation: Double): Double =
    finalElevation - knownEndElevation

// ---------------------------------------------------------------------------------------------
// Perfil longitudinal
// ---------------------------------------------------------------------------------------------

data class LevelingProfilePoint(val pointId: Long, val code: String, val cumulativeDistance: Double, val elevation: Double)

data class IncompleteProfilePoint(val code: String, val missing: String)

data class LevelingProfile(
    val valid: Boolean,
    val reason: String?,
    val points: List<LevelingProfilePoint>,
    val incomplete: List<IncompleteProfilePoint>,
    val totalLength: Double,
    val minElevation: Double,
    val maxElevation: Double,
)

/**
 * Perfil longitudinal: distancia acumulada (eje horizontal) contra cota (eje vertical). Los puntos
 * del perfil son el BM inicial (distancia 0) y cada vista intermedia/adelante. Si a algún punto le
 * falta la distancia o la cota, NO hay perfil: se devuelve la lista de puntos incompletos.
 */
fun buildLevelingProfile(
    rows: List<LevelingRowResult>,
    codeByPointId: Map<Long, String>,
): LevelingProfile {
    fun codeOf(id: Long) = codeByPointId[id] ?: "#$id"
    val first = rows.firstOrNull { it.readingType == ReadingType.BS }
    if (first == null) {
        return LevelingProfile(false, "No hay lecturas calculadas: registre el BM con su vista atrás y calcule la altimetría.", emptyList(), emptyList(), 0.0, 0.0, 0.0)
    }
    val points = mutableListOf<LevelingProfilePoint>()
    val incomplete = mutableListOf<IncompleteProfilePoint>()
    points.add(LevelingProfilePoint(first.pointId, codeOf(first.pointId), 0.0, first.elevation))
    for (row in rows) {
        if (row.readingType == ReadingType.BS) continue
        val missing = mutableListOf<String>()
        if (row.partialDistance == null) missing += "distancia parcial"
        if (row.elevation.isNaN()) missing += "cota"
        if (missing.isNotEmpty()) {
            incomplete.add(IncompleteProfilePoint(codeOf(row.pointId), "falta " + missing.joinToString(" y ")))
        } else if (row.cumulativeDistance != null) {
            points.add(LevelingProfilePoint(row.pointId, codeOf(row.pointId), row.cumulativeDistance, row.elevation))
        } // si la acumulada es null es por una distancia parcial anterior ausente, ya listada arriba
    }
    if (incomplete.isNotEmpty()) {
        return LevelingProfile(
            false,
            "El perfil no se dibuja porque hay puntos incompletos (${incomplete.size}). Complete las distancias parciales.",
            points, incomplete, 0.0, 0.0, 0.0,
        )
    }
    if (points.size < 2) {
        return LevelingProfile(false, "Se necesitan al menos 2 puntos (BM y otra lectura) para trazar un perfil.", points, incomplete, 0.0, 0.0, 0.0)
    }
    if (points.map { it.cumulativeDistance }.zipWithNext().any { (a, b) -> b < a - 1e-9 }) {
        return LevelingProfile(false, "Las distancias acumuladas no son crecientes; revise las distancias parciales.", points, incomplete, 0.0, 0.0, 0.0)
    }
    val total = points.maxOf { it.cumulativeDistance }
    if (total <= 0.0) {
        return LevelingProfile(false, "La distancia total es 0: registre las distancias parciales entre puntos.", points, incomplete, 0.0, 0.0, 0.0)
    }
    return LevelingProfile(true, null, points, incomplete, total, points.minOf { it.elevation }, points.maxOf { it.elevation })
}

/** Escalas «redondas» de dibujo: 1:N con N de la serie 1-2-2.5-5 × 10^k, para un ancho de papel dado. */
fun niceScaleDenominator(worldExtent: Double, paperExtentMm: Double, unitMeters: Double = 1.0): Double {
    if (worldExtent <= 0.0 || paperExtentMm <= 0.0) return 1.0
    val raw = worldExtent * unitMeters * 1000.0 / paperExtentMm // N tal que cabe justo
    var p = 1.0
    while (p * 10 < raw) p *= 10
    while (p > raw) p /= 10
    for (m in listOf(1.0, 2.0, 2.5, 5.0, 10.0)) if (p * m >= raw) return p * m
    return p * 10
}
