package com.toposcribe.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DxfTest {

    private fun model(withContours: Boolean): PlanModel {
        val pts = listOf(
            PlanPoint(1, "EST-1", "Estación", 500.0, 1000.0, 100.0, ElevationSource.CONTROL_CONOCIDO, true),
            PlanPoint(2, "P1", "Árbol ñandú", 510.0, 1000.0, 101.0, ElevationSource.NIVEL_INGENIERO, false),
            PlanPoint(3, "P2", null, 510.0, 1010.0, 103.0, ElevationSource.NIVEL_INGENIERO, false),
            PlanPoint(4, "P3", null, 500.0, 1010.0, 102.0, ElevationSource.NIVEL_INGENIERO, false),
            PlanPoint(5, "P4", null, 505.0, 1005.0, null, ElevationSource.NINGUNA, false),
        )
        val contours = if (withContours) {
            computeContours(pts.filter { it.elevation != null }.map { ElevatedPoint(it.pointId, it.east, it.north, it.elevation!!) }, 0.5)
        } else null
        return PlanModel(
            "Proyecto de prueba", "Local", "m", pts,
            listOf(PlanRadiation(1, 2), PlanRadiation(1, 3), PlanRadiation(1, 4), PlanRadiation(1, 5)),
            listOf(PlanPolyline("Cerco", listOf(2, 3, 4), true)),
            contours, 0,
        )
    }

    @Test
    fun `el DXF contiene las capas diferenciadas y verifica contra el plano`() {
        val m = model(true)
        val layers = m.availableLayers()
        val dxf = buildProjectDxf(m, layers)
        val v = verifyDxf(dxf, m, layers)
        assertTrue(v.summary(), v.ok)
        val used = parseDxfEntities(dxf).map { it.layer }.toSet()
        assertTrue(used.containsAll(listOf("TOPO-PUNTOS", "TOPO-CODIGOS", "TOPO-ESTACION", "TOPO-RADIACIONES", "TOPO-LINEAS", "TOPO-COTAS", "TOPO-TRIANGULACION", "TOPO-CURVAS")))
    }

    @Test
    fun `las coordenadas del DXF coinciden con las del plano`() {
        val m = model(false)
        val dxf = buildProjectDxf(m, m.availableLayers())
        val points = parseDxfEntities(dxf).filter { it.type == "POINT" }
        assertEquals(5, points.size)
        val p1 = points[1]
        assertEquals(510.0, p1.coords[0], 1e-9); assertEquals(1000.0, p1.coords[1], 1e-9); assertEquals(101.0, p1.coords[2], 1e-9)
        assertTrue(dxf.contains("\$INSUNITS\n70\n6\n"))
        assertTrue(dxf.contains("AC1009"))
    }

    @Test
    fun `sin curvas calculadas no hay capas de triangulacion ni de curvas`() {
        val m = model(false)
        assertFalse(PlanLayer.CURVAS in m.availableLayers())
        assertFalse(PlanLayer.TRIANGULACION in m.availableLayers())
        val dxf = buildProjectDxf(m, PlanLayer.entries.toSet()) // aunque se pidan, no hay datos validos
        val used = parseDxfEntities(dxf).map { it.layer }.toSet()
        assertFalse(used.any { it.startsWith("TOPO-CURVAS") || it == "TOPO-TRIANGULACION" })
    }

    @Test
    fun `solo se exportan las capas activas`() {
        val m = model(true)
        val dxf = buildProjectDxf(m, setOf(PlanLayer.PUNTOS))
        val used = parseDxfEntities(dxf).map { it.layer }.toSet()
        assertEquals(setOf("TOPO-PUNTOS"), used)
        assertTrue(verifyDxf(dxf, m, setOf(PlanLayer.PUNTOS)).ok)
    }

    @Test
    fun `un DXF alterado no pasa la verificacion`() {
        val m = model(false)
        val layers = m.availableLayers()
        val dxf = buildProjectDxf(m, layers).replace("510.000000\n20\n1000.000000", "511.000000\n20\n1000.000000")
        assertFalse(verifyDxf(dxf, m, layers).ok)
    }

    @Test
    fun `los numeros no usan notacion cientifica ni coma decimal`() {
        assertEquals("0.000100", DxfBuilder.num(1e-4))
        assertEquals("1234567.891000", DxfBuilder.num(1234567.891))
        assertEquals("0.000000", DxfBuilder.num(-1e-12))
    }

    /** Deja un DXF real en build/ para abrirlo con un lector independiente (ezdxf) fuera de la JVM. */
    @Test
    fun `escribe un DXF de muestra para verificacion externa`() {
        val m = model(true)
        val dxf = buildProjectDxf(m, m.availableLayers())
        val dir = java.io.File("build/dxf-sample").apply { mkdirs() }
        java.io.File(dir, "plano_muestra.dxf").writeBytes(dxf.toByteArray(charset("windows-1252")))
        assertTrue(java.io.File(dir, "plano_muestra.dxf").length() > 500)
    }
}