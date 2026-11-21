package com.toposcribe.app.data.repository

/** Orden de prioridad cuando un punto tiene más de una fila vigente (p. ej. poligonal bruta y
 * compensada por Bowditch coexisten a propósito). La de mayor prioridad se usa como resultado
 * "principal" para cálculos posteriores; las demás siguen disponibles para el toggle antes/después. */
object MethodPriority {
    // Un dato fijo (BM, estación o control con valor conocido) manda sobre cualquier valor calculado.
    val ORDER = listOf(
        "dato_fijo",
        "poligonal_bowditch",
        "poligonal_bruto",
        "radiacion",
        "nivelacion_geometrica",
        "nivelacion_trigonometrica",
    )

    fun rank(method: String): Int {
        val idx = ORDER.indexOf(method)
        return if (idx == -1) ORDER.size else idx
    }

    val LABELS_ES: Map<String, String> = mapOf(
        "poligonal_bowditch" to "Poligonal (compensado Bowditch)",
        "poligonal_bruto" to "Poligonal (bruto)",
        "radiacion" to "Radiación (teodolito)",
        "nivelacion_geometrica" to "Nivel de ingeniero",
        "nivelacion_trigonometrica" to "Nivelación trigonométrica",
        "dato_fijo" to "Control conocido",
    )

    fun label(method: String): String = LABELS_ES[method] ?: method
}
