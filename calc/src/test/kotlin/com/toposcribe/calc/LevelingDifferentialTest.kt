package com.toposcribe.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelingDifferentialTest {

    // Caso D1: BM1=100.000 -> ... -> BM2=99.500, 3 cambios de estación.
    // Puntos: 1=BM1, 2=TP1, 3=X (vista intermedia), 4=TP2, 5=BM2
    private val caseD1 = listOf(
        LevelingObservationCalcInput(1, 1, 1, ReadingType.BS, 1.5, false),
        LevelingObservationCalcInput(2, 2, 2, ReadingType.FS, 1.2, true),
        LevelingObservationCalcInput(3, 3, 2, ReadingType.BS, 1.1, false),
        LevelingObservationCalcInput(4, 4, 3, ReadingType.IS, 1.0, false),
        LevelingObservationCalcInput(5, 5, 4, ReadingType.FS, 1.8, true),
        LevelingObservationCalcInput(6, 6, 4, ReadingType.BS, 1.6, false),
        LevelingObservationCalcInput(7, 7, 5, ReadingType.FS, 1.7, false),
    )

    @Test
    fun `caso D1 -- calcula las cotas intermedias correctamente`() {
        val result = computeDifferentialLeveling(caseD1, 100.0)
        val byObsId = result.rows.associateBy { it.observationId }

        assertApprox(100.3, byObsId.getValue(2).elevation, 1e-6) // TP1
        assertApprox(101.4, byObsId.getValue(3).heightOfInstrument, 1e-6)
        assertApprox(100.4, byObsId.getValue(4).elevation, 1e-6) // X, vista intermedia
        assertApprox(99.6, byObsId.getValue(5).elevation, 1e-6) // TP2
        assertApprox(101.2, byObsId.getValue(6).heightOfInstrument, 1e-6)
        assertApprox(99.5, byObsId.getValue(7).elevation, 1e-6) // BM2
    }

    @Test
    fun `caso D1 -- suma correctamente vistas atras y adelante`() {
        val result = computeDifferentialLeveling(caseD1, 100.0)
        assertApprox(4.2, result.sumBacksight, 1e-6)
        assertApprox(4.7, result.sumForesight, 1e-6)
    }

    @Test
    fun `caso D1 -- cumple la comprobacion de la libreta SumaBS-SumaFS = cota final - cota inicial`() {
        val result = computeDifferentialLeveling(caseD1, 100.0)
        assertApprox(-0.5, result.checkValue, 1e-6)
        assertApprox(-0.5, result.finalElevation - result.startElevation, 1e-6)
        assertTrue(result.arithmeticCheckOk)
        assertApprox(99.5, result.finalElevation, 1e-6)
    }

    @Test
    fun `caso D1 -- no genera advertencias para una libreta bien formada`() {
        val result = computeDifferentialLeveling(caseD1, 100.0)
        assertEquals(0, result.warnings.size)
    }

    @Test
    fun `detecta cuando falta una vista atras inicial`() {
        val broken = computeDifferentialLeveling(
            listOf(LevelingObservationCalcInput(99, 1, 1, ReadingType.FS, 1.0, false)),
            100.0,
        )
        assertTrue(broken.warnings.isNotEmpty())
    }

    @Test
    fun `caso de la especificacion -- BM=100000 BS=1250 FS=0850 produce HI=101250 y nueva cota=100400`() {
        val result = computeDifferentialLeveling(
            listOf(
                LevelingObservationCalcInput(1, 1, 1, ReadingType.BS, 1.25, false),
                LevelingObservationCalcInput(2, 2, 2, ReadingType.FS, 0.85, false),
            ),
            100.0,
        )
        val hiRow = result.rows.first { it.observationId == 1L }
        val elevRow = result.rows.first { it.observationId == 2L }
        assertApprox(101.25, hiRow.heightOfInstrument, 1e-6)
        assertApprox(100.4, elevRow.elevation, 1e-6)
    }

    @Test
    fun `computeFieldMisclosure devuelve la diferencia entre la cota calculada y una cota de cierre conocida`() {
        assertApprox(0.0, computeFieldMisclosure(100.4, 100.4), 1e-9)
        assertApprox(0.012, computeFieldMisclosure(100.412, 100.4), 1e-6)
    }
}
