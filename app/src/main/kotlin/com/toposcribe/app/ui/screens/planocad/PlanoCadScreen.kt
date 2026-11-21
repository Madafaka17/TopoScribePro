package com.toposcribe.app.ui.screens.planocad

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toposcribe.app.ui.components.LocalNotify
import com.toposcribe.app.ui.components.NoProjectMessage
import com.toposcribe.app.ui.components.PlanCanvas
import com.toposcribe.app.ui.components.SectionCard
import com.toposcribe.app.ui.components.StateBanner
import com.toposcribe.calc.DxfVerification
import com.toposcribe.calc.PlanLayer

@Composable
fun PlanoCadScreen(modifier: Modifier = Modifier, vm: PlanoCadViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val notify = LocalNotify.current
    var resetKey by remember { mutableIntStateOf(0) }
    var verification by remember { mutableStateOf<DxfVerification?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/dxf")) { uri ->
        if (uri != null) vm.exportDxf(uri) { v, msg -> verification = v; notify(msg) }
    }
    val plan = ui.plan
    if (ui.projectId == null || plan == null) { NoProjectMessage(modifier); return }

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
        item {
            SectionCard("Plano CAD") {
                StateBanner(ui.planState, "Planimetría")
                val available = plan.availableLayers()
                Text("Capas (solo existen las que tienen datos; cotas, triangulación y curvas aparecen solo si se calcularon válidamente):", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlanLayer.entries.forEach { l -> FilterChip(selected = l in ui.layers, enabled = l in available, onClick = { vm.toggle(l) }, label = { Text(l.labelEs) }) }
                }
                Text("Sistema de coordenadas: ${plan.coordinateSystem} · Unidades: ${plan.unitsDistance}", style = MaterialTheme.typography.bodySmall)
                if (plan.pointsWithoutCoordinates > 0) Text("${plan.pointsWithoutCoordinates} punto(s) sin coordenadas calculadas no se muestran.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { PlanCanvas(plan, ui.layers, resetKey) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { resetKey += 1 }) { Text("Centrar") }
                Button(onClick = { export.launch("plano_${plan.projectName.ifBlank { "proyecto" }.replace(Regex("[^A-Za-z0-9_-]+"), "_")}.dxf") }, enabled = plan.points.isNotEmpty(), modifier = Modifier.testTag("btn_exportar_dxf")) { Text("Exportar DXF (AutoCAD)") }
            }
        }
        verification?.let { v ->
            item {
                SectionCard(if (v.ok) "Verificación del DXF: CORRECTA" else "Verificación del DXF: HAY DIFERENCIAS") {
                    v.checks.forEach { Text((if (it.ok) "✔ " else "✘ ") + it.label + ": " + it.detail, style = MaterialTheme.typography.bodySmall, color = if (it.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
