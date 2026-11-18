package com.toposcribe.calc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BowditchTest {

    private val legDeltasB2 = listOf(
        LegDelta(deltaE = 300.0, deltaN = 0.0, horizontalDistance = 300.0),
        LegDelta(deltaE = 0.0, deltaN = 400.0, horizontalDistance = 400.0),
        LegDelta(deltaE = -299.95, deltaN = -400.03, horizontalDistance = 500.06),
    )

    @Test
    fun `caso B2 -- aplica Bowditch y las correcciones suman -fE -fN, cierra exacto en el punto de partida`() {
        val closure = computeLinearClosure(legDeltasB2, 1000.0, 1000.0, 1000.0, 1000.0)
        val bowditch = applyBowditch(legDeltasB2, closure.fE, closure.fN, closure.perimeter, 1000.0, 1000.0)

        assertApprox(-0.0125, bowditch.corrections[0].correctionE, 1e-3)
        assertApprox(0.0075, bowditch.corrections[0].correctionN, 1e-3)
        assertApprox(-0.0167, bowditch.corrections[1].correctionE, 1e-3)
        assertApprox(0.01, bowditch.corrections[1].correctionN, 1e-3)
        assertApprox(-0.0208, bowditch.corrections[2].correctionE, 1e-3)
        assertApprox(0.0125, bowditch.corrections[2].correctionN, 1e-3)

        val sumCorrE = bowditch.corrections.sumOf { it.correctionE }
        val sumCorrN = bowditch.corrections.sumOf { it.correctionN }
        assertApprox(-closure.fE, sumCorrE, 1e-6)
        assertApprox(-closure.fN, sumCorrN, 1e-6)

        // Poligonal cerrada: el último punto ajustado vuelve exactamente al punto de partida.
        val last = bowditch.adjustedStations.last()
        assertApprox(1000.0, last.east, 1e-6)
        assertApprox(1000.0, last.north, 1e-6)
    }

    @Test
    fun `poligonal cerrada -- siempre aplicable`() {
        val r = checkBowditchApplicability(BowditchApplicabilityInput(TraverseType.CERRADA_POLIGONO, bothEndsKnown = false))
        assertTrue(r.applicable)
    }

    @Test
    fun `poligonal abierta libre -- nunca aplicable, con motivo explicito`() {
        val r = checkBowditchApplicability(BowditchApplicabilityInput(TraverseType.ABIERTA_LIBRE, bothEndsKnown = false))
        assertFalse(r.applicable)
        assertTrue(r.reason!!.contains("no tiene un punto de cierre conocido"))
    }

    @Test
    fun `poligonal abierta amarrada con ambos extremos conocidos -- aplicable`() {
        val r = checkBowditchApplicability(BowditchApplicabilityInput(TraverseType.ABIERTA_AMARRADA, bothEndsKnown = true))
        assertTrue(r.applicable)
    }

    @Test
    fun `poligonal abierta amarrada sin ambos extremos conocidos -- no aplicable, con motivo`() {
        val r = checkBowditchApplicability(BowditchApplicabilityInput(TraverseType.ABIERTA_AMARRADA, bothEndsKnown = false))
        assertFalse(r.applicable)
        assertTrue(r.reason!!.isNotBlank())
    }
}
