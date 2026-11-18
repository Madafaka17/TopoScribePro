package com.toposcribe.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {

    private val axis = listOf(
        AxisPoint(1, 0.0, 0.0),
        AxisPoint(2, 100.0, 0.0),
    )

    @Test
    fun `calcula progresivas a lo largo de un eje recto e incluye puntos dentro de la tolerancia`() {
        val points = listOf(
            ProfileCandidate(1, 0.0, 0.0, 10.0),
            ProfileCandidate(2, 50.0, 5.0, 12.0), // offset=5
            ProfileCandidate(3, 100.0, 0.0, 8.0),
            ProfileCandidate(4, 50.0, 30.0, 99.0), // offset=30, fuera de tolerancia
        )
        val result = computeProfile(axis, points, 10.0)

        assertTrue(result.applicable)
        assertEquals(listOf(1L, 2L, 3L), result.samples.map { it.pointId })
        assertApprox(0.0, result.samples[0].chainage, 1e-6)
        assertApprox(50.0, result.samples[1].chainage, 1e-6)
        assertApprox(5.0, result.samples[1].offset, 1e-6)
        assertApprox(100.0, result.samples[2].chainage, 1e-6)
        assertApprox(100.0, result.totalLength, 1e-6)
    }

    @Test
    fun `indica no aplicable si el eje tiene menos de 2 vertices`() {
        val result = computeProfile(listOf(AxisPoint(1, 0.0, 0.0)), emptyList(), 10.0)
        assertFalse(result.applicable)
        assertTrue(result.reason!!.isNotBlank())
    }

    @Test
    fun `indica no aplicable si hay menos de 2 puntos con cota cerca del eje`() {
        val result = computeProfile(axis, listOf(ProfileCandidate(1, 0.0, 0.0, 10.0)), 10.0)
        assertFalse(result.applicable)
    }

    @Test
    fun `ignora puntos sin cota conocida`() {
        val result = computeProfile(
            axis,
            listOf(
                ProfileCandidate(1, 0.0, 0.0, 10.0),
                ProfileCandidate(2, 50.0, 0.0, null),
                ProfileCandidate(3, 100.0, 0.0, 8.0),
            ),
            10.0,
        )
        assertTrue(result.applicable)
        assertEquals(listOf(1L, 3L), result.samples.map { it.pointId })
    }
}
