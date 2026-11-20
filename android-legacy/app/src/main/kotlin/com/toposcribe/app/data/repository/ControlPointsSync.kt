package com.toposcribe.app.data.repository

import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.ComputedPointEntity
import com.toposcribe.data.local.entity.DataSource
import java.time.Instant

private const val METHOD_DATO_FIJO = "dato_fijo"

/**
 * Garantiza que todo punto con al menos una coordenada/cota fija ingresada directamente (no solo
 * isControl -- también un punto medido con GNSS, etc.) tenga una fila vigente en computed_points,
 * para que el resto del motor de cálculo pueda resolver "¿se conoce la posición de este punto?"
 * consultando únicamente computed_points. El formulaId distingue si el dato vino de un punto de
 * control o de una medición GNSS/RTK.
 */
suspend fun syncControlPoints(db: TopoScribeDatabase, projectId: Long) {
    val allPoints = db.pointDao().listByProject(projectId)
        .filter { it.fixedEast != null || it.fixedNorth != null || it.fixedElevation != null }

    for (point in allPoints) {
        val formulaId = if (point.dataSource == DataSource.MEDIDO_GNSS) "DATO-FIJO-GNSS" else "DATO-FIJO"
        val existing = db.computedPointDao().listCurrentForPoint(point.id).find { it.method == METHOD_DATO_FIJO }
        val same = existing != null &&
            existing.east == point.fixedEast &&
            existing.north == point.fixedNorth &&
            existing.elevation == point.fixedElevation &&
            existing.formulaId == formulaId
        if (same) continue

        db.computedPointDao().retireCurrent(point.id, METHOD_DATO_FIJO)
        db.computedPointDao().insert(
            ComputedPointEntity(
                projectId = projectId,
                pointId = point.id,
                east = point.fixedEast,
                north = point.fixedNorth,
                elevation = point.fixedElevation,
                method = METHOD_DATO_FIJO,
                formulaId = formulaId,
                sourceObservationIds = emptyList(),
                computedAt = Instant.now().toString(),
            ),
        )
    }
}
