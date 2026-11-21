package com.toposcribe.app.ui.screens.altimetria

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toposcribe.app.data.repository.LevelingEvaluation
import com.toposcribe.app.ui.components.LocalNotify
import com.toposcribe.app.ui.components.NoProjectMessage
import com.toposcribe.app.ui.components.SectionCard
import com.toposcribe.app.ui.components.StateBanner
import com.toposcribe.app.ui.theme.MonoFieldStyle
import com.toposcribe.calc.ReadingType
import com.toposcribe.io.PlanStyle
import com.toposcribe.io.ProfileRenderer
import java.util.Locale

private fun f3(v: Double?) = if (v == null) "—" else String.format(Locale.US, "%.3f", v)
private fun typeLabel(t: ReadingType) = when (t) { ReadingType.BS -> "VA"; ReadingType.IS -> "VI"; ReadingType.FS -> "VD" }
private val small = MonoFieldStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp))

@Composable
fun AltimetriaScreen(modifier: Modifier = Modifier, vm: AltimetriaViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val notify = LocalNotify.current
    if (ui.projectId == null) { NoProjectMessage(modifier); return }
    val ev = ui.eval

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
        item {
            SectionCard("Altimetría con nivel de ingeniero") {
                if (ui.runs.isEmpty()) Text("No hay nivelaciones. Cree una en la pestaña «Libreta de campo» (modo Nivel de ingeniero) con su BM de cota conocida.", style = MaterialTheme.typography.bodySmall)
                if (ui.runs.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ui.runs.forEach { r -> FilterChip(selected = r.id == ev?.run?.id, onClick = { vm.select(r.id) }, label = { Text(r.name, maxLines = 1) }) } }
            }
        }
        if (ev == null) return@LazyColumn
        item { StateBanner(ev.state, "Altimetría «${ev.run.name}»") }
        item {
            SectionCard("Operaciones por punto") {
                Text("HI = cota de referencia + VA   ·   Cota = HI − VI (o − VD)", style = small)
                Text("BM inicial ${ev.startBmCode}: cota ${f3(ev.startElevation)}", style = MaterialTheme.typography.bodySmall)
                ev.blockers.forEach { Text("Falta: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (ev.rows.isNotEmpty()) Row(Modifier.fillMaxWidth()) {
                    listOf("Est." to 0.55f, "Punto" to 1f, "Lectura" to 1.3f, "D.parc." to 1f, "D.acum." to 1f, "Cota" to 1.2f).forEach { (h, w) -> Text(h, Modifier.weight(w), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        items(ev.rows, key = { it.observation.id }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("${r.calc?.stationNumber ?: "—"}", Modifier.weight(0.55f), style = small)
                        Text(r.pointCode, Modifier.weight(1f), style = small)
                        Text("${typeLabel(r.observation.readingType)} ${f3(r.observation.readingValue)}", Modifier.weight(1.3f), style = small)
                        Text(f3(r.calc?.partialDistance ?: r.observation.partialDistance), Modifier.weight(1f), style = small)
                        Text(f3(r.calc?.cumulativeDistance), Modifier.weight(1f), style = small)
                        Text(f3(r.calc?.elevation), Modifier.weight(1.2f), style = small)
                    }
                    r.calc?.operation?.let { Text(it + if (r.calc.isTurningPoint) "   (punto de cambio)" else "", style = small, color = MaterialTheme.colorScheme.secondary) }
                }
            }
        }
        val res = ev.result
        if (res != null) {
            item {
                SectionCard("Comprobación aritmética y cierre") {
                    Text("Σ VA = ${f3(res.sumBacksight)}      Σ VD = ${f3(res.sumForesight)}", style = small)
                    Text("Σ VA − Σ VD = ${f3(res.checkValue)}", style = small)
                    Text("Cota final − cota inicial = ${f3(res.finalElevation)} − ${f3(res.startElevation)} = ${f3(res.finalElevation - res.startElevation)}", style = small)
                    Text(
                        if (res.arithmeticCheckOk) "✔ Comprobación CONFORME: Σ VA − Σ VD coincide con la diferencia de cotas." else "✘ NO CONFORME: la suma de vistas no coincide con la diferencia de cotas; revise la libreta.",
                        color = if (res.arithmeticCheckOk) Color(0xFF1B5E20) else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                    if (ev.misclosure != null) Text("Error de cierre contra ${ev.endBmCode ?: "la cota conocida"} (${f3(ev.knownEndElevation)}): ${f3(ev.misclosure)}", style = MaterialTheme.typography.bodySmall)
                    else Text("No existe BM final de cota conocida: el cierre contra control externo no puede comprobarse.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    res.warnings.forEach { Text("Advertencia: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            Button(onClick = { vm.compute(ev.run.id, notify) }, enabled = ev.blockers.isEmpty(), modifier = Modifier.fillMaxWidth().testTag("btn_calcular_altimetria")) { Text("Calcular y guardar altimetría") }
        }
        item { ProfileSection(ev, ui.units) }
    }
}

@Composable
private fun ProfileSection(ev: LevelingEvaluation, units: String) {
    SectionCard("Perfil longitudinal") {
        val p = ev.profile
        if (p == null) { Text("Sin datos calculados.", style = MaterialTheme.typography.bodySmall); return@SectionCard }
        if (!p.valid) {
            Text(p.reason ?: "Datos insuficientes.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            p.incomplete.forEach { Text("• ${it.code}: ${it.missing}", style = MaterialTheme.typography.bodySmall) }
            return@SectionCard
        }
        val density = LocalDensity.current.density
        var size by remember { mutableStateOf(IntSize.Zero) }
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.7f).onSizeChanged { size = it }.testTag("perfil_longitudinal")) {
            drawIntoCanvas { c -> ProfileRenderer.draw(c.nativeCanvas, p, units, android.graphics.RectF(0f, 0f, this.size.width, this.size.height), PlanStyle(density), ProfileRenderer.NOMINAL_PLOT_WIDTH_MM) }
        }
        val scales = if (size.width > 0) ProfileRenderer.scalesForRect(p, size.width.toFloat(), size.height.toFloat(), density, ProfileRenderer.NOMINAL_PLOT_WIDTH_MM) else null
        scales?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
        Text("Longitud total ${f3(p.totalLength)} $units · cotas ${f3(p.minElevation)} a ${f3(p.maxElevation)} $units", style = MaterialTheme.typography.bodySmall)
    }
}
