package com.toposcribe.calc

data class FormulaEntry(
    val id: String,
    val labelEs: String,
    val formulaText: String,
    val reference: String,
)

/**
 * Texto legible en español + fórmula por formulaId, usado por la pantalla y por la memoria de
 * cálculo del PDF. Los id históricos (poligonal, GNSS, nivelación trigonométrica) se conservan solo
 * para rotular datos de proyectos anteriores.
 */
object FormulaRegistry {
    private val entries: Map<String, FormulaEntry> = listOf(
        FormulaEntry("DATO-FIJO", "Punto de control / dato fijo", "Valor ingresado directamente",
            "No proviene de una observación calculada: es un BM o una estación con coordenadas/cota conocidas o locales declaradas."),
        FormulaEntry("RADIACION", "Radiación con teodolito", "ΔE = d·sen(A)   ΔN = d·cos(A)   E₂ = E₁ + ΔE   N₂ = N₁ + ΔN",
            "A = azimut medido desde el norte en sentido horario; d = distancia horizontal; (E₁, N₁) = estación ocupada."),
        FormulaEntry("REDUCCION-ZENITAL", "Reducción de distancia inclinada (ángulo cenital)", "d = Ds·sen(Z)",
            "Z = ángulo cenital medido desde la vertical; Ds = distancia inclinada."),
        FormulaEntry("REDUCCION-VERTICAL", "Reducción de distancia inclinada (ángulo vertical)", "d = Ds·cos(α)",
            "α = ángulo vertical medido desde la horizontal; Ds = distancia inclinada."),
        FormulaEntry("NIVELACION-DIFERENCIAL", "Nivelación geométrica con nivel de ingeniero",
            "HI = cota de referencia + VA   Cota = HI − VI (o − VD)",
            "Comprobación de libreta: Σ VA − Σ VD = cota final − cota inicial."),
        FormulaEntry("CIERRE-CAMPO", "Error de cierre de la nivelación", "Error de cierre = cota final calculada − cota conocida del BM final",
            "Solo existe si hay un BM final de cota conocida independiente."),
        // --- históricos (opciones retiradas de la interfaz; los datos se conservan) ---
        FormulaEntry("DATO-FIJO-GNSS", "Punto medido con GNSS/RTK (histórico)", "Valor leído directamente del receptor", "Dato de un proyecto anterior."),
        FormulaEntry("NIV-TRIG-ZENITAL", "Nivelación trigonométrica (histórico)", "ΔElev = Ds·cos(Z) + hi − ht", "Dato de un proyecto anterior."),
        FormulaEntry("NIV-TRIG-VERTICAL", "Nivelación trigonométrica (histórico)", "ΔElev = Ds·sen(α) + hi − ht", "Dato de un proyecto anterior."),
        FormulaEntry("NIV-TRIG-ZENITAL-DH", "Nivelación trigonométrica (histórico)", "ΔElev = Dh·cot(Z) + hi − ht", "Dato de un proyecto anterior."),
        FormulaEntry("NIV-TRIG-VERTICAL-DH", "Nivelación trigonométrica (histórico)", "ΔElev = Dh·tan(α) + hi − ht", "Dato de un proyecto anterior."),
        FormulaEntry("POLIGONAL-CIERRE-ANGULAR", "Poligonal (histórico)", "Cierre = Σángulos − (n−2)·180°", "Dato de un proyecto anterior."),
        FormulaEntry("POLIGONAL-BRUTO", "Poligonal bruta (histórico)", "E,N propagados tramo a tramo", "Dato de un proyecto anterior."),
        FormulaEntry("POLIGONAL-BOWDITCH", "Poligonal compensada Bowditch (histórico)", "corrección = −f·(d_i/perímetro)", "Dato de un proyecto anterior."),
    ).associateBy { it.id }

    fun get(id: String): FormulaEntry = entries[id] ?: FormulaEntry(id, id, "(fórmula no registrada)", "")

    fun all(): List<FormulaEntry> = entries.values.toList()
}
