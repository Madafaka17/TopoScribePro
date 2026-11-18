package com.toposcribe.data.seed

import com.toposcribe.calc.ReadingType
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.CoordinateMode
import com.toposcribe.data.local.entity.DataSource
import com.toposcribe.data.local.entity.InstrumentType
import com.toposcribe.data.local.entity.LevelingObservationEntity
import com.toposcribe.data.local.entity.LevelingRunEntity
import com.toposcribe.data.local.entity.PlanimetryMethod
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.PointRole
import com.toposcribe.data.local.entity.ProjectEntity
import java.time.Instant

/**
 * Genera el proyecto de demostración reutilizando los MISMOS casos de prueba verificados a mano
 * que el motor de cálculo (:calc) -- is_demo=true, visible como "Proyecto de demostración" y
 * eliminable, así el demo nunca presenta números inventados sin verificar.
 */
object DemoSeeder {

    suspend fun seed(db: TopoScribeDatabase): Long {
        val now = Instant.now().toString()

        val projectId = db.projectDao().insert(
            ProjectEntity(
                name = "Proyecto de demostración",
                purpose = "Demostrar el funcionamiento de la aplicación con casos de cálculo verificados a mano.",
                location = "Campus universitario (ejemplo)",
                siteCharacteristics = "Terreno llano, ejemplo sintético.",
                date = now.substring(0, 10),
                responsible = "Datos de demostración",
                fieldProcedure = "Datos sintéticos -- no hubo trabajo de campo real.",
                instrument = "Estación total (simulada)",
                instrumentType = InstrumentType.ESTACION_TOTAL,
                coordinateSystem = "Local / arbitrario",
                coordinateMode = CoordinateMode.LOCAL,
                observations = "DATOS DE DEMOSTRACIÓN: valores sintéticos pero verificados a mano (mismos casos usados en las pruebas automatizadas del motor de cálculo). No representan un levantamiento real. Puede eliminar este proyecto cuando quiera.",
                isDemo = true,
                createdAt = now,
            ),
        )

        // BM-1: banco de nivel de referencia, cota fija de partida (caso D1 de nivelación).
        val bm1Id = db.pointDao().insert(
            PointEntity(
                projectId = projectId,
                code = "BM-1",
                description = "Banco de nivel de referencia (cota fija de partida)",
                isControl = true,
                role = PointRole.CONTROL,
                dataSource = DataSource.CONTROL_OFICIAL,
                fixedElevation = 100.0,
                createdAt = now,
            ),
        )
        // BM-2: punto de cierre con cota oficial conocida (99.498), para el cierre de campo real
        // -- la cota CALCULADA por la nivelación da 99.500, con un error de cierre genuino de
        // 0.002, no un cierre perfecto inventado.
        val bm2Id = db.pointDao().insert(
            PointEntity(
                projectId = projectId,
                code = "BM-2",
                description = "Punto de cierre de la nivelación (cota oficial conocida: 99.498)",
                isControl = true,
                role = PointRole.CONTROL,
                dataSource = DataSource.CONTROL_OFICIAL,
                fixedElevation = 99.498,
                createdAt = now,
            ),
        )
        val tp1Id = db.pointDao().insert(PointEntity(projectId = projectId, code = "CE-1", description = "Cambio de estación 1", role = PointRole.OTRO, createdAt = now))
        val xId = db.pointDao().insert(PointEntity(projectId = projectId, code = "X-1", description = "Punto de detalle (vista intermedia)", role = PointRole.RELLENO, createdAt = now))
        val tp2Id = db.pointDao().insert(PointEntity(projectId = projectId, code = "CE-2", description = "Cambio de estación 2", role = PointRole.OTRO, createdAt = now))

        val runId = db.levelingRunDao().insert(
            LevelingRunEntity(
                projectId = projectId,
                name = "Nivelación demo BM-1 a BM-2",
                startBmPointId = bm1Id,
                endBmPointId = bm2Id,
                endKnownElevation = 99.498,
                createdAt = now,
            ),
        )
        // Caso D1 verificado: BM1=100.000 -> ... -> BM2=99.500, ΣBS=4.200, ΣFS=4.700, ΣBS-ΣFS=-0.500.
        val obs = listOf(
            LevelingObservationEntity(levelingRunId = runId, sequenceOrder = 1, pointId = bm1Id, readingType = ReadingType.BS, readingValue = 1.5, createdAt = now),
            LevelingObservationEntity(levelingRunId = runId, sequenceOrder = 2, pointId = tp1Id, readingType = ReadingType.FS, readingValue = 1.2, isTurningPoint = true, createdAt = now),
            LevelingObservationEntity(levelingRunId = runId, sequenceOrder = 3, pointId = tp1Id, readingType = ReadingType.BS, readingValue = 1.1, createdAt = now),
            LevelingObservationEntity(levelingRunId = runId, sequenceOrder = 4, pointId = xId, readingType = ReadingType.IS, readingValue = 1.0, createdAt = now),
            LevelingObservationEntity(levelingRunId = runId, sequenceOrder = 5, pointId = tp2Id, readingType = ReadingType.FS, readingValue = 1.8, isTurningPoint = true, createdAt = now),
            LevelingObservationEntity(levelingRunId = runId, sequenceOrder = 6, pointId = tp2Id, readingType = ReadingType.BS, readingValue = 1.6, createdAt = now),
            LevelingObservationEntity(levelingRunId = runId, sequenceOrder = 7, pointId = bm2Id, readingType = ReadingType.FS, readingValue = 1.7, createdAt = now),
        )
        for (o in obs) db.levelingObservationDao().insert(o)

        // EST-1: estación de control fija, punto de partida para los casos de radiación.
        val est1Id = db.pointDao().insert(
            PointEntity(
                projectId = projectId,
                code = "EST-1",
                description = "Estación de control (punto fijo de partida)",
                isControl = true,
                role = PointRole.CONTROL,
                dataSource = DataSource.CONTROL_OFICIAL,
                fixedEast = 1000.0,
                fixedNorth = 1000.0,
                fixedElevation = 100.0,
                createdAt = now,
            ),
        )
        val p1Id = db.pointDao().insert(PointEntity(projectId = projectId, code = "P-1", description = "Radiación desde EST-1, azimut 45°, distancia horizontal 100 m", role = PointRole.RELLENO, createdAt = now))
        val p2Id = db.pointDao().insert(PointEntity(projectId = projectId, code = "P-2", description = "Radiación desde EST-1 con reducción de distancia inclinada (ángulo cenital)", role = PointRole.RELLENO, createdAt = now))

        // Observaciones crudas de radiación (caso A1 y A2 verificados a mano). El cálculo en sí
        // (computed_points) se dispara desde PlanimetryRepository.computePlanimetry cuando el
        // usuario pulsa "Calcular Planimetría" -- aquí solo se dejan los datos crudos de campo,
        // nunca un resultado ya calculado.
        db.planimetryObservationDao().insert(
            PlanimetryObservationEntity(
                projectId = projectId,
                occupiedPointId = est1Id,
                targetPointId = p1Id,
                method = PlanimetryMethod.ACIMUT_DISTANCIA,
                horizontalDistance = 100.0,
                azimuth = 45.0,
                instrumentHeight = 1.5,
                prismHeight = 1.5,
                createdAt = now,
            ),
        )
        // Caso A2: elev0=100 (EST-1), hi=1.5, ht=1.2, Ds=50, Z=80° -> Dh=49.240, dElev=8.982, elev1=108.982.
        db.planimetryObservationDao().insert(
            PlanimetryObservationEntity(
                projectId = projectId,
                occupiedPointId = est1Id,
                targetPointId = p2Id,
                method = PlanimetryMethod.ACIMUT_DISTANCIA,
                slopeDistance = 50.0,
                zenithAngle = 80.0,
                azimuth = 90.0,
                instrumentHeight = 1.5,
                prismHeight = 1.2,
                createdAt = now,
            ),
        )

        return projectId
    }
}
