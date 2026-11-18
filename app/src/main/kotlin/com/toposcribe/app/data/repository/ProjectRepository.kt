package com.toposcribe.app.data.repository

import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.ProjectEntity
import com.toposcribe.data.seed.DemoSeeder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class ProjectRepository @Inject constructor(
    private val db: TopoScribeDatabase,
) {
    fun observeAll(): Flow<List<ProjectEntity>> = db.projectDao().observeAll()

    fun observeById(id: Long): Flow<ProjectEntity?> = db.projectDao().observeById(id)

    suspend fun getById(id: Long): ProjectEntity? = db.projectDao().getById(id)

    /** Crea un proyecto NUEVO VACÍO -- condición principal de la especificación: nunca arranca
     * con datos, mediciones ni fotografías inventadas. */
    suspend fun createEmpty(project: ProjectEntity): Long =
        db.projectDao().insert(project.copy(isDemo = false, createdAt = Instant.now().toString()))

    suspend fun update(project: ProjectEntity) =
        db.projectDao().update(project.copy(updatedAt = Instant.now().toString()))

    suspend fun delete(project: ProjectEntity) = db.projectDao().delete(project)

    /** Genera el proyecto de demostración -- separado de los proyectos reales, marcado
     * permanentemente is_demo=true y eliminable. Devuelve el id del proyecto creado. */
    suspend fun loadDemo(): Long = DemoSeeder.seed(db)
}
