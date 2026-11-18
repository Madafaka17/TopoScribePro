package com.toposcribe.app.data.repository

import com.toposcribe.calc.FormulaRegistry
import com.toposcribe.calc.LevelingObservationCalcInput
import com.toposcribe.calc.ReportData
import com.toposcribe.calc.ReportLevelingCheck
import com.toposcribe.calc.ReportPhoto
import com.toposcribe.calc.ReportProjectInfo
import com.toposcribe.calc.ReportResultRow
import com.toposcribe.calc.ReportTraverseClosure
import com.toposcribe.calc.ReportTraverseStation
import com.toposcribe.calc.TraverseType
import com.toposcribe.calc.computeDifferentialLeveling
import com.toposcribe.calc.computeFieldMisclosure
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.CoordinateMode
import com.toposcribe.data.local.entity.InstrumentType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReportRepository @Inject constructor(
    private val db: TopoScribeDatabase,
    private val resultsRepository: ResultsRepository,
    private val planRepository: PlanRepository,
    private val levelingRepository: LevelingRepository,
) {
    /** Arma el ReportData completo para el informe PDF -- SOLO lee datos ya guardados/calculados,
     * nunca recalcula ni inventa nada nuevo acá (salvo recalcular en memoria, sin escribir en la
     * base, la comprobación de nivelación con la función pura del motor de cálculo -- igual que
     * ya hacía la app web para armar el informe). */
    suspend fun buildReportData(projectId: Long): ReportData {
        val project = db.projectDao().getById(projectId) ?: error("El proyecto $projectId no existe.")

        val instrumentTypeLabel = when (project.instrumentType) {
            InstrumentType.ESTACION_TOTAL -> "Estación total"
            InstrumentType.TEODOLITO -> "Teodolito"
            InstrumentType.GNSS_RTK -> "GNSS/RTK"
            InstrumentType.NIVEL_OPTICO -> "Nivel"
        }
        val coordinateSystemText = if (project.coordinateMode == CoordinateMode.UTM) {
            "UTM -- datum ${project.utmDatum ?: "(pendiente)"}, zona ${project.utmZone ?: "(pendiente)"}${project.utmHemisphere?.name ?: ""}"
        } else {
            project.coordinateSystem ?: "Local"
        }

        val results = resultsRepository.getProjectResults(projectId)
        val resultRows = results.map {
            ReportResultRow(it.code, it.description, it.east, it.north, it.elevation, roleLabel(it.role.name), it.origin)
        }

        val formulaIds = linkedSetOf<String>()
        for (row in db.computedPointDao().listCurrentSnapshot(projectId)) {
            row.formulaId.split("+").forEach { formulaIds.add(it) }
        }
        val formulasUsed = formulaIds.map { FormulaRegistry.get(it) }

        val known = resolveKnownPoints(db, projectId)
        val levelingChecks = mutableListOf<ReportLevelingCheck>()
        for (run in db.levelingRunDao().listSnapshot(projectId)) {
            val observations = db.levelingObservationDao().listSnapshot(run.id)
            if (observations.isEmpty()) continue
            val startElevation = known[run.startBmPointId]?.elevation ?: continue
            val calcInput = observations.map {
                LevelingObservationCalcInput(it.id, it.sequenceOrder, it.pointId, it.readingType, it.readingValue, it.isTurningPoint)
            }
            val result = computeDifferentialLeveling(calcInput, startElevation)
            val knownEndElevation = levelingRepository.resolveKnownEndElevation(run)
            levelingChecks.add(
                ReportLevelingCheck(
                    runName = run.name,
                    sumBacksight = result.sumBacksight,
                    sumForesight = result.sumForesight,
                    checkValue = result.checkValue,
                    startElevation = result.startElevation,
                    finalElevation = result.finalElevation,
                    arithmeticCheckOk = result.arithmeticCheckOk,
                    knownEndElevation = knownEndElevation,
                    fieldMisclosure = knownEndElevation?.let { computeFieldMisclosure(result.finalElevation, it) },
                ),
            )
        }

        val codeById = db.pointDao().listByProject(projectId).associate { it.id to it.code }
        val traverseClosures = mutableListOf<ReportTraverseClosure>()
        for (traverse in db.traverseDao().listSnapshot(projectId)) {
            val latest = db.adjustmentDao().getLatest(traverse.id) ?: continue
            val legs = db.planimetryObservationDao().listByTraverse(traverse.id)
            val stations = legs.distinctBy { it.targetPointId }.mapNotNull { leg ->
                val rows = db.computedPointDao().listCurrentForPoint(leg.targetPointId)
                val raw = rows.find { it.method == "poligonal_bruto" }
                val adjusted = rows.find { it.method == "poligonal_bowditch" }
                if (raw == null && adjusted == null) return@mapNotNull null
                ReportTraverseStation(
                    pointCode = codeById[leg.targetPointId] ?: leg.targetPointId.toString(),
                    rawEast = raw?.east,
                    rawNorth = raw?.north,
                    adjustedEast = adjusted?.east,
                    adjustedNorth = adjusted?.north,
                )
            }
            traverseClosures.add(
                ReportTraverseClosure(
                    traverseName = traverse.name,
                    typeLabel = traverseTypeLabel(traverse.type),
                    angularMisclosureDeg = latest.angularMisclosure,
                    fE = latest.fE,
                    fN = latest.fN,
                    fL = latest.fL,
                    relativePrecision = latest.relativePrecision,
                    stations = stations,
                ),
            )
        }

        val planData = planRepository.getPlanData(projectId)

        val allPoints = db.pointDao().listByProject(projectId)
        val pointPhotos = allPoints.map { p ->
            ReportPhoto(
                code = p.code,
                description = p.description,
                caption = p.photoCaption,
                segment = p.photoSegment,
                chainage = p.photoChainage,
                photoFilePath = p.sketchPhotoPath,
            )
        }
        // Fotos adjuntas a observaciones de la Libreta: también entran al panel fotográfico, rotuladas "ESTACIÓN → PUNTO".
        val observationPhotos = db.planimetryObservationDao().listSnapshot(projectId)
            .filter { it.photoPath != null }
            .map { obs ->
                ReportPhoto(
                    code = "${codeById[obs.occupiedPointId] ?: "?"} → ${codeById[obs.targetPointId] ?: "?"}",
                    description = obs.rawNotes,
                    caption = obs.rawNotes,
                    segment = obs.tramo,
                    chainage = null,
                    photoFilePath = obs.photoPath,
                )
            }
        val photos = pointPhotos + observationPhotos

        return ReportData(
            project = ReportProjectInfo(
                name = project.name,
                purpose = project.purpose,
                location = project.location,
                siteCharacteristics = project.siteCharacteristics,
                date = project.date,
                responsible = project.responsible,
                instrument = project.instrument,
                instrumentTypeLabel = instrumentTypeLabel,
                coordinateSystemText = coordinateSystemText,
                unitsDistance = project.unitsDistance,
                unitsAngle = project.unitsAngle,
                fieldProcedure = project.fieldProcedure,
                observations = project.observations,
                isDemo = project.isDemo,
            ),
            results = resultRows,
            formulasUsed = formulasUsed,
            levelingChecks = levelingChecks,
            traverseClosures = traverseClosures,
            planData = planData,
            photos = photos,
        )
    }

    private fun roleLabel(role: String): String = when (role) {
        "CONTROL" -> "control"
        "POLIGONAL" -> "poligonal"
        "RELLENO" -> "relleno"
        else -> "otro"
    }

    private fun traverseTypeLabel(type: TraverseType): String = when (type) {
        TraverseType.CERRADA_POLIGONO -> "Cerrada"
        TraverseType.ABIERTA_AMARRADA -> "Abierta amarrada"
        TraverseType.ABIERTA_LIBRE -> "Abierta libre"
    }
}
