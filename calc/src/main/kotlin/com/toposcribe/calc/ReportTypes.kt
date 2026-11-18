package com.toposcribe.calc

/**
 * Formas de datos del informe PDF, compartidas entre :app (las arma desde Room) y :io (las
 * dibuja) -- viven en :calc por la misma razón que PlanData: :io no puede depender de :app.
 */
data class ReportProjectInfo(
    val name: String,
    val purpose: String?,
    val location: String?,
    val siteCharacteristics: String?,
    val date: String?,
    val responsible: String?,
    val instrument: String?,
    val instrumentTypeLabel: String,
    val coordinateSystemText: String,
    val unitsDistance: String,
    val unitsAngle: String,
    val fieldProcedure: String?,
    val observations: String?,
    val isDemo: Boolean,
)

data class ReportResultRow(
    val code: String,
    val description: String?,
    val east: Double?,
    val north: Double?,
    val elevation: Double?,
    val roleLabel: String,
    val origin: String,
)

data class ReportLevelingCheck(
    val runName: String,
    val sumBacksight: Double,
    val sumForesight: Double,
    val checkValue: Double,
    val startElevation: Double,
    val finalElevation: Double,
    val arithmeticCheckOk: Boolean,
    val knownEndElevation: Double?,
    val fieldMisclosure: Double?,
)

data class ReportTraverseStation(
    val pointCode: String,
    val rawEast: Double?,
    val rawNorth: Double?,
    val adjustedEast: Double?,
    val adjustedNorth: Double?,
)

data class ReportTraverseClosure(
    val traverseName: String,
    val typeLabel: String,
    val angularMisclosureDeg: Double?,
    val fE: Double,
    val fN: Double,
    val fL: Double,
    val relativePrecision: String,
    val stations: List<ReportTraverseStation>,
)

/** photoFilePath es una ruta de archivo absoluta en el dispositivo (o null si el punto no tiene
 * foto); :io decide si existe/es legible al momento de dibujar -- nunca se asume ni se inventa. */
data class ReportPhoto(
    val code: String,
    val description: String?,
    val caption: String?,
    val segment: String?,
    val chainage: Double?,
    val photoFilePath: String?,
)

data class ReportData(
    val project: ReportProjectInfo,
    val results: List<ReportResultRow>,
    val formulasUsed: List<FormulaEntry>,
    val levelingChecks: List<ReportLevelingCheck>,
    val traverseClosures: List<ReportTraverseClosure>,
    // Reutiliza la MISMA PlanData que dibuja la pantalla del Plano CAD -- la sección 3 del PDF
    // nunca es una segunda implementación del dibujo, solo otro "lienzo" de destino (PdfDocument
    // en vez de Compose Canvas) para el mismo CadMath/dibujo de puntos y líneas.
    val planData: PlanData,
    val photos: List<ReportPhoto>,
)
