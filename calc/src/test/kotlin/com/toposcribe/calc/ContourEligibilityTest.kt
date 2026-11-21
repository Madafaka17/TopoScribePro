package com.toposcribe.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las curvas solo se generan con puntos que tienen E, N y cota validos de nivel o control. */
class ContourEligibilityTest {

    private fun cand(id: Long, code: String, e: Double?, n: Double?, z: Double?, src: ElevationSource = ElevationSource.NIVEL_INGENIERO) =
        ContourCandidate(id, code, e, n, z, if (z == null) ElevationSource.NINGUNA else src)

    private val square = listOf(
        cand(1, "A", 0.0, 0.0, 100.0),
        cand(2, "B", 10.0, 0.0, 101.0),
        cand(3, "C", 10.0, 10.0, 103.0),
        cand(4, "D", 0.0, 10.0, 102.0),
    )

    @Test
    fun `cuatro puntos con E N y cota validos son elegibles`() {
        val e = evaluateContourEligibility(square)
        assertTrue(e.issues.toString(), e.eligible)
        assertEquals(4, e.usable.size)
    }

    @Test
    fun `un punto radiado sin cota no se usa y NO se le asigna ninguna`() {
        val e = evaluateContourEligibility(square + cand(5, "R1", 5.0, 5.0, null))
        assertTrue(e.eligible)
        assertEquals(4, e.usable.size)
        assertEquals("R1", e.excluded.single().code)
        assertTrue(e.excluded.single().reason.contains("cota"))
    }

    @Test
    fun `solo con azimut y distancia (sin cotas) no se activa la generacion de curvas`() {
        val radiated = (1..6).map { cand(it.toLong(), "R$it", it * 3.0, it * 2.0 + (it % 2), null) }
        val e = evaluateContourEligibility(radiated)
        assertFalse(e.eligible)
        assertTrue(e.issues.first().contains("0 punto"))
        assertTrue(e.issues.any { it.contains("R1") }) // explica exactamente cuales faltan
    }

    @Test
    fun `tres puntos son insuficientes y se explica cuantos hay`() {
        val e = evaluateContourEligibility(square.take(3))
        assertFalse(e.eligible)
        assertTrue(e.issues.first().contains("3 punto"))
    }

    @Test
    fun `puntos alineados no forman triangulos`() {
        val line = (0..5).map { cand(it.toLong(), "L$it", it * 5.0, it * 5.0, 100.0 + it) }
        val e = evaluateContourEligibility(line)
        assertFalse(e.eligible)
        assertTrue(e.issues.any { it.contains("alineados") })
    }

    @Test
    fun `cotas iguales no tienen relieve`() {
        val flat = square.map { it.copy(elevation = 100.0) }
        assertFalse(evaluateContourEligibility(flat).eligible)
    }

    @Test
    fun `una cota de origen no permitido (trigonometrica) no cuenta`() {
        val mixed = square.take(3) + cand(4, "D", 0.0, 10.0, 102.0, ElevationSource.OTRO)
        val e = evaluateContourEligibility(mixed)
        assertFalse(e.eligible)
        assertTrue(e.excluded.single().reason.contains("nivel de ingeniero"))
    }

    @Test
    fun `control conocido y nivel se pueden combinar`() {
        val mixed = listOf(
            cand(1, "BM1", 0.0, 0.0, 100.0, ElevationSource.CONTROL_CONOCIDO),
            cand(2, "B", 10.0, 0.0, 101.0), cand(3, "C", 10.0, 10.0, 103.0), cand(4, "D", 0.0, 10.0, 102.0),
        )
        assertTrue(evaluateContourEligibility(mixed).eligible)
    }

    @Test
    fun `dos puntos en la misma posicion se reportan`() {
        val e = evaluateContourEligibility(square + cand(5, "A2", 0.0, 0.0, 100.5))
        assertEquals(4, e.usable.size)
        assertTrue(e.excluded.single().reason.contains("misma posición"))
    }

    @Test
    fun `curvas -- equidistancia grande sin niveles dentro del rango explica el motivo`() {
        val pts = evaluateContourEligibility(square).usable
        val r = computeContours(pts, 50.0) // rango 100..103: 100 es multiplo de 50 => hay un nivel
        assertTrue(r.applicable)
        val shifted = pts.map { it.copy(elevation = it.elevation + 10.3) } // 110.3..113.3 no contiene 150
        val none = computeContours(shifted, 50.0)
        assertFalse(none.applicable)
        assertTrue(none.reason!!, none.reason!!.contains("equidistancia"))
    }

    @Test
    fun `curvas -- el plano inclinado produce curvas rectas con cotas multiplo de la equidistancia`() {
        // z = 100 + 0.1*E -> el nivel 101 cruza en E=10, el 102 en E=20
        val pts = listOf(
            ElevatedPoint(1, 0.0, 0.0, 100.0), ElevatedPoint(2, 30.0, 0.0, 103.0),
            ElevatedPoint(3, 30.0, 30.0, 103.0), ElevatedPoint(4, 0.0, 30.0, 100.0), ElevatedPoint(5, 15.0, 15.0, 101.5),
        )
        val r = computeContours(pts, 1.0)
        assertTrue(r.applicable)
        assertEquals(listOf(100.0, 101.0, 102.0, 103.0), r.levels)
        val l101 = r.segments.filter { it.level == 101.0 }
        assertTrue(l101.isNotEmpty())
        assertTrue(l101.all { kotlin.math.abs(it.a.east - 10.0) < 1e-6 && kotlin.math.abs(it.b.east - 10.0) < 1e-6 })
        assertTrue(r.triangles.isNotEmpty())
        assertTrue(r.polylines.any { it.level == 101.0 })
    }

    @Test
    fun `el encadenado une los segmentos en una sola polilinea`() {
        val segs = listOf(
            ContourSegment(1.0, EdgePoint(0.0, 0.0), EdgePoint(1.0, 0.0)),
            ContourSegment(1.0, EdgePoint(2.0, 0.0), EdgePoint(1.0, 0.0)),
            ContourSegment(1.0, EdgePoint(2.0, 0.0), EdgePoint(3.0, 0.0)),
        )
        val chains = chainContourSegments(segs)
        assertEquals(1, chains.size)
        assertEquals(4, chains.single().points.size)
        assertApprox(3.0, polylineLength(chains.single()), 1e-9)
    }
}
