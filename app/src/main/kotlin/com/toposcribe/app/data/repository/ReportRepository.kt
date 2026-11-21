package com.toposcribe.app.data.repository

import com.toposcribe.app.BuildConfig
import com.toposcribe.calc.AnnexRules
import com.toposcribe.calc.ContourResult
import com.toposcribe.calc.ReadingType
import com.toposcribe.calc.ReportAnnex
import com.toposcribe.calc.ReportContourSummary
import com.toposcribe.calc.ReportData
import com.toposcribe.calc.ReportLevelCalcRow
import com.toposcribe.calc.ReportLevelInputRow
import com.toposcribe.calc.ReportLevelRun
import com.toposcribe.calc.ReportPhotoEntry
import com.toposcribe.calc.ReportPointRow
import com.toposcribe.calc.ReportProjectInfo
import com.toposcribe.calc.ReportRadiationRow
import com.toposcribe.calc.ReportRadiationStation
import com.toposcribe.calc.ResultState
import com.toposcribe.calc.formatDegreesAsDms
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.StationSetupEntity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Orden «natural» de códigos: P2 antes que P10; letras antes que números compuestos. */
val NaturalCodeComparator: Comparator<String> = Comparator { a, b ->
    val ra = Regex("\\d+|\\D+").findAll(a.uppercase()).map { it.value }.toList()
    val rb = Regex("\\d+|\\D+").findAll(b.uppercase()).map { it.value }.toList()
    for (i in 0 until minOf(ra.size, rb.size)) {
        val x = ra[i]; val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
        if (c != 0) return@Comparator c
    }
    ra.size - rb.size
}

fun procedureLabel(instrumentRef: String?): String = when (instrumentRef) {
    "NIVEL" -> "Nivel de ingeniero"
    "TEODOLITO" -> "Teodolito"
    "CONTROL" -> "Control / BM"
    "OTRO", null, "" -> "—"
    else -> instrumentRef
}

fun orientationText(s: StationSetupEntity?): String =
    if (s == null) "sin orientación declarada (solo se aceptan azimuts directos)"
    else "referencia «${s.backsightPointLabel}» con azimut ${formatDegreesAsDms(s.baseAzimuthDecimal, 2)} y lectura horizontal ${formatDegreesAsDms(s.backsightReadingDeg, 2)} sobre ella; convenio ${s.angleConvention.labelEs.substringBefore(" (").lowercase()}"

@Singleton
class ReportRepository @Inject constructor(
    private val db: TopoScribeDatabase,
    private val results: ResultsRepository,
    private val plans: PlanRepository,
    private val leveling: LevelingRepository,
    private val radiation: RadiationRepository,
    private val contours: ContourRepository,
    private val annexes: AnnexRepository,
    private val files: AppFiles,
) {
    /** Arma el informe SOLO leyendo lo guardado/calculado: no inventa ningún dato. */
    suspend fun build(projectId: Long): ReportData {
        val project = db.projectDao().getById(projectId) ?: error("El proyecto $projectId no existe.")
        val allResults = results.getProjectResults(projectId).sortedWith(compareBy(NaturalCodeComparator) { it.point.code })
        val pointById = allResults.associateBy { it.point.id }
        val pending = mutableListOf<String>()

        // --- nivelaciones
        val runs = leveling.runs(projectId)
        val levelRuns = mutableListOf<ReportLevelRun>()
        for (run in runs) {
            val ev = leveling.evaluate(run.id) ?: continue
            val res = ev.result
            if (res == null) { pending += "Nivelación «${run.name}»: no se puede calcular (${ev.blockers.joinToString(" ")})"; continue }
            var station = 0
            val inputRows = ev.rows.map { r ->
                if (r.observation.readingType == ReadingType.BS) station += 1
                ReportLevelInputRow(r.observation.sequenceOrder, r.calc?.stationNumber ?: station, r.pointCode, typeLabel(r.observation.readingType), r.observation.readingValue,
                    r.observation.partialDistance, r.observation.isTurningPoint, r.observation.rawNotes, r.correction)
            }
            val calcRows = res.rows.map { c ->
                ReportLevelCalcRow(c.stationNumber, pointById[c.pointId]?.point?.code ?: "?", typeLabel(c.readingType), c.readingValue, c.partialDistance, c.cumulativeDistance, c.operation, c.elevation)
            }
            levelRuns += ReportLevelRun(
                name = run.name, startBmCode = ev.startBmCode, startElevation = res.startElevation, inputRows = inputRows, calcRows = calcRows,
                sumBacksight = res.sumBacksight, sumForesight = res.sumForesight, finalElevation = res.finalElevation, arithmeticCheckOk = res.arithmeticCheckOk,
                endBmCode = ev.endBmCode, knownEndElevation = ev.knownEndElevation, misclosure = ev.misclosure, state = ev.state, warnings = res.warnings, profile = ev.profile!!,
            )
            if (ev.knownEndElevation == null) pending += "Nivelación «${run.name}»: sin BM final de cota conocida; el cierre contra control externo no puede comprobarse."
            if (!res.arithmeticCheckOk) pending += "Nivelación «${run.name}»: la comprobación aritmética ΣVA − ΣVD no coincide con la diferencia de cotas."
            if (ev.profile?.valid == false) pending += "Nivelación «${run.name}»: perfil longitudinal no dibujado (${ev.profile?.reason})."
            if (ev.state != ResultState.ACTUALIZADO) pending += "Nivelación «${run.name}»: ${ev.state.labelEs}."
        }
        if (runs.isEmpty()) pending += "No hay nivelaciones registradas (no hay altimetría con nivel de ingeniero)."

        // --- radiación
        val rad = radiation.evaluate(projectId)
        val stations = rad.rows.filter { it.legacyNote == null }.groupBy { it.observation.occupiedPointId }.map { (stationId, rows) ->
            val code = rows.first().stationCode
            val coords = rad.stationCoordinates[stationId]
            val setup = rows.first().setup
            val stationPoint = pointById[stationId]?.point
            ReportRadiationStation(
                stationCode = code, stationEast = coords?.first, stationNorth = coords?.second,
                coordinateOrigin = when {
                    stationPoint?.fixedEast != null -> if (stationPoint.isControl) "coordenadas conocidas (control)" else "coordenadas locales declaradas"
                    coords != null -> "coordenadas calculadas desde otra estación"
                    else -> "coordenadas no conocidas"
                },
                instrumentHeight = setup?.instrumentHeightAi, orientationText = orientationText(setup),
                rows = rows.map { r ->
                    val s = r.step
                    ReportRadiationRow(
                        code = r.targetCode, description = r.targetDescription, originalReading = r.originalReading, correction = r.correction,
                        azimuthText = s?.let { formatDegreesAsDms(it.azimuthDeg, 2) }, distanceUsed = s?.horizontalDistance, deltaE = s?.deltaE, deltaN = s?.deltaN,
                        east = s?.east, north = s?.north,
                        formulaLines = s?.let { listOf(it.azimuthFormula, it.distanceFormula, it.deltaEFormula, it.deltaNFormula, it.eastFormula, it.northFormula) }.orEmpty(),
                        errors = r.errors,
                    )
                },
            )
        }
        val legacyRows = rad.rows.count { it.legacyNote != null }
        if (legacyRows > 0) pending += "$legacyRows lectura(s) heredadas de poligonales (opción retirada) se conservan en la base de datos pero no se recalculan ni se incluyen en este informe."
        if (stations.isEmpty()) pending += "No hay radiaciones con teodolito registradas."
        if (rad.state != ResultState.ACTUALIZADO && rad.rows.any { it.legacyNote == null }) pending += "Planimetría: ${rad.state.labelEs}."
        rad.rows.filter { it.errors.isNotEmpty() && it.legacyNote == null }.forEach { pending += "Radiación a ${it.targetCode} desde ${it.stationCode} sin calcular: ${it.errors.first()}" }

        // --- tablas y plano
        val pointRows = allResults.map { r ->
            ReportPointRow(r.point.code, r.point.description, procedureLabel(r.point.instrumentRef), r.east, r.north, r.elevation, r.elevationSource.labelEs)
        }
        val withoutCoords = allResults.filter { !it.hasCoordinates }.map { it.point.code }
        val withoutElev = allResults.filter { it.elevation == null }.map { it.point.code }
        if (withoutCoords.isNotEmpty()) pending += "Puntos sin coordenadas Este/Norte: ${withoutCoords.joinToString()}."
        if (withoutElev.isNotEmpty()) pending += "Puntos sin cota: ${withoutElev.joinToString()} (no se les asignó ninguna)."

        val plan = plans.getPlanModel(projectId)
        val eq = project.contourEquidistance
        val analysis = contours.analyze(projectId, eq ?: 1.0)
        val summary = when {
            eq != null && analysis.result?.applicable == true -> {
                val c: ContourResult = analysis.result
                ReportContourSummary(true, c.equidistance, c.levels.size, c.method, analysis.eligibility.usable.size, null)
            }
            eq == null -> ReportContourSummary(false, 0.0, 0, "", analysis.eligibility.usable.size, "las curvas de nivel no se han generado (use la pestaña «Curvas de nivel»).")
            else -> ReportContourSummary(false, eq, 0, "", analysis.eligibility.usable.size, analysis.result?.reason ?: analysis.eligibility.issues.joinToString(" "))
        }
        if (!summary.applicable) pending += "Curvas de nivel no generadas: ${summary.reason}"

        // --- fotografías y anexos
        val photos = allResults.map { ReportPhotoEntry(it.point.code, it.point.description, it.point.photoCaption, files.photo(it.point.sketchPhotoPath)?.absolutePath) }
        val noPhoto = photos.filter { it.photoFilePath == null }.map { it.code }
        if (noPhoto.isNotEmpty()) pending += "Puntos sin fotografía: ${noPhoto.joinToString()}."
        val annexList = annexes.list(projectId)
        val reportAnnexes = annexList.mapIndexed { i, a ->
            val related = listOfNotNull(a.pointId?.let { id -> pointById[id]?.point?.code?.let { "punto $it" } }, a.reportSection?.let { "sección $it" }).joinToString("; ").ifEmpty { null }
            ReportAnnex(i + 1, a.title, a.description, a.originalName, AnnexRules.typeLabel(a.originalName), a.addedAt, related, AnnexRules.kindOf(a.mimeType), files.annex(a.storedFile).absolutePath, a.sizeBytes)
        }

        // --- memoria descriptiva
        val memo = listOf(
            "objetivo" to project.purpose, "ubicación" to project.location, "descripción del terreno" to project.siteCharacteristics, "procedimiento de campo" to project.fieldProcedure,
            "responsable(s)" to project.responsible, "fecha" to project.date,
        ).filter { it.second.isNullOrBlank() }.map { it.first }
        if (memo.isNotEmpty()) pending.add(0, "Memoria descriptiva incompleta; pendiente de completar: ${memo.joinToString()}.")
        if (project.name.isBlank()) pending.add(0, "El proyecto no tiene nombre.")

        val instruments = buildString {
            append("Nivel de ingeniero: ").append(project.levelInstrument?.takeIf { it.isNotBlank() } ?: "(marca/modelo pendiente de completar)")
            append(" · Teodolito: ").append(project.theodoliteInstrument?.takeIf { it.isNotBlank() } ?: "(marca/modelo pendiente de completar)")
            project.instrument?.takeIf { it.isNotBlank() }?.let { append(" · Instrumento registrado en una versión anterior: ").append(it) }
        }
        val info = ReportProjectInfo(
            name = project.name, purpose = project.purpose, location = project.location, terrainDescription = project.siteCharacteristics, date = project.date,
            responsible = project.responsible, instruments = instruments, coordinateSystem = coordinateSystemText(project), unitsDistance = project.unitsDistance,
            unitsAngle = project.unitsAngle, fieldProcedure = project.fieldProcedure, observations = project.observations, isDemo = project.isDemo,
        )
        return ReportData(
            project = info, levelRuns = levelRuns, radiationStations = stations, points = pointRows, plan = plan, planState = rad.state, contourSummary = summary,
            photos = photos, annexes = reportAnnexes, pendingItems = pending, generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)),
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    private fun typeLabel(t: ReadingType) = when (t) { ReadingType.BS -> "VA"; ReadingType.IS -> "VI"; ReadingType.FS -> "VD" }
}
