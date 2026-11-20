package com.toposcribe.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContoursTest {

    private val pA = ElevatedPoint(1, 0.0, 0.0, 100.0)
    private val pB = ElevatedPoint(2, 10.0, 0.0, 110.0)

    @Test
    fun `interpolateEdgeAtLevel -- interpola linealmente el cruce del nivel 105 en el punto medio`() {
        val cross = interpolateEdgeAtLevel(pA, pB, 105.0)
        assertNotNull(cross)
        assertApprox(5.0, cross!!.east, 1e-6)
        assertApprox(0.0, cross.north, 1e-6)
    }

    @Test
    fun `interpolateEdgeAtLevel -- devuelve null si el nivel esta fuera del rango de la arista`() {
        assertNull(interpolateEdgeAtLevel(pA, pB, 120.0))
        assertNull(interpolateEdgeAtLevel(pA, pB, 90.0))
    }

    @Test
    fun `interpolateEdgeAtLevel -- devuelve null si ambos extremos tienen la misma cota`() {
        assertNull(interpolateEdgeAtLevel(pA, pB.copy(elevation = 100.0), 100.0))
    }

    @Test
    fun `computeContours -- no es aplicable con menos de 4 puntos`() {
        val result = computeContours(
            listOf(
                ElevatedPoint(1, 0.0, 0.0, 100.0),
                ElevatedPoint(2, 10.0, 0.0, 101.0),
                ElevatedPoint(3, 5.0, 10.0, 102.0),
            ),
            1.0,
        )
        assertFalse(result.applicable)
        assertTrue(result.reason!!.isNotBlank())
    }

    @Test
    fun `computeContours -- genera segmentos para un cuadrado simple de 4 puntos con cotas conocidas`() {
        val points = listOf(
            ElevatedPoint(1, 0.0, 0.0, 100.0),
            ElevatedPoint(2, 10.0, 0.0, 100.0),
            ElevatedPoint(3, 10.0, 10.0, 110.0),
            ElevatedPoint(4, 0.0, 10.0, 110.0),
        )
        val result = computeContours(points, 5.0)

        assertTrue(result.applicable)
        assertApprox(5.0, result.equidistance, 1e-9)
        assertEquals(listOf(100.0, 105.0, 110.0), result.levels)
        assertTrue(result.segments.isNotEmpty())
        assertTrue(result.segments.any { it.level == 105.0 })
    }
}
