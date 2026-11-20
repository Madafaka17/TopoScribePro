package com.toposcribe.app.data.repository

import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.ComputedPointEntity

data class ResolvedPointState(
    val east: Double? = null,
    val north: Double? = null,
    val elevation: Double? = null,
    val eastSource: ComputedPointEntity? = null,
    val northSource: ComputedPointEntity? = null,
    val elevationSource: ComputedPointEntity? = null,
)

/**
 * Para cada punto del proyecto, combina sus filas vigentes en computed_points (puede haber
 * varias, p. ej. bruto + compensado) eligiendo por prioridad de método el este/norte/cota "mejor
 * disponible". Sincroniza primero los puntos de control, para que uno recién marcado como control
 * aparezca de inmediato sin depender de que el usuario dispare algún otro cálculo antes.
 */
suspend fun resolveKnownPoints(db: TopoScribeDatabase, projectId: Long): Map<Long, ResolvedPointState> {
    syncControlPoints(db, projectId)

    val currentRows = db.computedPointDao().listCurrentSnapshot(projectId)
    val byPoint = currentRows.groupBy { it.pointId }

    return byPoint.mapValues { (_, pointRows) ->
        val sorted = pointRows.sortedBy { MethodPriority.rank(it.method) }
        var state = ResolvedPointState()
        for (row in sorted) {
            if (state.east == null && row.east != null) state = state.copy(east = row.east, eastSource = row)
            if (state.north == null && row.north != null) state = state.copy(north = row.north, northSource = row)
            if (state.elevation == null && row.elevation != null) state = state.copy(elevation = row.elevation, elevationSource = row)
        }
        state
    }
}
