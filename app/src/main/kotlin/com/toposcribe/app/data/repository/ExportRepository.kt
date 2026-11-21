package com.toposcribe.app.data.repository

import android.content.Context
import android.net.Uri
import com.toposcribe.calc.ReadingType
import com.toposcribe.data.backup.BackupException
import com.toposcribe.data.backup.BackupExportResult
import com.toposcribe.data.backup.BackupImportResult
import com.toposcribe.data.backup.ProjectBackupService
import com.toposcribe.io.buildCsv
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun n3(v: Double?): String = if (v == null) "" else String.format(Locale.US, "%.3f", v)

/**
 * Exportación de datos a CSV (se abre en Excel). La libreta original solo trae datos INGRESADOS;
 * el archivo de resultados trae únicamente valores CALCULADOS: cada fila lleva la columna
 * «Naturaleza» y los encabezados de resultados dicen «(calculado)».
 */
@Singleton
class ExportRepository @Inject constructor(
    private val leveling: LevelingRepository,
    private val radiation: RadiationRepository,
    private val results: ResultsRepository,
    private val points: PointRepository,
) {
    suspend fun levelingBookCsv(projectId: Long): String {
        val rows = mutableListOf<List<String>>()
        for (run in leveling.runs(projectId)) {
            val ev = leveling.evaluate(run.id) ?: continue
            for (r in ev.rows) {
                val o = r.observation
                rows += listOf("INGRESADO", run.name, o.sequenceOrder.toString(), r.pointCode, when (o.readingType) { ReadingType.BS -> "VA"; ReadingType.IS -> "VI"; ReadingType.FS -> "VD" },
                    n3(o.readingValue), n3(o.partialDistance), if (o.isTurningPoint) "Sí" else "", o.rawNotes.orEmpty(), r.correction.orEmpty())
            }
        }
        return buildCsv(listOf("Naturaleza", "Nivelación", "N°", "Punto", "Tipo de lectura", "Lectura de mira (dato ingresado)", "Distancia parcial (dato ingresado)", "Punto de cambio", "Observaciones", "Corrección registrada"), rows)
    }

    suspend fun radiationBookCsv(projectId: Long): String {
        val rows = radiation.evaluate(projectId).rows.map { r ->
            val o = r.observation
            listOf("INGRESADO", r.stationCode, r.targetCode, r.setup?.let { orientationText(it) }.orEmpty(), o.azimuth?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                o.horizontalAngle?.let { String.format(Locale.US, "%.6f", it) } ?: "", o.zenithAngle?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                o.verticalAngle?.let { String.format(Locale.US, "%.6f", it) } ?: "", n3(o.horizontalDistance), n3(o.slopeDistance), o.rawNotes.orEmpty(), r.correction.orEmpty())
        }
        return buildCsv(
            listOf("Naturaleza", "Estación", "Punto visado", "Orientación de la estación", "Azimut ingresado (°)", "Lectura horizontal ingresada (°)", "Cenital ingresado (°)", "Vertical ingresado (°)",
                "Distancia horizontal ingresada", "Distancia inclinada ingresada", "Descripción", "Corrección registrada"),
            rows,
        )
    }

    suspend fun resultsCsv(projectId: Long): String {
        val rows = results.getProjectResults(projectId).sortedWith(compareBy(NaturalCodeComparator) { it.point.code }).map { r ->
            listOf("CALCULADO", r.point.code, r.point.description.orEmpty(), procedureLabel(r.point.instrumentRef), n3(r.east), n3(r.north), n3(r.elevation), r.planimetryOrigin, r.elevationSource.labelEs)
        }
        return buildCsv(listOf("Naturaleza", "Código", "Descripción", "Procedimiento", "Este (calculado)", "Norte (calculado)", "Cota (calculada)", "Origen de las coordenadas", "Origen de la cota"), rows)
    }
}

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: ProjectBackupService,
    private val files: AppFiles,
) {
    suspend fun export(projectId: Long, destination: Uri): Outcome<BackupExportResult> = withContext(Dispatchers.IO) {
        try {
            val out = context.contentResolver.openOutputStream(destination) ?: return@withContext Outcome.Fail("No se pudo escribir en la ubicación elegida.")
            out.use { Outcome.Ok(service.export(projectId, it)) }
        } catch (e: BackupException) { Outcome.Fail(e.message ?: "Error en el respaldo.") }
        catch (e: Exception) { Outcome.Fail("No se pudo crear el respaldo: ${e.message ?: e::class.simpleName}") }
    }

    suspend fun import(source: Uri): Outcome<BackupImportResult> = withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(source) ?: return@withContext Outcome.Fail("No se pudo leer el archivo elegido.")
            input.use { Outcome.Ok(service.import(it, files.workDir)) }
        } catch (e: BackupException) { Outcome.Fail(e.message ?: "Respaldo no válido.") }
        catch (e: Exception) { Outcome.Fail("No se pudo restaurar el respaldo: ${e.message ?: e::class.simpleName}") }
    }
}
