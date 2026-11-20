package com.toposcribe.calc

enum class ReadingType { BS, IS, FS }

data class LevelingObservationCalcInput(
    val id: Long,
    val sequenceOrder: Int,
    val pointId: Long,
    val readingType: ReadingType,
    val readingValue: Double,
    val isTurningPoint: Boolean,
)

data class LevelingRowResult(
    val observationId: Long,
    val pointId: Long,
    val readingType: ReadingType,
    val readingValue: Double,
    val heightOfInstrument: Double,
    val elevation: Double,
)

data class DifferentialLevelingResult(
    val rows: List<LevelingRowResult>,
    val sumBacksight: Double,
    val sumForesight: Double,
    val checkValue: Double,
    val startElevation: Double,
    val finalElevation: Double,
    val arithmeticCheckOk: Boolean,
    val warnings: List<String>,
)

/**
 * Nivelación geométrica/diferencial: HI = Elev_atrás + BS, Elev = HI - FS (o - IS). Comprobación
 * de libreta: ΣBS - ΣFS == Elev_final - Elev_inicial (identidad aritmética, IS no participa).
 * Caso D1 verificado: BM1=100.000 -> tras 3 cambios de estación -> BM2=99.500, ΣBS=4.200,
 * ΣFS=4.700, ΣBS-ΣFS=-0.500=BM2-BM1.
 */
fun computeDifferentialLeveling(
    observations: List<LevelingObservationCalcInput>,
    startElevation: Double,
): DifferentialLevelingResult {
    val sorted = observations.sortedBy { it.sequenceOrder }
    val rows = mutableListOf<LevelingRowResult>()
    val warnings = mutableListOf<String>()

    // "baseline" es la cota del último punto de cambio (o la inicial): solo avanza en los
    // renglones marcados is_turning_point, y es la que se usa para recalcular la HI en la
    // siguiente vista atrás. "lastElevation" es la cota del ÚLTIMO renglón procesado sin importar
    // si fue punto de cambio -- es la que se reporta como finalElevation (verificado contra el
    // caso D1: el último FS de la libreta, hacia BM2, tiene is_turning_point=false y aun así
    // finalElevation debe quedar en la cota de BM2, no en la del último punto de cambio real).
    var baseline = startElevation
    var lastElevation = startElevation
    var currentHi: Double? = null
    var sumBs = 0.0
    var sumFs = 0.0

    for (obs in sorted) {
        when (obs.readingType) {
            ReadingType.BS -> {
                currentHi = baseline + obs.readingValue
                sumBs += obs.readingValue
                lastElevation = baseline
                rows.add(LevelingRowResult(obs.id, obs.pointId, obs.readingType, obs.readingValue, currentHi, baseline))
            }
            ReadingType.IS, ReadingType.FS -> {
                val hi = currentHi
                if (hi == null) {
                    warnings.add("La observación de la secuencia ${obs.sequenceOrder} (punto ${obs.pointId}) no tiene una vista atrás previa en esta corrida: no se puede calcular su cota.")
                    continue
                }
                val elevation = hi - obs.readingValue
                if (obs.readingType == ReadingType.FS) sumFs += obs.readingValue
                lastElevation = elevation
                if (obs.isTurningPoint) baseline = elevation
                rows.add(LevelingRowResult(obs.id, obs.pointId, obs.readingType, obs.readingValue, hi, elevation))
            }
        }
    }

    val finalElevation = lastElevation
    val checkValue = sumBs - sumFs
    val expectedDelta = finalElevation - startElevation
    val arithmeticCheckOk = kotlin.math.abs(checkValue - expectedDelta) < 1e-6

    return DifferentialLevelingResult(
        rows = rows,
        sumBacksight = sumBs,
        sumForesight = sumFs,
        checkValue = checkValue,
        startElevation = startElevation,
        finalElevation = finalElevation,
        arithmeticCheckOk = arithmeticCheckOk,
        warnings = warnings,
    )
}

/**
 * Cierre de campo REAL contra una cota de cierre conocida de forma independiente -- distinto de
 * arithmeticCheckOk, que es una identidad aritmética (siempre se cumple si la cadena HI/elevación
 * está bien sumada), no una verificación de error real.
 */
fun computeFieldMisclosure(finalElevation: Double, knownEndElevation: Double): Double =
    finalElevation - knownEndElevation
