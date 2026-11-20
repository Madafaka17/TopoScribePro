package com.toposcribe.calc

/**
 * Formas de datos del plano compartidas entre :app (arma PlanData desde Room) y :io (DXF/PDF la
 * consumen para dibujar) -- viven en :calc, el único módulo que ambos pueden ver sin crear una
 * dependencia circular (:app depende de :io, así que :io no puede depender de :app).
 */
data class PlanPoint(
    val pointId: Long,
    val code: String,
    val description: String?,
    val east: Double,
    val north: Double,
    val isControl: Boolean,
    val role: String,
    val elevation: Double? = null,
)

data class PlanLine(val fromPointId: Long, val toPointId: Long, val kind: String)

data class PlanData(val points: List<PlanPoint>, val lines: List<PlanLine>, val pointsWithoutCoords: Int)
