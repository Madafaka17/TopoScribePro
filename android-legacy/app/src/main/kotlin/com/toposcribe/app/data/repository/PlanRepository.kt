package com.toposcribe.app.data.repository

import com.toposcribe.calc.PlanData
import com.toposcribe.calc.PlanLine
import com.toposcribe.calc.PlanPoint
import com.toposcribe.data.local.TopoScribeDatabase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanRepository @Inject constructor(
    private val db: TopoScribeDatabase,
) {
    /** Datos listos para dibujar el plano: solo puntos con E/N conocidos, y los tramos entre ellos. */
    suspend fun getPlanData(projectId: Long): PlanData {
        val allPoints = db.pointDao().listByProject(projectId)
        val known = resolveKnownPoints(db, projectId)

        val planPoints = allPoints.mapNotNull { p ->
            val state = known[p.id]
            if (state?.east != null && state.north != null) {
                PlanPoint(p.id, p.code, p.description, state.east, state.north, p.isControl, p.role.name, state.elevation)
            } else {
                null
            }
        }

        val knownIds = planPoints.map { it.pointId }.toSet()
        val observations = db.planimetryObservationDao().listSnapshot(projectId)
        val lines = observations
            .filter { it.occupiedPointId in knownIds && it.targetPointId in knownIds }
            .map { PlanLine(it.occupiedPointId, it.targetPointId, if (it.traverseId != null) "poligonal" else "radiacion") }

        return PlanData(planPoints, lines, allPoints.size - planPoints.size)
    }
}
