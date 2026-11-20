package com.toposcribe.calc

import org.junit.Assert.assertEquals
import org.junit.Test

class TraverseTest {

    @Test
    fun `caso B1 -- triangulo 60 60 60d00m30s cierre mas30s correccion menos10s`() {
        val angles = listOf(
            dmsToDecimal(Dms(60, 0, 0.0)),
            dmsToDecimal(Dms(60, 0, 0.0)),
            dmsToDecimal(Dms(60, 0, 30.0)),
        )
        val result = computeAngularClosureClosedPolygon(angles)

        assertApprox(dmsToDecimal(Dms(180, 0, 30.0)), result.sumMeasuredDeg, 1e-6)
        assertEquals(180.0, result.theoreticalSumDeg, 1e-9)
        assertApprox(30.0 / 3600.0, result.misclosureDeg, 1e-6)
        assertApprox(-10.0 / 3600.0, result.correctionPerAngleDeg, 1e-6)

        assertApprox(dmsToDecimal(Dms(59, 59, 50.0)), result.balancedAnglesDeg[0], 1e-5)
        assertApprox(dmsToDecimal(Dms(59, 59, 50.0)), result.balancedAnglesDeg[1], 1e-5)
        assertApprox(dmsToDecimal(Dms(60, 0, 20.0)), result.balancedAnglesDeg[2], 1e-5)
    }

    @Test
    fun `chainAzimuths encadena Az_siguiente = Az_anterior + 180 + angulo normalizado`() {
        val azimuths = chainAzimuths(45.0, listOf(0.0, 90.0, 90.0))
        assertApprox(45.0, azimuths[0])
        assertApprox(315.0, azimuths[1]) // 45+180+90=315
        assertApprox(225.0, azimuths[2]) // 315+180+90=585 -> 225
    }

    @Test
    fun `caso B2 -- poligonal cerrada de 3 lados fE=005 fN=-003 fL=00583 perimetro=120006`() {
        val legDeltas = listOf(
            LegDelta(deltaE = 300.0, deltaN = 0.0, horizontalDistance = 300.0),
            LegDelta(deltaE = 0.0, deltaN = 400.0, horizontalDistance = 400.0),
            LegDelta(deltaE = -299.95, deltaN = -400.03, horizontalDistance = 500.06),
        )
        val result = computeLinearClosure(legDeltas, 1000.0, 1000.0, 1000.0, 1000.0)

        assertApprox(0.05, result.fE, 1e-6)
        assertApprox(-0.03, result.fN, 1e-6)
        assertApprox(0.0583, result.fL, 1e-3)
        assertApprox(1200.06, result.perimeter, 1e-6)
        assertApprox(1200.06 / result.fL, result.relativePrecisionDenominator, 1.0)
    }
}
