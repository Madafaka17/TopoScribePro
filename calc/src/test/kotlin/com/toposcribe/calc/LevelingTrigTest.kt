package com.toposcribe.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelingTrigTest {

    @Test
    fun `caso E1 -- elev0=250 hi=145 ht=0 Dh=120 alfa=15g30 produce elev1 cerca de 28473`() {
        val result = computeTrigElevation(
            TrigLevelingInput(
                startElevation = 250.0,
                slopeDistance = null,
                horizontalDistance = 120.0,
                zenithDeg = null,
                verticalAngleDeg = 15.5,
                instrumentHeight = 1.45,
                targetHeight = 0.0,
            ),
        )!!
        assertEquals("NIV-TRIG-VERTICAL-DH", result.formulaId)
        assertApprox(284.73, result.finalElevation, 0.05)
    }

    @Test
    fun `caso E2 -- elev0=150 hi=ht=16 Ds=755 alfa=-8g15 produce elev1 cerca de 13917`() {
        val result = computeTrigElevation(
            TrigLevelingInput(
                startElevation = 150.0,
                slopeDistance = 75.5,
                horizontalDistance = null,
                zenithDeg = null,
                verticalAngleDeg = -8.25,
                instrumentHeight = 1.6,
                targetHeight = 1.6,
            ),
        )!!
        assertEquals("NIV-TRIG-VERTICAL", result.formulaId)
        assertApprox(139.17, result.finalElevation, 0.05)
    }

    @Test
    fun `devuelve null si no hay distancia ni angulo suficientes`() {
        val result = computeTrigElevation(
            TrigLevelingInput(
                startElevation = 100.0,
                slopeDistance = null,
                horizontalDistance = null,
                zenithDeg = null,
                verticalAngleDeg = null,
                instrumentHeight = 1.5,
                targetHeight = 1.5,
            ),
        )
        assertNull(result)
    }
}
