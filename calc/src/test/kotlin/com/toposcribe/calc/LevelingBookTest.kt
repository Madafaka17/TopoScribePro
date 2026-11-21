package com.toposcribe.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Distancias acumuladas, cambio de estación, perfil longitudinal y huellas de desactualización. */
class LevelingBookTest {

    private fun obs(id: Long, seq: Int, pt: Long, t: ReadingType, v: Double, tp: Boolean = false, d: Double? = null) =
        LevelingObservationCalcInput(id, seq, pt, t, v, tp, d)

    // BM(1)=100.000 --- E1: BS 1.250 ; FS TP1(2) 0.850 a 20 m ---
    // E2: BS 1.100 sobre TP1 ; IS punto 3 a 15 m ; FS BM2 (4) a 25 m
    private val book = listOf(
        obs(1, 1, 1, ReadingType.BS, 1.250),
        obs(2, 2, 2, ReadingType.FS, 0.850, tp = true, d = 20.0),
        obs(3, 3, 2, ReadingType.BS, 1.100),
        obs(4, 4, 3, ReadingType.IS, 1.500, d = 15.0),
        obs(5, 5, 4, ReadingType.FS, 0.700, d = 25.0),
    )

    @Test
    fun `cambio de estacion -- la segunda vista atras parte de la cota del punto de cambio`() {
        val r = computeDifferentialLeveling(book, 100.0)
        val rows = r.rows.associateBy { it.observationId }
        assertApprox(101.250, rows.getValue(1).heightOfInstrument, 1e-9)
        assertApprox(100.400, rows.getValue(2).elevation, 1e-9)
        assertApprox(101.500, rows.getValue(3).heightOfInstrument, 1e-9) // 100.400 + 1.100
        assertApprox(100.000, rows.getValue(4).elevation, 1e-9)          // 101.500 - 1.500
        assertApprox(100.800, rows.getValue(5).elevation, 1e-9)          // 101.500 - 0.700
        assertEquals(1, rows.getValue(1).stationNumber)
        assertEquals(2, rows.getValue(3).stationNumber)
        assertEquals(2, rows.getValue(5).stationNumber)
    }

    @Test
    fun `comprobacion aritmetica -- suma VA menos suma VD igual a diferencia de cotas`() {
        val r = computeDifferentialLeveling(book, 100.0)
        assertApprox(2.350, r.sumBacksight, 1e-9)
        assertApprox(1.550, r.sumForesight, 1e-9)
        assertApprox(0.800, r.checkValue, 1e-9)
        assertApprox(100.800, r.finalElevation, 1e-9)
        assertTrue(r.arithmeticCheckOk)
    }

    @Test
    fun `distancia parcial y acumulada se propagan en los puntos de cambio`() {
        val r = computeDifferentialLeveling(book, 100.0)
        val rows = r.rows.associateBy { it.observationId }
        assertApprox(20.0, rows.getValue(2).cumulativeDistance!!, 1e-9)
        assertApprox(20.0, rows.getValue(3).cumulativeDistance!!, 1e-9) // BS sobre el punto de cambio: no suma
        assertApprox(35.0, rows.getValue(4).cumulativeDistance!!, 1e-9)
        assertApprox(60.0, rows.getValue(5).cumulativeDistance!!, 1e-9)
    }

    @Test
    fun `las operaciones se muestran con valores sustituidos`() {
        val r = computeDifferentialLeveling(book, 100.0)
        assertEquals("HI = 100.000 + 1.250 = 101.250", r.rows[0].operation)
        assertEquals("Cota = 101.250 − 0.850 = 100.400", r.rows[1].operation)
    }

    @Test
    fun `una VD seguida de VA en el mismo punto se trata como punto de cambio aunque no este marcada`() {
        val unmarked = book.map { if (it.id == 2L) it.copy(isTurningPoint = false) else it }
        val r = computeDifferentialLeveling(unmarked, 100.0)
        assertTrue(r.arithmeticCheckOk)
        assertApprox(100.800, r.finalElevation, 1e-9)
    }

    @Test
    fun `VD seguida de VA en otro punto sin marcar genera advertencia y rompe la comprobacion`() {
        val broken = listOf(
            obs(1, 1, 1, ReadingType.BS, 1.250),
            obs(2, 2, 2, ReadingType.FS, 0.850),
            obs(3, 3, 9, ReadingType.BS, 1.100),
            obs(4, 4, 4, ReadingType.FS, 0.700),
        )
        val r = computeDifferentialLeveling(broken, 100.0)
        assertTrue(r.warnings.any { it.contains("punto de cambio") })
        assertFalse(r.arithmeticCheckOk)
    }

    @Test
    fun `la vista intermedia no participa en las sumas ni altera la cadena`() {
        val withoutIs = book.filter { it.readingType != ReadingType.IS }
        val a = computeDifferentialLeveling(book, 100.0)
        val b = computeDifferentialLeveling(withoutIs, 100.0)
        assertApprox(a.sumBacksight, b.sumBacksight, 1e-12)
        assertApprox(a.sumForesight, b.sumForesight, 1e-12)
        assertApprox(a.finalElevation, b.finalElevation, 1e-12)
    }

    @Test
    fun `error de cierre contra BM final de cota conocida`() {
        val r = computeDifferentialLeveling(book, 100.0)
        assertApprox(0.005, computeFieldMisclosure(r.finalElevation, 100.795), 1e-9)
    }

    // ---- perfil longitudinal ----
    private val codes = mapOf(1L to "BM1", 2L to "TP1", 3L to "P3", 4L to "BM2")

    @Test
    fun `perfil valido -- distancia acumulada y cota con todos los puntos`() {
        val rows = computeDifferentialLeveling(book, 100.0).rows
        val p = buildLevelingProfile(rows, codes)
        assertTrue(p.reason, p.valid)
        assertEquals(listOf("BM1", "TP1", "P3", "BM2"), p.points.map { it.code })
        assertEquals(listOf(0.0, 20.0, 35.0, 60.0), p.points.map { it.cumulativeDistance })
        assertApprox(60.0, p.totalLength, 1e-9)
        assertApprox(100.0, p.minElevation, 1e-9)
        assertApprox(100.8, p.maxElevation, 1e-9)
    }

    @Test
    fun `perfil invalido si falta una distancia -- lista los puntos incompletos y no dibuja`() {
        val missing = book.map { if (it.id == 4L) it.copy(partialDistance = null) else it }
        val rows = computeDifferentialLeveling(missing, 100.0).rows
        val p = buildLevelingProfile(rows, codes)
        assertFalse(p.valid)
        assertEquals(listOf("P3"), p.incomplete.map { it.code })
        assertTrue(p.incomplete.single().missing.contains("distancia"))
        assertTrue(p.reason!!.contains("incompletos"))
    }

    @Test
    fun `perfil invalido sin lecturas`() {
        val p = buildLevelingProfile(emptyList(), codes)
        assertFalse(p.valid)
    }

    @Test
    fun `escala redonda para el perfil`() {
        // 60 m en 170 mm de papel => 1:353 -> 1:500
        assertEquals(500.0, niceScaleDenominator(60.0, 170.0), 1e-9)
        // 0.8 m de relieve en 60 mm => 1:13.3 -> 1:20
        assertEquals(20.0, niceScaleDenominator(0.8, 60.0), 1e-9)
    }

    // ---- desactualizacion ----
    @Test
    fun `la huella cambia si se edita cualquier lectura y el estado pasa a desactualizado`() {
        val before = inputFingerprint(book.map { "${it.id}|${it.readingValue}|${it.partialDistance}" })
        val edited = book.map { if (it.id == 2L) it.copy(readingValue = 0.860) else it }
        val after = inputFingerprint(edited.map { "${it.id}|${it.readingValue}|${it.partialDistance}" })
        assertEquals(ResultState.SIN_CALCULAR, resultState(null, before))
        assertEquals(ResultState.ACTUALIZADO, resultState(before, before))
        assertEquals(ResultState.DESACTUALIZADO, resultState(before, after))
    }
}
