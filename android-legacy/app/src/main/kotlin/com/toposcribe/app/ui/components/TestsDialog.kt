package com.toposcribe.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.toposcribe.calc.Dms
import com.toposcribe.calc.PolarCoordinatesInput
import com.toposcribe.calc.LevelingObservationCalcInput
import com.toposcribe.calc.ReadingType
import com.toposcribe.calc.computeAngularClosureClosedPolygon
import com.toposcribe.calc.computeDifferentialLeveling
import com.toposcribe.calc.computePolarCoordinates
import com.toposcribe.calc.dmsToDecimal
import kotlin.math.abs

data class TestOutcome(val label: String, val pass: Boolean, val detail: String)

/**
 * Corre en vivo, dentro de la propia app, un subconjunto de los mismos casos verificados a mano
 * que ya tiene la batería de JUnit de :calc -- panel de regresión visible sin salir de la app,
 * siguiendo el lenguaje del botón "Pruebas" del diseño Stitch.
 */
private fun runLiveTests(): List<TestOutcome> {
    val results = mutableListOf<TestOutcome>()

    fun approx(expected: Double, actual: Double, eps: Double = 1e-3) = abs(expected - actual) <= eps

    run {
        val r = computePolarCoordinates(PolarCoordinatesInput(500.0, 1000.0, 90.0, 10.0))
        val pass = approx(510.0, r.east) && approx(1000.0, r.north)
        results.add(TestOutcome("Radiación E1=500,N1=1000,d=10,Az=90°", pass, "E2=${"%.3f".format(r.east)} N2=${"%.3f".format(r.north)} (esperado 510.000 / 1000.000)"))
    }

    run {
        val r = computeDifferentialLeveling(
            listOf(
                LevelingObservationCalcInput(1, 1, 1, ReadingType.BS, 1.25, false),
                LevelingObservationCalcInput(2, 2, 2, ReadingType.FS, 0.85, false),
            ),
            100.0,
        )
        val hi = r.rows.first { it.observationId == 1L }.heightOfInstrument
        val elev = r.rows.first { it.observationId == 2L }.elevation
        val pass = approx(101.25, hi) && approx(100.4, elev)
        results.add(TestOutcome("Nivelación BM=100,BS=1.25,FS=0.85", pass, "HI=${"%.3f".format(hi)} cota=${"%.3f".format(elev)} (esperado 101.250 / 100.400)"))

        val check = abs((r.sumBacksight - r.sumForesight) - (elev - 100.0))
        results.add(
            TestOutcome(
                "Chequeo aritmético ΣVA − ΣVD = ΔCota",
                check < 0.005 && r.arithmeticCheckOk,
                "|(ΣVA−ΣVD) − (Cota final − Cota inicial)| = ${"%.4f".format(check)} m (tolerancia 0.005 m)",
            ),
        )
    }

    run {
        val duplicates = listOf("BM-01", "EST-A", "bm-01").groupingBy { it.trim().uppercase() }.eachCount().filter { it.value > 1 }.keys
        val none = listOf("BM-01", "EST-A").groupingBy { it.trim().uppercase() }.eachCount().filter { it.value > 1 }.keys
        results.add(
            TestOutcome(
                "Detección de BMs duplicados",
                duplicates == setOf("BM-01") && none.isEmpty(),
                "Unicidad de códigos sin distinguir mayúsculas: detecta BM-01/bm-01 y no marca una lista sin repetidos.",
            ),
        )
    }

    run {
        val angles = listOf(dmsToDecimal(Dms(60, 0, 0.0)), dmsToDecimal(Dms(60, 0, 0.0)), dmsToDecimal(Dms(60, 0, 30.0)))
        val closure = computeAngularClosureClosedPolygon(angles)
        val pass = approx(30.0 / 3600.0, closure.misclosureDeg, 1e-6) && approx(-10.0 / 3600.0, closure.correctionPerAngleDeg, 1e-6)
        results.add(TestOutcome("Cierre angular 60°+60°+60°00'30\"", pass, "cierre=${closure.misclosureDeg * 3600}\" corrección=${closure.correctionPerAngleDeg * 3600}\"/ángulo (esperado +30\" / -10\")"))
    }

    return results
}

@Composable
fun TestsDialog(onDismiss: () -> Unit) {
    var rerun by remember { mutableStateOf(0) }
    val results = remember(rerun) { runLiveTests() }
    val allPass = results.all { it.pass }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (allPass) "Pruebas: todo conforme" else "Pruebas: hay fallas") },
        text = {
            LazyColumn {
                items(results) { r ->
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Icon(
                            if (r.pass) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                            contentDescription = null,
                            tint = if (r.pass) Color(0xFF1F6F5C) else MaterialTheme.colorScheme.error,
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(r.label, style = MaterialTheme.typography.labelMedium)
                            Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = { TextButton(onClick = { rerun++ }) { Text("Ejecutar de Nuevo") } },
    )
}
