package com.toposcribe.app.data.repository

import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.StationSetupEntity
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class StationSetupRepository @Inject constructor(
    private val db: TopoScribeDatabase,
) {
    fun observeByProject(projectId: Long): Flow<List<StationSetupEntity>> = db.stationSetupDao().observeByProject(projectId)

    suspend fun getLatest(projectId: Long): StationSetupEntity? = db.stationSetupDao().getLatest(projectId)

    suspend fun create(setup: StationSetupEntity): Long = db.stationSetupDao().insert(setup.copy(createdAt = Instant.now().toString()))
}
