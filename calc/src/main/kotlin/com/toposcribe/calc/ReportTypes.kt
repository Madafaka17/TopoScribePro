package com.toposcribe.calc

import java.util.Locale

/**
 * Datos del informe PDF. :app los arma desde Room (solo lectura de lo guardado); :io los dibuja.
 * Viven en :calc porque :io no puede depender de :app.
 */
data class ReportProjectInfo(
    val name: String,
    val purpose: String?,
    val location: String?,
    val terrainDescription: String?,
    val date: String?,
    val responsible: String?,
    val instruments: String,
    val coordinateSystem: String,
    val unitsDistance: String,
    val unitsAngle: String,
    val fieldProcedure: String?,
    val observations: String?,
    val isDemo: Boolean,
)

/** Lectura de nivelación tal como se registró en la libreta (dato ingresado). */
data class ReportLevelInputRow(
    val sequence: Int,
    val station: Int,
    val pointCode: String,
    val typeLabel: String,
    val reading: Double,
    val partialDistance: Double?,
    val turningPoint: Boolean,
    val notes: String?,
    /** Si la lectura fue corregida: texto «original X → vigente Y». */
    val correction: String?,
)

/** Fila calculada de la nivelación (cálculo). */
data class ReportLevelCalcRow(
    val station: Int,
    val pointCode: String,
    val typeLabel: String,
    val reading: Double,
    val partialDistance: Double?,
    val cumulativeDistance: Double?,
    val operation: String,
    val elevation: Double,
)

data class ReportLevelRun(
    val name: String,
    val startBmCode: String,
    val startElevation: Double,
    val inputRows: List<ReportLevelInputRow>,
    val calcRows: List<ReportLevelCalcRow>,
    val sumBacksight: Double,
    val sumForesight: Double,
    val finalElevation: Double,
    val arithmeticCheckOk: Boolean,
    val endBmCode: String?,
    val knownEndElevation: Double?,
    val misclosure: Double?,
    val state: ResultState,
    val warnings: List<String>,
    val profile: LevelingProfile,
)

data class ReportRadiationRow(
    val code: String,
    val description: String?,
    /** Lectura original tal como se anotó (azimut u horizontal), con vertical y tipo de distancia. */
    val originalReading: String,
    val correction: String?,
    val azimuthText: String?,
    val distanceUsed: Double?,
    val deltaE: Double?,
    val deltaN: Double?,
    val east: Double?,
    val north: Double?,
    val formulaLines: List<String>,
    val errors: List<String>,
)

data class ReportRadiationStation(
    val stationCode: String,
    val stationEast: Double?,
    val stationNorth: Double?,
    val coordinateOrigin: String,
    val instrumentHeight: Double?,
    val orientationText: String,
    val rows: List<ReportRadiationRow>,
)

data class ReportPointRow(
    val code: String,
    val description: String?,
    val procedure: String,
    val east: Double?,
    val north: Double?,
    val elevation: Double?,
    val elevationSource: String,
)

data class ReportPhotoEntry(
    val code: String,
    val description: String?,
    val caption: String?,
    val photoFilePath: String?,
)

enum class AnnexFileKind { IMAGE, PDF, OTHER }

data class ReportAnnex(
    val number: Int,
    val title: String,
    val description: String?,
    val originalName: String,
    val typeLabel: String,
    val addedAt: String,
    val relatedTo: String?,
    val kind: AnnexFileKind,
    val filePath: String,
    val sizeBytes: Long,
)

data class ReportContourSummary(
    val applicable: Boolean,
    val equidistance: Double,
    val levels: Int,
    val method: String,
    val usedPoints: Int,
    val reason: String?,
)

data class ReportData(
    val project: ReportProjectInfo,
    val levelRuns: List<ReportLevelRun>,
    val radiationStations: List<ReportRadiationStation>,
    val points: List<ReportPointRow>,
    val plan: PlanModel,
    val planState: ResultState,
    val contourSummary: ReportContourSummary,
    val photos: List<ReportPhotoEntry>,
    val annexes: List<ReportAnnex>,
    /** Datos pendientes y limitaciones, redactados por la app a partir de lo realmente faltante. */
    val pendingItems: List<String>,
    val generatedAt: String,
    val appVersion: String,
)

fun fmt3(value: Double?): String = if (value == null) "—" else String.format(Locale.US, "%.3f", value)
fun fmtN(value: Double?, digits: Int): String = if (value == null) "—" else String.format(Locale.US, "%.${digits}f", value)
