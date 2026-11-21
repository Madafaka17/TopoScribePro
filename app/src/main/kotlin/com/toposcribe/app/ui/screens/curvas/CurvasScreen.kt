package com.toposcribe.app.ui.screens.curvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toposcribe.app.ui.components.LocalNotify
import com.toposcribe.app.ui.components.NoProjectMessage
import com.toposcribe.app.ui.components.NumberField
import com.toposcribe.app.ui.components.PlanCanvas
import com.toposcribe.app.ui.components.SectionCard
import com.toposcribe.app.ui.components.toDecimalOrNull
import com.toposcribe.app.ui.theme.MonoFieldStyle
import com.toposcribe.calc.PlanLayer
import java.util.Locale

private fun f3(v: Double?) = if (v == null) "—" else String.format(Locale.US, "%.3f", v)
private val small = MonoFieldStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp))

@Composable
fun CurvasScreen(modifier: Modifier = Modifier, vm: CurvasViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val notify = LocalNotify.current
    if (ui.projectId == null) { NoProjectMessage(modifier); return }
    var eqText by remember(ui.projectId) { mutableStateOf("") }
    LaunchedEffect(ui.savedEquidistance, ui.projectId) { if (eqText.isEmpty()) eqText = f3(ui.savedEquidistance ?: 1.0) }
    val eq = eqText.toDecimalOrNull()?.takeIf { it > 0.0 }
    LaunchedEffect(eq) { vm.setEquidistance(eq) }
    val a = ui.analysis

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
        item {
            SectionCard("Curvas de nivel") {
                Text("Se generan solo con puntos que tengan Este, Norte y cota válidos (la cota debe venir del nivel de ingeniero o de un control conocido). Tener azimuts y distancias no basta: un punto radiado sin cota no se usa y no se le asigna ninguna.", style = MaterialTheme.typography.bodySmall)
                NumberField("Equidistancia (${ui.units})", eqText, { eqText = it }, error = if (eq == null) "Escriba un número mayor que 0." else null)
                Text("Método: Triangulación de Delaunay (Bowyer-Watson) e interpolación lineal sobre las aristas de cada triángulo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        if (a == null) return@LazyColumn
        if (a.states.anyStale) item { Text("⚠ Hay lecturas modificadas sin recalcular: las cotas/coordenadas usadas aquí son las del último cálculo guardado. Recalcule en Altimetría y Planimetría.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        item {
            SectionCard("Datos disponibles (${a.eligibility.usable.size} utilizable(s) de ${a.candidates.size})") {
                if (a.candidates.isEmpty()) Text("El proyecto no tiene puntos.", style = MaterialTheme.typography.bodySmall)
                a.candidates.forEach { c ->
                    val excluded = a.eligibility.excluded.firstOrNull { it.code == c.code }
                    Text("${c.code}:  E ${f3(c.east)}  N ${f3(c.north)}  Z ${f3(c.elevation)}  — ${c.elevationSource.labelEs}" + (excluded?.let { "  ✘ no se usa: ${it.reason}" } ?: if (c.elevation != null) "  ✔" else ""), style = small, color = if (excluded != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        item {
            SectionCard("Resultado") {
                if (!a.eligibility.eligible) {
                    Text("No se pueden generar curvas. Falta exactamente:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    a.eligibility.issues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                } else if (a.result?.applicable == true) {
                    val r = a.result
                    Text("Curvas calculadas: equidistancia ${f3(r.equidistance)} ${ui.units}, ${r.levels.size} nivel(es) (${f3(r.levels.first())} a ${f3(r.levels.last())}), ${r.triangles.size} triángulo(s)" + if (r.discardedTriangles > 0) " (${r.discardedTriangles} degenerado(s) descartado(s))" else "", style = MaterialTheme.typography.bodySmall)
                    Text("Método: ${r.method}.", style = MaterialTheme.typography.bodySmall)
                } else Text(a.result?.reason ?: "Indique una equidistancia válida.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.save(notify) }, enabled = a.result?.applicable == true, modifier = Modifier.testTag("btn_generar_curvas")) { Text("Generar y usar en plano e informe") }
                    if (ui.savedEquidistance != null) OutlinedButton(onClick = { vm.clear(notify) }) { Text("Quitar curvas") }
                }
                if (ui.savedEquidistance != null) Text("Curvas guardadas con equidistancia ${f3(ui.savedEquidistance)} ${ui.units}: aparecen en el Plano CAD, el DXF y el informe.", style = MaterialTheme.typography.bodySmall)
            }
        }
        val plan = ui.plan
        if (plan != null && a.result?.applicable == true) {
            item {
                SectionCard("Vista previa") {
                    PlanCanvas(plan, setOf(PlanLayer.PUNTOS, PlanLayer.CODIGOS, PlanLayer.TRIANGULACION, PlanLayer.CURVAS), resetKey = 0)
                }
            }
        }
    }
}
