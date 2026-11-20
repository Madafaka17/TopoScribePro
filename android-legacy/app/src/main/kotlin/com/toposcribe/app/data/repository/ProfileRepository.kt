package com.toposcribe.app.data.repository

import com.toposcribe.calc.AxisPoint
import com.toposcribe.calc.ProfileCandidate
import com.toposcribe.calc.computeProfile
import com.toposcribe.data.local.TopoScribeDatabase
import javax.inject.Inject
import javax.inject.Singleton

class ProfileComputeException(message: String) : Exception(message)

data class ProfileServiceResult(
    val applicable: Boolean,
    val reason: String?,
    val samples: List<com.toposcribe.calc.ProfileSample>,
    val totalLength: Double,
    val alignmentName: String,
    val offsetTolerance: Double,
)

private const val DEFAULT_OFFSET_TOLERANCE = 5.0

@Singleton
class ProfileRepository @Inject constructor(
    private val db: TopoScribeDatabase,
) {
    suspend fun getProfile(projectId: Long, alignmentId: Long, offsetTolerance: Double = DEFAULT_OFFSET_TOLERANCE): ProfileServiceResult {
        val alignment = db.alignmentDao().listSnapshot(projectId).find { it.id == alignmentId }
            ?: throw ProfileComputeException("El eje no existe en este proyecto.")

        val pointIds = alignment.pointSequence
        val points = db.pointDao().listByProject(projectId)
        val codeById = points.associate { it.id to it.code }
        val known = resolveKnownPoints(db, projectId)

        val axisVertices = pointIds.map { id ->
            val state = known[id]
            if (state?.east == null || state.north == null) {
                throw ProfileComputeException("El punto \"${codeById[id] ?: id}\" del eje \"${alignment.name}\" no tiene coordenadas E/N calculadas; no se puede trazar el perfil.")
            }
            AxisPoint(id, state.east, state.north)
        }

        val candidatePoints = points.mapNotNull { p ->
            val state = known[p.id]
            val east = state?.east
            val north = state?.north
            if (east != null && north != null) ProfileCandidate(p.id, east, north, state.elevation) else null
        }

        val result = computeProfile(axisVertices, candidatePoints, offsetTolerance)
        return ProfileServiceResult(result.applicable, result.reason, result.samples, result.totalLength, alignment.name, offsetTolerance)
    }
}
