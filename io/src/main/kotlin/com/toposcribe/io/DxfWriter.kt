package com.toposcribe.io

import com.toposcribe.calc.PlanData

/**
 * Escritor DXF ASCII R12 minimalista y sin dependencias externas: el subconjunto de entidades que
 * necesitamos (POINT, LINE, TEXT) es pequeño y el formato de grupo de códigos de R12 es estable
 * desde hace décadas, por lo que no se justifica traer una librería de terceros para esto.
 */
private class DxfBuilder {
    private val lines = mutableListOf<String>()

    fun code(code: Int, value: String): DxfBuilder {
        lines.add(code.toString())
        lines.add(value)
        return this
    }

    fun code(code: Int, value: Double): DxfBuilder = code(code, formatNumber(value))
    fun code(code: Int, value: Int): DxfBuilder = code(code, value.toString())

    private fun formatNumber(value: Double): String {
        // Evita notación científica y ceros de más, manteniendo precisión decimal razonable.
        return if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }

    fun section(name: String, body: DxfBuilder.() -> Unit): DxfBuilder {
        code(0, "SECTION").code(2, name)
        body()
        code(0, "ENDSEC")
        return this
    }

    fun build(): String {
        code(0, "EOF")
        return lines.joinToString("\n")
    }
}

private fun insUnitsFor(unitsDistance: String): Int = if (unitsDistance == "m") 6 else 2 // $INSUNITS: 6=metros, 2=pies

fun buildProjectDxf(planData: PlanData, unitsDistance: String): String {
    val writer = DxfBuilder()

    writer.section("HEADER") {
        code(9, "\$INSUNITS").code(70, insUnitsFor(unitsDistance))
    }

    writer.section("TABLES") {
        code(0, "TABLE").code(2, "LAYER").code(70, 3)
        val layers = listOf("V-TOP-PUNTOS" to 7, "RADIACION" to 3, "V-TOP-POLIG" to 5)
        for ((name, color) in layers) {
            code(0, "LAYER").code(2, name).code(70, 0).code(62, color).code(6, "CONTINUOUS")
        }
        code(0, "ENDTAB")
    }

    writer.section("ENTITIES") {
        val pointById = planData.points.associateBy { it.pointId }

        for (line in planData.lines) {
            val a = pointById[line.fromPointId] ?: continue
            val b = pointById[line.toPointId] ?: continue
            val layer = if (line.kind == "poligonal") "V-TOP-POLIG" else "RADIACION"
            code(0, "LINE").code(8, layer)
                .code(10, a.east).code(20, a.north).code(30, 0.0)
                .code(11, b.east).code(21, b.north).code(31, 0.0)
        }

        for (p in planData.points) {
            code(0, "POINT").code(8, "V-TOP-PUNTOS").code(10, p.east).code(20, p.north).code(30, 0.0)
            code(0, "TEXT").code(8, "V-TOP-PUNTOS")
                .code(10, p.east).code(20, p.north).code(30, 0.0)
                .code(40, 0.5).code(1, p.code)
        }
    }

    return writer.build()
}
