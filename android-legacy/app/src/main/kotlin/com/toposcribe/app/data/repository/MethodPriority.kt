package com.toposcribe.app.data.repository

/** Orden de prioridad cuando un punto tiene más de una fila vigente (p. ej. poligonal bruta y
 * compensada por Bowditch coexisten a propósito). La de mayor prioridad se usa como resultado
 * "principal" para cálculos posteriores; las demás siguen disponibles para el toggle antes/después. */
object MethodPriority {
    val ORDER = listOf(
        "poligonal_bowditch",
        "poligonal_bruto",
        "radiacion",
        "nivelacion_geometrica",
        "nivelacion_trigonometrica",
        "dato_fijo",
    )

    fun rank(method: String): Int {
        val idx = ORDER.indexOf(method)
        return if (idx == -1) ORDER.size else idx
    }

    val LABELS_ES: Map<String, String> = mapOf(
        "poligonal_bowditch" to "Poligonal (compensado Bowditch)",
        "poligonal_bruto" to "Poligonal (bruto)",
        "radiacion" to "Radiación",
        "nivelacion_geometrica" to "Nivelación geométrica",
        "nivelacion_trigonometrica" to "Nivelación trigonométrica",
        "dato_fijo" to "Punto de control",
    )

    fun label(method: String): String = LABELS_ES[method] ?: method
}
