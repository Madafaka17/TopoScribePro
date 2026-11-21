package com.toposcribe.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolarTest {

    @Test
    fun `caso A1 -- Az=45 Dh=100 desde E0=N0=1000`() {
        val result = computePolarCoordinates(PolarCoordinatesInput(1000.0, 1000.0, 45.0, 100.0))
        assertApprox(1070.711, result.east)
        assertApprox(1070.711, result.north)
    }

    @Test
    fun `reduccion zenital Ds=50 Z=80 da Dh=49,240`() {
        assertApprox(49.240, horizontalDistanceFromZenith(50.0, 80.0))
    }

    @Test
    fun `reduccion por angulo vertical Ds=50 alfa=10 da Dh=49,240`() {
        assertApprox(49.240, horizontalDistanceFromVerticalAngle(50.0, 10.0))
    }

    @Test
    fun `caso A3 -- rumbo S30E distancia 80`() {
        val azimuth = bearingToAzimuth("S30E")
        assertApprox(150.0, azimuth!!)
        val result = computePolarCoordinates(PolarCoordinatesInput(0.0, 0.0, azimuth, 80.0))
        assertApprox(40.000, result.east)
        assertApprox(-69.282, result.north)
    }

    // ---- CASO DE LA ESPECIFICACION: E=500, N=1000, d=10, Az=90 -> E=510, N=1000 ----
    private val northOriented = StationOrientation(0.0, 0.0, AngleConvention.HORARIO)

    @Test
    fun `especificacion -- estacion 500,1000 distancia 10 azimut 90 produce 510,1000`() {
        val out = computeRadiation(RadiationInput(500.0, 1000.0, northOriented, HorizontalKind.AZIMUT, 90.0, 10.0, DistanceKind.HORIZONTAL))
        val s = out.step!!
        assertApprox(510.000, s.east, 1e-9)
        assertApprox(1000.000, s.north, 1e-9)
        assertApprox(10.0, s.deltaE, 1e-9)
        assertApprox(0.0, s.deltaN, 1e-9)
        assertTrue(s.eastFormula, s.eastFormula.contains("500.000 + 10.000 = 510.000"))
        assertTrue(s.deltaEFormula, s.deltaEFormula.contains("10.000 × sen(90°00'00.00\")"))
    }

    @Test
    fun `azimut mayor que 360 o negativo se normaliza entre 0 y 360`() {
        val a = computeRadiation(RadiationInput(0.0, 0.0, northOriented, HorizontalKind.AZIMUT, 360.0, 10.0, DistanceKind.HORIZONTAL)).step!!
        assertApprox(0.0, a.azimuthDeg, 1e-9)
        assertApprox(10.0, a.north, 1e-9)
        // 450° es un azimut inválido (se rechaza); -90° tambien
        assertNull(computeRadiation(RadiationInput(0.0, 0.0, northOriented, HorizontalKind.AZIMUT, 450.0, 10.0, DistanceKind.HORIZONTAL)).step)
        assertNull(computeRadiation(RadiationInput(0.0, 0.0, northOriented, HorizontalKind.AZIMUT, -90.0, 10.0, DistanceKind.HORIZONTAL)).step)
    }

    @Test
    fun `orientacion horaria -- lectura 30 con referencia Az=60 lectura 0 da azimut 90`() {
        val o = StationOrientation(60.0, 0.0, AngleConvention.HORARIO)
        val s = computeRadiation(RadiationInput(500.0, 1000.0, o, HorizontalKind.LECTURA_HORIZONTAL, 30.0, 10.0, DistanceKind.HORIZONTAL)).step!!
        assertApprox(90.0, s.azimuthDeg, 1e-9)
        assertApprox(510.0, s.east, 1e-9)
    }

    @Test
    fun `orientacion con la referencia leida en un valor distinto de cero`() {
        val o = StationOrientation(100.0, 45.0, AngleConvention.HORARIO)
        // lectura 145 => 100 deg mas que la referencia => Az = 200
        assertApprox(200.0, azimuthFromReading(145.0, o), 1e-9)
        // lectura 10 => (10-45) = -35 => Az 65
        assertApprox(65.0, azimuthFromReading(10.0, o), 1e-9)
    }

    @Test
    fun `convenio antihorario invierte el sentido de la lectura`() {
        val o = StationOrientation(90.0, 0.0, AngleConvention.ANTIHORARIO)
        assertApprox(60.0, azimuthFromReading(30.0, o), 1e-9)
        assertApprox(350.0, azimuthFromReading(100.0, o), 1e-9) // 90-100=-10 -> 350
    }

    @Test
    fun `distancia inclinada con cenital se reduce a horizontal antes de proyectar`() {
        val s = computeRadiation(
            RadiationInput(0.0, 0.0, northOriented, HorizontalKind.AZIMUT, 0.0, 50.0, DistanceKind.INCLINADA, VerticalKind.CENITAL, 80.0),
        ).step!!
        assertApprox(49.240, s.horizontalDistance)
        assertApprox(49.240, s.north)
        assertTrue(s.distanceFormula.contains("sen(Z)"))
    }

    @Test
    fun `distancia inclinada con angulo vertical`() {
        val s = computeRadiation(
            RadiationInput(0.0, 0.0, northOriented, HorizontalKind.AZIMUT, 90.0, 50.0, DistanceKind.INCLINADA, VerticalKind.VERTICAL, 10.0),
        ).step!!
        assertApprox(49.240, s.east)
    }

    @Test
    fun `distancia inclinada sin angulo vertical es un error y no produce resultado`() {
        val out = computeRadiation(RadiationInput(0.0, 0.0, northOriented, HorizontalKind.AZIMUT, 90.0, 50.0, DistanceKind.INCLINADA))
        assertNull(out.step)
        assertTrue(out.errors.single().contains("vertical"))
    }

    @Test
    fun `cenital fuera de rango y distancia no positiva se rechazan`() {
        assertNull(computeRadiation(RadiationInput(0.0, 0.0, northOriented, HorizontalKind.AZIMUT, 0.0, 10.0, DistanceKind.INCLINADA, VerticalKind.CENITAL, 0.0)).step)
        assertNull(computeRadiation(RadiationInput(0.0, 0.0, northOriented, HorizontalKind.AZIMUT, 0.0, 10.0, DistanceKind.INCLINADA, VerticalKind.CENITAL, 181.0)).step)
        assertNull(computeRadiation(RadiationInput(0.0, 0.0, northOriented, HorizontalKind.AZIMUT, 0.0, 0.0, DistanceKind.HORIZONTAL)).step)
    }

    @Test
    fun `distancias y azimut entre dos puntos calculados`() {
        assertApprox(5.0, planDistance(0.0, 0.0, 3.0, 4.0))
        assertApprox(90.0, planAzimuth(0.0, 0.0, 10.0, 0.0))
        assertApprox(180.0, planAzimuth(0.0, 0.0, 0.0, -10.0))
        assertApprox(270.0, planAzimuth(0.0, 0.0, -10.0, 0.0))
    }

    @Test
    fun `parseAngleText acepta decimal y grados minutos segundos y rechaza invalidos`() {
        assertEquals(90.5, parseAngleText("90,5", "Az").degrees!!, 1e-9)
        assertEquals(90.504166666, parseAngleText("90 30 15", "Az").degrees!!, 1e-6)
        assertEquals(90.504166666, parseAngleText("90°30'15\"", "Az").degrees!!, 1e-6)
        assertNotNull(parseAngleText("90 75 00", "Az").error)
        assertNotNull(parseAngleText("abc", "Az").error)
        assertNotNull(parseAngleText("", "Az").error)
    }

    @Test
    fun `formatDegreesAsDms propaga el acarreo y nunca imprime 60 segundos`() {
        assertEquals("90°00'00.00\"", formatDegreesAsDms(90.0))
        assertEquals("1°00'00.00\"", formatDegreesAsDms(1.0 - 1e-9))
        assertEquals("59°59'59.00\"", formatDegreesAsDms(59.0 + 59.0 / 60 + 59.0 / 3600))
    }
}
