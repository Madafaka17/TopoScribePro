package com.toposcribe.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.toposcribe.app.data.repository.normalizeCode
import com.toposcribe.calc.AngleConvention
import com.toposcribe.calc.ContourCandidate
import com.toposcribe.calc.DistanceKind
import com.toposcribe.calc.ElevationSource
import com.toposcribe.calc.HorizontalKind
import com.toposcribe.calc.LevelingObservationCalcInput
import com.toposcribe.calc.ReadingType
import com.toposcribe.calc.RadiationInput
import com.toposcribe.calc.StationOrientation
import com.toposcribe.calc.VerticalKind
import com.toposcribe.calc.computeDifferentialLeveling
import com.toposcribe.calc.computeRadiation
import com.toposcribe.calc.evaluateContourEligibility
import com.toposcribe.calc.inputFingerprint
import kotlin.math.abs

data class TestOutcome(val label: String, val pass: Boolean, val detail: String)

/**
 * Casos de prueba de la especificación, ejecutados en memoria con las mismas funciones de cálculo
 * que usa la app. NO escriben nada en la base de datos ni tocan proyectos reales.
 */
private fun runLiveTests(): List<TestOutcome> {
    val out = mutableListOf<TestOutcome>()
    fun near(a: Double, b: Double, eps: Double = 1e-6) = abs(a - b) <= eps
    fun f(v: Double) = "%.3f".format(v)

    run {
        val r = computeDifferentialLeveling(listOf(LevelingObservationCalcInput(1, 1, 1, ReadingType.BS, 1.25, false), LevelingObservationCalcInput(2, 2, 2, ReadingType.FS, 0.85, false)), 100.0)
        val hi = r.rows[0].heightOfInstrument; val z = r.rows[1].elevation
        out += TestOutcome("Nivelación: BM 100.000, VA 1.250, VD 0.850", near(hi, 101.25) && near(z, 100.4), "HI = ${f(hi)} (esperado 101.250); nueva cota = ${f(z)} (esperado 100.400)")
    }
    run {
        val s = computeRadiation(RadiationInput(500.0, 1000.0, StationOrientation(0.0, 0.0, AngleConvention.HORARIO), HorizontalKind.AZIMUT, 90.0, 10.0, DistanceKind.HORIZONTAL)).step!!
        out += TestOutcome("Radiación: E 500.000, N 1000.000, d 10.000, Az 90°", near(s.east, 510.0) && near(s.north, 1000.0), "E = ${f(s.east)} (esperado 510.000); N = ${f(s.north)} (esperado 1000.000)")
    }
    run {
        val book = listOf(
            LevelingObservationCalcInput(1, 1, 1, ReadingType.BS, 1.250, false), LevelingObservationCalcInput(2, 2, 2, ReadingType.FS, 0.850, true, 20.0),
            LevelingObservationCalcInput(3, 3, 2, ReadingType.BS, 1.100, false), LevelingObservationCalcInput(4, 4, 3, ReadingType.IS, 1.500, false, 15.0),
            LevelingObservationCalcInput(5, 5, 4, ReadingType.FS, 0.700, false, 25.0),
        )
        val r = computeDifferentialLeveling(book, 100.0)
        out += TestOutcome("Cambio de estación y distancia acumulada", near(r.finalElevation, 100.8) && near(r.rows.last().cumulativeDistance ?: -1.0, 60.0) && r.arithmeticCheckOk,
            "cota final ${f(r.finalElevation)} (esperado 100.800); distancia acumulada ${f(r.rows.last().cumulativeDistance ?: -1.0)} (esperado 60.000); ΣVA−ΣVD ${f(r.checkValue)} = Δcota")
    }
    run {
        val s = computeRadiation(RadiationInput(500.0, 1000.0, StationOrientation(60.0, 0.0, AngleConvention.HORARIO), HorizontalKind.LECTURA_HORIZONTAL, 30.0, 10.0, DistanceKind.HORIZONTAL)).step!!
        out += TestOutcome("Orientación del teodolito (referencia Az 60°, lectura 30°)", near(s.azimuthDeg, 90.0) && near(s.east, 510.0), "azimut = ${f(s.azimuthDeg)}° (esperado 90.000°)")
    }
    run {
        val s = computeRadiation(RadiationInput(0.0, 0.0, StationOrientation(0.0, 0.0, AngleConvention.HORARIO), HorizontalKind.AZIMUT, 0.0, 50.0, DistanceKind.INCLINADA, VerticalKind.CENITAL, 80.0)).step!!
        out += TestOutcome("Distancia inclinada: Ds 50.000, cenital 80°", near(s.horizontalDistance, 49.2404, 1e-3), "d horizontal = ${f(s.horizontalDistance)} (esperado 49.240)")
    }
    run {
        val dup = listOf("BM-1", "P1", "bm-1 ").groupingBy { normalizeCode(it) }.eachCount().filter { it.value > 1 }.keys
        out += TestOutcome("Códigos duplicados (sin distinguir mayúsculas ni espacios)", dup == setOf("BM-1"), "duplicados detectados: $dup")
    }
    run {
        val base = listOf(1L to (0.0 to 0.0), 2L to (10.0 to 0.0), 3L to (10.0 to 10.0), 4L to (0.0 to 10.0))
        val noElev = base.map { (id, p) -> ContourCandidate(id, "P$id", p.first, p.second, null, ElevationSource.NINGUNA) }
        val e = evaluateContourEligibility(noElev)
        out += TestOutcome("Puntos sin cota no producen curvas", !e.eligible && e.usable.isEmpty(), "elegible = ${e.eligible}; ${e.issues.firstOrNull() ?: ""}")
    }
    run {
        val a = inputFingerprint(listOf("1|1.250")); val b = inputFingerprint(listOf("1|1.260"))
        out += TestOutcome("Editar una lectura marca el resultado como desactualizado", a != b, "la huella de las lecturas cambia al editar 1.250 → 1.260")
    }
    return out
}

@Composable
fun TestsDialog(onDismiss: () -> Unit) {
    var rerun by remember { mutableIntStateOf(0) }
    val results = remember(rerun) { runLiveTests() }
    val allPass = results.all { it.pass }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (allPass) "Casos de prueba: todo conforme" else "Casos de prueba: hay fallas") },
        text = {
            Column {
                Text("Se ejecutan en memoria; no modifican ningún proyecto.", style = MaterialTheme.typography.bodySmall)
                LazyColumn {
                    items(results) { r ->
                        Row(Modifier.padding(vertical = 6.dp)) {
                            Icon(if (r.pass) Icons.Filled.CheckCircle else Icons.Filled.Cancel, contentDescription = null, tint = if (r.pass) Color(0xFF1F6F5C) else MaterialTheme.colorScheme.error)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(r.label, style = MaterialTheme.typography.labelMedium)
                                Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = { TextButton(onClick = { rerun++ }) { Text("Ejecutar de nuevo") } },
    )
}
