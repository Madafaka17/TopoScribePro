package com.toposcribe.app.data.repository

import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.PointRole
import javax.inject.Inject
import javax.inject.Singleton

data class ResultRow(
    val pointId: Long,
    val code: String,
    val description: String?,
    val east: Double?,
    val north: Double?,
    val elevation: Double?,
    val origin: String,
    val isControl: Boolean,
    val hasResult: Boolean,
    val eastSourceId: Long?,
    val northSourceId: Long?,
    val elevationSourceId: Long?,
    val role: PointRole,
    val reviewed: Boolean,
    val reviewedAt: String?,
    val reviewedBy: String?,
)

@Singleton
class ResultsRepository @Inject constructor(
    private val db: TopoScribeDatabase,
) {
    suspend fun getProjectResults(projectId: Long): List<ResultRow> {
        val points = db.pointDao().listByProject(projectId)
        val known = resolveKnownPoints(db, projectId)

        return points.map { point ->
            val state = known[point.id]
            val originParts = linkedSetOf<String>()
            state?.eastSource?.let { originParts.add(MethodPriority.label(it.method)) }
            state?.northSource?.let {
                if (it.method != state.eastSource?.method) originParts.add(MethodPriority.label(it.method))
            }
            state?.elevationSource?.let { originParts.add(MethodPriority.label(it.method)) }

            ResultRow(
                pointId = point.id,
                code = point.code,
                description = point.description,
                east = state?.east,
                north = state?.north,
                elevation = state?.elevation,
                origin = if (originParts.isNotEmpty()) originParts.joinToString(" + ") else "Sin calcular",
                isControl = point.isControl,
                hasResult = state != null,
                eastSourceId = state?.eastSource?.id,
                northSourceId = state?.northSource?.id,
                elevationSourceId = state?.elevationSource?.id,
                role = point.role,
                reviewed = point.reviewed,
                reviewedAt = point.reviewedAt,
                reviewedBy = point.reviewedBy,
            )
        }
    }
}
