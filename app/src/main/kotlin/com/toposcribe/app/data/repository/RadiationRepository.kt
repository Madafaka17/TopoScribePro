package com.toposcribe.app.data.repository

import androidx.room.withTransaction
import com.toposcribe.calc.AngleConvention
import com.toposcribe.calc.DistanceKind
import com.toposcribe.calc.HorizontalKind
import com.toposcribe.calc.RadiationInput
import com.toposcribe.calc.RadiationOutcome
import com.toposcribe.calc.ResultState
import com.toposcribe.calc.StationOrientation
import com.toposcribe.calc.VerticalKind
import com.toposcribe.calc.bearingToAzimuth
import com.toposcribe.calc.computeRadiation
import com.toposcribe.calc.formatDegreesAsDms
import com.toposcribe.calc.inputFingerprint
import com.toposcribe.calc.resultState
import com.toposcribe.data.local.TopoScribeDatabase
import com.toposcribe.data.local.entity.CalcSnapshotEntity
import com.toposcribe.data.local.entity.ComputedPointEntity
import com.toposcribe.data.local.entity.PlanimetryMethod
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import com.toposcribe.data.local.entity.SourceObservationRef
import com.toposcribe.data.local.entity.StationSetupEntity
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

const val TABLE_PLAN_OBS = "planimetry_observations"

/** Datos de una lectura de radiación ya interpretados por el formulario. */
data class RadiationReadingForm(
    val stationPointId: Long,
    val setupId: Long,
    val targetCode: String,
    val targetDescription: String?,
    val horizontalKind: HorizontalKind,
    val horizontalAngleDeg: Double,
    val distance: Double,
    val distanceKind: DistanceKind,
    val verticalKind: VerticalKind?,
    val verticalDeg: Double?,
    val notes: String?,
)

data class RadiationRow(
    val observation: PlanimetryObservationEntity,
    val stationCode: String,
    val targetCode: String,
    val targetDescription: String?,
    val setup: StationSetupEntity?,
    val originalReading: String,
    val correction: String?,
    val outcome: RadiationOutcome?,
    val errors: List<String>,
    /** Observación heredada de una opción retirada (poligonal): se conserva pero no se recalcula. */
    val legacyNote: String?,
) {
    val step get() = outcome?.step
}

data class RadiationEvaluation(
    val rows: List<RadiationRow>,
    val fingerprint: String,
    val state: ResultState,
    val stationCoordinates: Map<Long, Pair<Double, Double>>,
)

@Singleton
class RadiationRepository @Inject constructor(
    private val db: TopoScribeDatabase,
    private val history: EditHistoryRepository,
    private val points: PointRepository,
) {
    fun observeObservations(projectId: Long): Flow<List<PlanimetryObservationEntity>> = db.planimetryObservationDao().observeByProject(projectId)
    suspend fun observations(projectId: Long): List<PlanimetryObservationEntity> = db.planimetryObservationDao().listSnapshot(projectId)

    // ---- alta / edición / baja ----

    private fun orientationOf(s: StationSetupEntity) = StationOrientation(s.baseAzimuthDecimal, s.backsightReadingDeg, s.angleConvention)

    private suspend fun validate(projectId: Long, form: RadiationReadingForm): String? {
        val station = db.pointDao().getById(form.stationPointId)
        if (station == null || station.projectId != projectId) return "La estación ocupada no existe en este proyecto."
        val setup = db.stationSetupDao().getById(form.setupId)
        if (setup == null || setup.projectId != projectId || setup.occupiedPointId != station.id) return "Defina la estación ocupada con su orientación y convenio angular antes de registrar lecturas."
        val code = form.targetCode.trim()
        if (code.isEmpty()) return "El código del punto visado es obligatorio."
        if (normalizeCode(code) == normalizeCode(station.code)) return "La estación ocupada y el punto visado no pueden ser el mismo."
        val outcome = computeRadiation(
            RadiationInput(0.0, 0.0, orientationOf(setup), form.horizontalKind, form.horizontalAngleDeg, form.distance, form.distanceKind, form.verticalKind, form.verticalDeg),
        )
        return outcome.errors.firstOrNull()
    }

    private fun toEntity(base: PlanimetryObservationEntity, form: RadiationReadingForm, targetId: Long, setup: StationSetupEntity): PlanimetryObservationEntity {
        val inclined = form.distanceKind == DistanceKind.INCLINADA
        return base.copy(
            occupiedPointId = form.stationPointId, targetPointId = targetId, stationSetupId = setup.id,
            method = if (form.horizontalKind == HorizontalKind.AZIMUT) PlanimetryMethod.ACIMUT_DISTANCIA else PlanimetryMethod.ANGULO_DISTANCIA,
            azimuth = if (form.horizontalKind == HorizontalKind.AZIMUT) form.horizontalAngleDeg else null,
            horizontalAngle = if (form.horizontalKind == HorizontalKind.LECTURA_HORIZONTAL) form.horizontalAngleDeg else null,
            bearing = null,
            horizontalDistance = if (inclined) null else form.distance,
            slopeDistance = if (inclined) form.distance else null,
            zenithAngle = if (inclined && form.verticalKind == VerticalKind.CENITAL) form.verticalDeg else null,
            verticalAngle = if (inclined && form.verticalKind == VerticalKind.VERTICAL) form.verticalDeg else null,
            instrumentHeight = setup.instrumentHeightAi, rawNotes = form.notes?.ifBlank { null },
        )
    }

    private suspend fun resolveTarget(projectId: Long, form: RadiationReadingForm): Outcome<Long> {
        points.findByCode(projectId, form.targetCode)?.let { return Outcome.Ok(it.id) }
        return when (val r = points.createSimple(projectId, form.targetCode, form.targetDescription, "TEODOLITO")) {
            is Outcome.Fail -> r
            is Outcome.Ok -> Outcome.Ok(r.value.id)
        }
    }

    suspend fun add(projectId: Long, form: RadiationReadingForm): Outcome<Long> {
        validate(projectId, form)?.let { return Outcome.Fail(it) }
        val setup = db.stationSetupDao().getById(form.setupId)!!
        val target = resolveTarget(projectId, form)
        if (target is Outcome.Fail) return target
        val now = Instant.now().toString()
        val order = (db.planimetryObservationDao().listSnapshot(projectId).maxOfOrNull { it.sequenceOrder ?: 0 } ?: 0) + 1
        val base = PlanimetryObservationEntity(projectId = projectId, occupiedPointId = form.stationPointId, targetPointId = (target as Outcome.Ok).value, method = PlanimetryMethod.ACIMUT_DISTANCIA, sequenceOrder = order, createdAt = now)
        return Outcome.Ok(db.planimetryObservationDao().insert(toEntity(base, form, target.value, setup)))
    }

    suspend fun update(before: PlanimetryObservationEntity, form: RadiationReadingForm): Outcome<Unit> {
        validate(before.projectId, form)?.let { return Outcome.Fail(it) }
        val setup = db.stationSetupDao().getById(form.setupId)!!
        val target = resolveTarget(before.projectId, form)
        if (target is Outcome.Fail) return target
        val after = toEntity(before, form, (target as Outcome.Ok).value, setup).copy(updatedAt = Instant.now().toString())
        val oldTarget = db.pointDao().getById(before.targetPointId)?.code ?: "?"
        val newTarget = db.pointDao().getById(after.targetPointId)?.code ?: "?"
        history.logChanges(
            TABLE_PLAN_OBS, before.id,
            listOf(
                EditHistoryRepository.Change("target", oldTarget, newTarget),
                EditHistoryRepository.Change("azimuth", before.azimuth?.let(::deg), after.azimuth?.let(::deg)),
                EditHistoryRepository.Change("horizontal_angle", before.horizontalAngle?.let(::deg), after.horizontalAngle?.let(::deg)),
                EditHistoryRepository.Change("horizontal_distance", before.horizontalDistance?.let(::m3), after.horizontalDistance?.let(::m3)),
                EditHistoryRepository.Change("slope_distance", before.slopeDistance?.let(::m3), after.slopeDistance?.let(::m3)),
                EditHistoryRepository.Change("zenith_angle", before.zenithAngle?.let(::deg), after.zenithAngle?.let(::deg)),
                EditHistoryRepository.Change("vertical_angle", before.verticalAngle?.let(::deg), after.verticalAngle?.let(::deg)),
                EditHistoryRepository.Change("raw_notes", before.rawNotes, after.rawNotes),
            ),
        )
        db.planimetryObservationDao().update(after)
        return Outcome.Ok(Unit)
    }

    suspend fun delete(obs: PlanimetryObservationEntity) = db.planimetryObservationDao().softDelete(obs.id, Instant.now().toString())

    private fun deg(v: Double) = formatDegreesAsDms(v, 2)
    private fun m3(v: Double) = String.format(Locale.US, "%.3f", v)

    // ---- texto de la lectura original ----

    fun describeOriginal(o: PlanimetryObservationEntity): String {
        val parts = mutableListOf<String>()
        o.azimuth?.let { parts += "Az ${deg(it)}" }
        o.horizontalAngle?.let { parts += "Hz ${deg(it)}" }
        o.bearing?.let { parts += "Rumbo $it" }
        o.zenithAngle?.let { parts += "Cenital ${deg(it)}" }
        o.verticalAngle?.let { parts += "Vertical ${deg(it)}" }
        o.horizontalDistance?.let { parts += "d horizontal ${m3(it)}" }
        o.slopeDistance?.let { parts += "d inclinada ${m3(it)}" }
        return parts.joinToString(" · ").ifEmpty { "—" }
    }

    // ---- evaluación pura + cálculo ----

    suspend fun evaluate(projectId: Long): RadiationEvaluation {
        syncControlPoints(db, projectId)
        val pointList = db.pointDao().listByProject(projectId)
        val byId = pointList.associateBy { it.id }
        val setups = db.stationSetupDao().listSnapshot(projectId)
        val setupById = setups.associateBy { it.id }
        val obs = db.planimetryObservationDao().listSnapshot(projectId).sortedBy { it.sequenceOrder ?: Int.MAX_VALUE }.sortedBy { it.id }
        val histories = history.historyByRow(TABLE_PLAN_OBS)

        // Coordenadas conocidas de partida: datos fijos y resultados históricos que no son radiación.
        val coords = HashMap<Long, Pair<Double, Double>>()
        val baseRows = db.computedPointDao().listCurrentSnapshot(projectId)
            .filter { it.method != "radiacion" && it.east != null && it.north != null }
            .sortedBy { MethodPriority.rank(it.method) }
        for (r in baseRows) coords.putIfAbsent(r.pointId, r.east!! to r.north!!)

        fun setupFor(o: PlanimetryObservationEntity): StationSetupEntity? =
            o.stationSetupId?.let { setupById[it] }
                ?: setups.filter { it.occupiedPointId == o.occupiedPointId }.maxWithOrNull(compareBy({ it.createdAt }, { it.id }))

        data class Work(val o: PlanimetryObservationEntity, var outcome: RadiationOutcome? = null, val errors: MutableList<String> = mutableListOf(), var legacy: String? = null)
        val work = obs.map { Work(it) }
        for (w in work) if (w.o.traverseId != null) w.legacy = "Dato histórico de una poligonal (opción retirada): se conserva, no se recalcula."

        var pending = work.filter { it.legacy == null }
        var progress = true
        while (progress && pending.isNotEmpty()) {
            progress = false
            val next = mutableListOf<Work>()
            for (w in pending) {
                val o = w.o
                val station = coords[o.occupiedPointId]
                if (station == null) { next += w; continue }
                val setup = setupFor(o)
                val az = o.azimuth ?: o.bearing?.let { bearingToAzimuth(it) }
                val kind: HorizontalKind
                val angle: Double
                if (az != null) { kind = HorizontalKind.AZIMUT; angle = az }
                else if (o.horizontalAngle != null) { kind = HorizontalKind.LECTURA_HORIZONTAL; angle = o.horizontalAngle!! }
                else { w.errors += "Falta el azimut o la lectura horizontal."; continue }
                if (kind == HorizontalKind.LECTURA_HORIZONTAL && setup == null) {
                    w.errors += "La lectura horizontal necesita la orientación de la estación (referencia y convenio): defina la estación."
                    continue
                }
                val orientation = setup?.let { orientationOf(it) } ?: StationOrientation(0.0, 0.0, AngleConvention.HORARIO)
                val hd = o.horizontalDistance
                val sd = o.slopeDistance
                val input = when {
                    hd != null -> RadiationInput(station.first, station.second, orientation, kind, angle, hd, DistanceKind.HORIZONTAL)
                    sd != null -> RadiationInput(
                        station.first, station.second, orientation, kind, angle, sd, DistanceKind.INCLINADA,
                        if (o.zenithAngle != null) VerticalKind.CENITAL else if (o.verticalAngle != null) VerticalKind.VERTICAL else null,
                        o.zenithAngle ?: o.verticalAngle,
                    )
                    else -> { w.errors += "Falta la distancia."; continue }
                }
                val out = computeRadiation(input)
                w.outcome = out
                w.errors += out.errors
                val step = out.step
                if (step != null) {
                    if (!coords.containsKey(o.targetPointId)) coords[o.targetPointId] = step.east to step.north
                    progress = true
                }
            }
            pending = next
        }
        for (w in pending) {
            val st = byId[w.o.occupiedPointId]?.code ?: "?"
            w.errors += "La estación «$st» no tiene coordenadas conocidas ni calculadas: declare sus coordenadas (reales o locales) en la estación."
        }

        val rows = work.map { w ->
            val h = histories[w.o.id]
            val corr = h?.takeIf { it.isNotEmpty() }?.let {
                "corregido (${it.map { c -> c.field }.distinct().size} campo(s)): " + it.groupBy { c -> c.field }.entries.joinToString("; ") { (f, l) -> "$f original ${l.first().oldValue ?: "—"}" }
            }
            RadiationRow(
                observation = w.o, stationCode = byId[w.o.occupiedPointId]?.code ?: "?", targetCode = byId[w.o.targetPointId]?.code ?: "?",
                targetDescription = byId[w.o.targetPointId]?.description, setup = setupFor(w.o), originalReading = describeOriginal(w.o),
                correction = corr, outcome = w.outcome, errors = w.errors.distinct(), legacyNote = w.legacy,
            )
        }

        val fixedParts = pointList.filter { it.fixedEast != null || it.fixedNorth != null }.map { "pt|${it.id}|${it.fixedEast}|${it.fixedNorth}" }
        val fp = inputFingerprint(
            fixedParts + setups.map { "setup|${it.id}|${it.occupiedPointId}|${it.baseAzimuthDecimal}|${it.backsightReadingDeg}|${it.angleConvention}" } +
                obs.filter { it.traverseId == null }.map { "obs|${it.id}|${it.occupiedPointId}|${it.targetPointId}|${it.stationSetupId}|${it.method}|${it.azimuth}|${it.bearing}|${it.horizontalAngle}|${it.horizontalDistance}|${it.slopeDistance}|${it.zenithAngle}|${it.verticalAngle}" },
        )
        val snap = db.calcSnapshotDao().find(projectId, KIND_PLANIMETRIA, 0)
        var state = resultState(snap?.inputHash, fp)
        if (snap == null && db.computedPointDao().listCurrentSnapshot(projectId).any { it.method == "radiacion" }) state = ResultState.VERSION_ANTERIOR
        return RadiationEvaluation(rows, fp, state, coords)
    }

    /** Guarda las coordenadas por radiación (las anteriores se retiran) y la huella del cálculo. */
    suspend fun compute(projectId: Long): Outcome<RadiationEvaluation> {
        val eval = evaluate(projectId)
        val now = Instant.now().toString()
        db.withTransaction {
            db.computedPointDao().retireAllForMethod(projectId, "radiacion")
            for (r in eval.rows) {
                val step = r.step ?: continue
                val inclined = r.observation.horizontalDistance == null
                val formula = "RADIACION" + if (inclined) (if (r.observation.zenithAngle != null) "+REDUCCION-ZENITAL" else "+REDUCCION-VERTICAL") else ""
                db.computedPointDao().insert(
                    ComputedPointEntity(
                        projectId = projectId, pointId = r.observation.targetPointId, east = step.east, north = step.north, elevation = null,
                        method = "radiacion", formulaId = formula, sourceObservationIds = listOf(SourceObservationRef(TABLE_PLAN_OBS, r.observation.id)), computedAt = now,
                    ),
                )
            }
            db.calcSnapshotDao().upsert(CalcSnapshotEntity(projectId = projectId, kind = KIND_PLANIMETRIA, subjectId = 0, inputHash = eval.fingerprint, computedAt = now))
        }
        return Outcome.Ok(evaluate(projectId))
    }
}
