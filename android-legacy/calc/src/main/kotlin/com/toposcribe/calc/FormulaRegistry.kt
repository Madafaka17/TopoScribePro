package com.toposcribe.calc

data class FormulaEntry(
    val id: String,
    val labelEs: String,
    val formulaText: String,
    val reference: String,
)

/**
 * Registro centralizado de texto legible en español + fórmula + referencia por formulaId --
 * usado tanto por el panel "fórmulas con valores sustituidos" en pantalla como por el generador
 * de informe PDF (:io/PdfReportBuilder.kt). Mismos formulaId que produce el motor de cálculo.
 */
object FormulaRegistry {
    private val entries: Map<String, FormulaEntry> = listOf(
        FormulaEntry(
            id = "DATO-FIJO",
            labelEs = "Punto de control / dato fijo",
            formulaText = "Valor ingresado directamente",
            reference = "No proviene de una observación calculada: es un punto de control o BM con coordenadas/cota conocidas.",
        ),
        FormulaEntry(
            id = "DATO-FIJO-GNSS",
            labelEs = "Punto medido con GNSS/RTK",
            formulaText = "Valor leído directamente del receptor GNSS",
            reference = "Se distingue del punto de control oficial para trazar su origen; el estado de fijación (FIXED/FLOAT/DGPS/AUTONOMO) se registra tal como lo reportó el equipo.",
        ),
        FormulaEntry(
            id = "RADIACION",
            labelEs = "Radiación (coordenadas polares)",
            formulaText = "E1 = E0 + Dh·sin(Az)   N1 = N0 + Dh·cos(Az)",
            reference = "Topografía general -- método de radiación desde una estación conocida y orientada.",
        ),
        FormulaEntry(
            id = "REDUCCION-ZENITAL",
            labelEs = "Reducción de distancia inclinada (ángulo cenital)",
            formulaText = "Dh = Ds·sin(Z)   ΔElev = Ds·cos(Z) + hi - ht",
            reference = "Z = ángulo cenital medido desde la vertical.",
        ),
        FormulaEntry(
            id = "REDUCCION-VERTICAL",
            labelEs = "Reducción de distancia inclinada (ángulo vertical)",
            formulaText = "Dh = Ds·cos(α)   ΔElev = Ds·sin(α) + hi - ht",
            reference = "α = ángulo vertical medido desde la horizontal.",
        ),
        FormulaEntry(
            id = "NIVELACION-DIFERENCIAL",
            labelEs = "Nivelación geométrica/diferencial",
            formulaText = "HI = Elev(punto atrás) + BS   Elev(punto) = HI - FS (o - IS)",
            reference = "Comprobación de libreta: ΣBS - ΣFS = Cota final - Cota inicial.",
        ),
        FormulaEntry(
            id = "CIERRE-CAMPO",
            labelEs = "Cierre de campo contra cota conocida",
            formulaText = "Error de cierre = Cota final calculada - Cota de cierre conocida",
            reference = "Distinto de la comprobación aritmética: esta SÍ detecta error real de campo, solo se calcula si hay una cota de cierre independiente.",
        ),
        FormulaEntry(
            id = "NIV-TRIG-ZENITAL",
            labelEs = "Nivelación trigonométrica (cenital + distancia inclinada)",
            formulaText = "ΔElev = Ds·cos(Z) + hi - ht",
            reference = "Z = ángulo cenital medido desde la vertical.",
        ),
        FormulaEntry(
            id = "NIV-TRIG-VERTICAL",
            labelEs = "Nivelación trigonométrica (vertical + distancia inclinada)",
            formulaText = "ΔElev = Ds·sin(α) + hi - ht",
            reference = "α = ángulo vertical medido desde la horizontal.",
        ),
        FormulaEntry(
            id = "NIV-TRIG-ZENITAL-DH",
            labelEs = "Nivelación trigonométrica (cenital + distancia horizontal)",
            formulaText = "ΔElev = Dh·cot(Z) + hi - ht",
            reference = "Cuando se conoce la distancia horizontal en vez de la inclinada.",
        ),
        FormulaEntry(
            id = "NIV-TRIG-VERTICAL-DH",
            labelEs = "Nivelación trigonométrica (vertical + distancia horizontal)",
            formulaText = "ΔElev = Dh·tan(α) + hi - ht",
            reference = "α = ángulo vertical; Dh = distancia horizontal ya reducida.",
        ),
        FormulaEntry(
            id = "POLIGONAL-CIERRE-ANGULAR",
            labelEs = "Cierre angular de poligonal cerrada",
            formulaText = "Cierre = Σángulos medidos - (n-2)·180°   Corrección por ángulo = -Cierre/n",
            reference = "n = número de vértices de la poligonal cerrada.",
        ),
        FormulaEntry(
            id = "POLIGONAL-BRUTO",
            labelEs = "Coordenadas de poligonal (bruto, sin compensar)",
            formulaText = "E,N propagados tramo a tramo: E1=E0+D·sin(Az), N1=N0+D·cos(Az)",
            reference = "Con azimutes ya balanceados angularmente, antes de la compensación lineal.",
        ),
        FormulaEntry(
            id = "POLIGONAL-BOWDITCH",
            labelEs = "Compensación de Bowditch",
            formulaText = "corrección_E_i = -fE·(d_i/perímetro)   corrección_N_i = -fN·(d_i/perímetro)",
            reference = "Solo aplica a poligonal cerrada, o abierta amarrada con ambos extremos conocidos.",
        ),
    ).associateBy { it.id }

    fun get(id: String): FormulaEntry = entries[id]
        ?: FormulaEntry(id, id, "(fórmula no registrada)", "")

    fun all(): List<FormulaEntry> = entries.values.toList()
}
