package com.toposcribe.app.data.repository

import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.AlignmentEntity
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class AlignmentRepository @Inject constructor(
    private val db: TopoScribeDatabase,
) {
    fun observeByProject(projectId: Long): Flow<List<AlignmentEntity>> = db.alignmentDao().observeByProject(projectId)

    suspend fun create(projectId: Long, name: String, pointIds: List<Long>): Long =
        db.alignmentDao().insert(
            AlignmentEntity(projectId = projectId, name = name, pointSequence = pointIds, createdAt = Instant.now().toString()),
        )

    suspend fun delete(alignment: AlignmentEntity) = db.alignmentDao().delete(alignment)
}
