package com.toposcribe.calc

data class TrigLevelingInput(
    val startElevation: Double,
    val slopeDistance: Double?,
    val horizontalDistance: Double?,
    val zenithDeg: Double?,
    val verticalAngleDeg: Double?,
    val instrumentHeight: Double,
    val targetHeight: Double,
)

data class TrigLevelingResult(val elevationDiff: Double, val finalElevation: Double, val formulaId: String)

/**
 * Nivelación trigonométrica: mismas fórmulas de reducción vertical de Polar.kt, con formulaId
 * propio para distinguir en resultados si la cota vino de nivelación geométrica, trigonométrica o
 * control. Caso E1 verificado: elev0=250,hi=1.45,ht=0,Dh=120,α=15°30' -> ΔElev≈34.73 -> elev1≈284.73.
 * Caso E2: elev0=150,hi=ht=1.6 (se cancelan),Ds=75.5,α=-8°15' -> ΔElev≈-10.83 -> elev1≈139.17.
 */
fun computeTrigElevation(input: TrigLevelingInput): TrigLevelingResult? {
    val diffAndId: Pair<Double, String>? = when {
        input.zenithDeg != null && input.slopeDistance != null ->
            elevationDiffFromZenith(input.slopeDistance, input.zenithDeg, input.instrumentHeight, input.targetHeight) to "NIV-TRIG-ZENITAL"
        input.verticalAngleDeg != null && input.slopeDistance != null ->
            elevationDiffFromVerticalAngle(input.slopeDistance, input.verticalAngleDeg, input.instrumentHeight, input.targetHeight) to "NIV-TRIG-VERTICAL"
        input.zenithDeg != null && input.horizontalDistance != null ->
            elevationDiffFromZenithUsingHorizontal(input.horizontalDistance, input.zenithDeg, input.instrumentHeight, input.targetHeight) to "NIV-TRIG-ZENITAL-DH"
        input.verticalAngleDeg != null && input.horizontalDistance != null ->
            elevationDiffFromVerticalAngleUsingHorizontal(input.horizontalDistance, input.verticalAngleDeg, input.instrumentHeight, input.targetHeight) to "NIV-TRIG-VERTICAL-DH"
        else -> null
    }
    val (diff, formulaId) = diffAndId ?: return null
    return TrigLevelingResult(
        elevationDiff = diff,
        finalElevation = input.startElevation + diff,
        formulaId = formulaId,
    )
}
