package com.toposcribe.calc

import org.junit.Test

class PolarTest {

    @Test
    fun `caso A1 -- Az=45 Dh=100 desde E0=N0=1000`() {
        val result = computePolarCoordinates(PolarCoordinatesInput(1000.0, 1000.0, 45.0, 100.0))
        assertApprox(1070.711, result.east)
        assertApprox(1070.711, result.north)
    }

    @Test
    fun `caso A2 -- reduccion zenital Ds=50 Z=80 hi=1,5 ht=1,2`() {
        val dh = horizontalDistanceFromZenith(50.0, 80.0)
        val deltaElev = elevationDiffFromZenith(50.0, 80.0, 1.5, 1.2)
        assertApprox(49.240, dh)
        assertApprox(8.982, deltaElev)
    }

    @Test
    fun `caso A3 -- rumbo S30E distancia 80`() {
        val azimuth = bearingToAzimuth("S30E")
        assertApprox(150.0, azimuth!!)
        val result = computePolarCoordinates(PolarCoordinatesInput(0.0, 0.0, azimuth, 80.0))
        assertApprox(40.000, result.east)
        assertApprox(-69.282, result.north)
    }

    @Test
    fun `caso verificado del usuario -- E1=500 N1=1000 d=10 Az=90 produce E2=510 N2=1000`() {
        val result = computePolarCoordinates(PolarCoordinatesInput(500.0, 1000.0, 90.0, 10.0))
        assertApprox(510.000, result.east)
        assertApprox(1000.000, result.north)
    }
}
