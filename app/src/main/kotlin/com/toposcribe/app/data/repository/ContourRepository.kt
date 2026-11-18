package com.toposcribe.app.data.repository

import com.toposcribe.calc.ContourResult
import com.toposcribe.calc.ElevatedPoint
import com.toposcribe.calc.computeContours
import com.toposcribe.data.local.TopoScribeDatabase
import javax.inject.Inject
import javax.inject.Singleton

private const val DEFAULT_EQUIDISTANCE = 1.0

@Singleton
class ContourRepository @Inject constructor(
    private val db: TopoScribeDatabase,
) {
    suspend fun getContours(projectId: Long, equidistance: Double = DEFAULT_EQUIDISTANCE): ContourResult {
        val points = db.pointDao().listByProject(projectId)
        val known = resolveKnownPoints(db, projectId)

        val contourPoints = points.mapNotNull { p ->
            val state = known[p.id]
            val east = state?.east
            val north = state?.north
            val elevation = state?.elevation
            if (east != null && north != null && elevation != null) {
                ElevatedPoint(p.id, east, north, elevation)
            } else {
                null
            }
        }

        return computeContours(contourPoints, equidistance)
    }
}
