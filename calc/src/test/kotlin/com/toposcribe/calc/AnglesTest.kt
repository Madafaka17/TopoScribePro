package com.toposcribe.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnglesTest {

    @Test
    fun `dmsToDecimal y decimalToDms son inversas`() {
        val dms = Dms(59, 59, 50.0)
        val decimal = dmsToDecimal(dms)
        assertApprox(59.99722, decimal, 1e-4)
        val back = decimalToDms(decimal)
        assertEquals(59, back.deg)
        assertEquals(59, back.min)
        assertApprox(50.0, back.sec, 0.1)
    }

    @Test
    fun `normalizeAzimuth envuelve a 0-360`() {
        assertApprox(10.0, normalizeAzimuth(370.0))
        assertApprox(350.0, normalizeAzimuth(-10.0))
        assertApprox(0.0, normalizeAzimuth(360.0))
    }

    @Test
    fun `bearingToAzimuth convierte los 4 cuadrantes`() {
        assertApprox(30.0, bearingToAzimuth("N30E")!!)
        assertApprox(150.0, bearingToAzimuth("S30E")!!)
        assertApprox(210.0, bearingToAzimuth("S30W")!!)
        assertApprox(330.0, bearingToAzimuth("N30W")!!)
    }

    @Test
    fun `azimuthToBearing elige el cuadrante NS-EW correcto`() {
        assertTrue(azimuthToBearing(15.0).startsWith("N") && azimuthToBearing(15.0).endsWith("E"))
        assertTrue(azimuthToBearing(95.0).startsWith("S") && azimuthToBearing(95.0).endsWith("E"))
        assertTrue(azimuthToBearing(190.0).startsWith("S") && azimuthToBearing(190.0).endsWith("W"))
        assertTrue(azimuthToBearing(300.0).startsWith("N") && azimuthToBearing(300.0).endsWith("W"))
    }
}
