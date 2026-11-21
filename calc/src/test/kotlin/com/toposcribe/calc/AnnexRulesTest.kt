package com.toposcribe.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnexRulesTest {

    @Test
    fun `acepta croquis imagenes PDF y documentos de respaldo`() {
        listOf("croquis.jpg", "foto.PNG", "ficha_campo.pdf", "libreta.xlsx", "plano.dxf", "notas.txt").forEach {
            assertNull(it, AnnexRules.validate(it, 1024))
        }
    }

    @Test
    fun `rechaza tipos no permitidos con un mensaje que lista los permitidos`() {
        val msg = AnnexRules.validate("virus.exe", 1000)
        assertNotNull(msg)
        assertTrue(msg!!.contains(".exe") && msg.contains("pdf"))
        assertNotNull(AnnexRules.validate("sin_extension", 10))
    }

    @Test
    fun `rechaza archivos vacios o mayores al maximo`() {
        assertEquals("El archivo está vacío.", AnnexRules.validate("a.pdf", 0))
        val tooBig = AnnexRules.validate("a.pdf", AnnexRules.MAX_BYTES + 1)
        assertNotNull(tooBig)
        assertTrue(tooBig!!.contains("25 MB"))
        assertNull(AnnexRules.validate("a.pdf", AnnexRules.MAX_BYTES))
    }

    @Test
    fun `clasifica imagen PDF y otros para decidir que se incorpora al informe`() {
        assertEquals(AnnexFileKind.IMAGE, AnnexRules.kindOf(AnnexRules.mimeFor("a.jpg")!!))
        assertEquals(AnnexFileKind.PDF, AnnexRules.kindOf(AnnexRules.mimeFor("a.pdf")!!))
        assertEquals(AnnexFileKind.OTHER, AnnexRules.kindOf(AnnexRules.mimeFor("a.xlsx")!!))
        // un DXF es image/vnd.dxf pero NO es una imagen que se pueda incrustar
        assertEquals(AnnexFileKind.OTHER, AnnexRules.kindOf(AnnexRules.mimeFor("plano.dxf")!!))
    }
}
