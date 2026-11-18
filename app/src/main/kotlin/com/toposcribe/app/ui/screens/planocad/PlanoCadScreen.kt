package com.toposcribe.app.ui.screens.planocad

import com.toposcribe.app.ui.components.toDecimalOrNull
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toposcribe.app.ui.cad.computeUniformScale
import com.toposcribe.app.ui.cad.toScreenX
import com.toposcribe.app.ui.cad.toScreenY
import com.toposcribe.calc.PlanData
import com.toposcribe.calc.PlanPoint
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

@Composable
fun PlanoCadScreen(modifier: Modifier = Modifier, viewModel: PlanoCadViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val contours by viewModel.contours.collectAsState()
    val profileSamples by viewModel.profileSamples.collectAsState()
    val profileError by viewModel.profileError.collectAsState()

    var showPoints by remember { mutableStateOf(true) }
    var showLines by remember { mutableStateOf(true) }
    var showGrid by remember { mutableStateOf(true) }
    var showContours by remember { mutableStateOf(true) }
    var showProfile by remember { mutableStateOf(false) }
    var equidistance by remember { mutableStateOf("1.0") }
    // Antes un valor con coma ("0,5") o inválido se cambiaba en silencio por 1.0; ahora se avisa y se bloquea el botón.
    val equidistanceValue = equidistance.toDecimalOrNull()?.takeIf { it > 0.0 }
    var resetKey by remember { mutableStateOf(0) }

    // El plano se cargaba una sola vez al abrir la app: puntos calculados o añadidos después no aparecían.
    // Ahora se recarga cada vez que se entra a la pestaña.
    LaunchedEffect(Unit) { viewModel.reload() }

    // Las curvas arrancan activas (como en el diseño): se recalculan cuando cambia el plano.
    LaunchedEffect(state.planData, showContours) {
        if (showContours && state.planData.points.isNotEmpty()) viewModel.loadContours(equidistanceValue ?: 1.0)
    }

    // "Ver Perfil Eje": el eje es la cadena de puntos de la poligonal; si no existe un eje, se crea uno.
    val axisChain = remember(state.planData) { axisChain(state.planData) }
    LaunchedEffect(showProfile, state.alignments.size, state.planData) {
        if (!showProfile) return@LaunchedEffect
        val axis = state.alignments.firstOrNull()
        if (axis != null) viewModel.loadProfile(axis.id, 5.0)
        else if (axisChain.size >= 2) viewModel.createAlignment("Eje poligonal", axisChain.map { it.pointId })
    }

    LazyColumn(modifier = modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Capas:", style = MaterialTheme.typography.labelSmall)
                    LayerToggle("Vértices", showPoints) { showPoints = it }
                    LayerToggle("Polígono", showLines) { showLines = it }
                    LayerToggle("Cuadrícula", showGrid) { showGrid = it }
                    LayerToggle("Curvas Nivel", showContours) { showContours = it }
                    LayerToggle("Ver Perfil Eje", showProfile) { showProfile = it }
                    OutlinedButton(onClick = { resetKey++ }) { Text("Centrar") }
                }
            }
        }

        val c = contours
        if (showContours && c != null && !c.applicable && state.planData.points.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "⚠ Datos insuficientes para triangulación (TIN) e interpolación de curvas de nivel. Se requieren al menos 4 puntos no colineales con cota definida. ${c.reason ?: ""}",
                        modifier = Modifier.background(Color(0xFFFFF8E1)).padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4A3B00),
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                if (state.planData.points.isEmpty()) {
                    Text(
                        "Todavía no hay puntos con coordenadas calculadas en este proyecto.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    PlanoCanvas(
                        points = state.planData.points,
                        lines = if (showLines) state.planData.lines else emptyList(),
                        showPoints = showPoints,
                        showGrid = showGrid,
                        resetKey = resetKey,
                        contourSegments = if (showContours) contours?.segments ?: emptyList() else emptyList(),
                    )
                    if (state.planData.pointsWithoutCoords > 0) {
                        Text(
                            "${state.planData.pointsWithoutCoords} punto(s) sin coordenadas calculadas no se muestran.",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Curvas de nivel", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = equidistance, onValueChange = { equidistance = it }, label = { Text("Equidistancia [m]") },
                        singleLine = true, isError = equidistanceValue == null,
                        supportingText = { if (equidistanceValue == null) Text("Escribe un número mayor que 0 (coma o punto decimal).") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(enabled = equidistanceValue != null, onClick = { viewModel.loadContours(equidistanceValue!!) }) { Text("Calcular curvas de nivel") }
                    contours?.let { r ->
                        Text(
                            if (r.applicable) "Método: ${r.method}. ${r.levels.size} nivel(es) generado(s)." else r.reason ?: "No aplicable.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (showProfile) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Perfil Longitudinal de Eje Poligonal", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        if (axisChain.size >= 2) {
                            Text(axisChain.joinToString(" → ") { it.code }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        } else {
                            Text("Se necesitan al menos 2 puntos unidos por observaciones para trazar el eje.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.alignments.drop(1).forEach { alignment ->
                            OutlinedButton(onClick = { viewModel.loadProfile(alignment.id, 5.0) }) { Text("Perfil de \"${alignment.name}\"") }
                        }
                        if (profileError != null) {
                            Text(profileError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (profileSamples.isNotEmpty()) {
                            ProfileCanvas(samples = profileSamples)
                        }
                    }
                }
            }
        }
    }
}

/** Cadena ordenada de puntos del eje: recorre las líneas en el orden de las observaciones (A→B→C…). */
private fun axisChain(plan: PlanData): List<PlanPoint> {
    val byId = plan.points.associateBy { it.pointId }
    val ids = mutableListOf<Long>()
    for (line in plan.lines) {
        if (ids.isEmpty()) ids.add(line.fromPointId)
        if (ids.last() == line.fromPointId) ids.add(line.toPointId)
        else if (line.toPointId !in ids) { ids.add(line.fromPointId); ids.add(line.toPointId) }
    }
    return ids.mapNotNull { byId[it] }
}

@Composable
private fun LayerToggle(label: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheck)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Paso "redondo" (1, 2, 5 × 10^n) para que la cuadrícula tenga ~100 px entre líneas a cualquier zoom. */
private fun niceStep(raw: Double): Double {
    if (raw <= 0.0 || raw.isNaN() || raw.isInfinite()) return 1.0
    val p = 10.0.pow(floor(log10(raw)))
    val f = raw / p
    return p * when {
        f < 1.5 -> 1.0
        f < 3.5 -> 2.0
        f < 7.5 -> 5.0
        else -> 10.0
    }
}

@Composable
private fun PlanoCanvas(
    points: List<PlanPoint>,
    lines: List<com.toposcribe.calc.PlanLine>,
    showPoints: Boolean,
    showGrid: Boolean,
    resetKey: Int,
    contourSegments: List<com.toposcribe.calc.ContourSegment>,
) {
    var panX by remember(resetKey) { mutableStateOf(0f) }
    var panY by remember(resetKey) { mutableStateOf(0f) }
    var zoom by remember(resetKey) { mutableStateOf(1f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var hud by remember { mutableStateOf<Triple<Double, Double, Double?>?>(null) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f)
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(0.2f, 10f)
                    panX += pan.x
                    panY += pan.y
                }
            }
            .pointerInput(points, canvasSize) {
                // Toque -> lectura X/Y/Z en el HUD; si cae cerca de un vértice se ajusta a él (con su cota).
                detectTapGestures { tap ->
                    val w = canvasSize.width.toFloat()
                    val h = canvasSize.height.toFloat()
                    if (w <= 0f || h <= 0f) return@detectTapGestures
                    val base = computeUniformScale(points, w, h)
                    val nearest = points.minByOrNull { p ->
                        val sx = base.toScreenX(p.east) * zoom + panX
                        val sy = base.toScreenY(p.north, h) * zoom + panY
                        hypot(sx - tap.x, sy - tap.y)
                    }
                    val snapped = nearest?.takeIf { p ->
                        val sx = base.toScreenX(p.east) * zoom + panX
                        val sy = base.toScreenY(p.north, h) * zoom + panY
                        hypot(sx - tap.x, sy - tap.y) < 40f
                    }
                    hud = if (snapped != null) {
                        Triple(snapped.east, snapped.north, snapped.elevation)
                    } else {
                        val east = ((tap.x - panX) / zoom - base.offsetXPx) / base.scale + base.minEast
                        val north = (h - base.offsetYPx - (tap.y - panY) / zoom) / base.scale + base.minNorth
                        Triple(east, north, null)
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val base = computeUniformScale(points, size.width, size.height)
            val pointById = points.associateBy { it.pointId }

            fun sx(east: Double) = base.toScreenX(east) * zoom + panX
            fun sy(north: Double) = base.toScreenY(north, size.height) * zoom + panY

            // Cuadrícula: en coordenadas de mundo, con paso redondo según el zoom actual.
            if (showGrid) {
                val pxPerUnit = (base.scale * zoom).toDouble()
                val step = niceStep(100.0 / pxPerUnit)
                val worldW0 = ((0f - panX) / zoom - base.offsetXPx) / base.scale + base.minEast
                val worldW1 = ((size.width - panX) / zoom - base.offsetXPx) / base.scale + base.minEast
                val worldN0 = (size.height - base.offsetYPx - (size.height - panY) / zoom) / base.scale + base.minNorth
                val worldN1 = (size.height - base.offsetYPx - (0f - panY) / zoom) / base.scale + base.minNorth
                val gridColor = Color(0x22000000)
                var e = floor(worldW0 / step) * step
                var guard = 0
                while (e <= worldW1 && guard++ < 200) {
                    drawLine(gridColor, Offset(sx(e), 0f), Offset(sx(e), size.height), strokeWidth = 1f)
                    e += step
                }
                var n = floor(worldN0 / step) * step
                guard = 0
                while (n <= worldN1 && guard++ < 200) {
                    drawLine(gridColor, Offset(0f, sy(n)), Offset(size.width, sy(n)), strokeWidth = 1f)
                    n += step
                }
            }

            // Capa de datos: líneas, curvas de nivel, puntos -- transformadas por pan/zoom.
            for (line in lines) {
                val a = pointById[line.fromPointId] ?: continue
                val b = pointById[line.toPointId] ?: continue
                drawLine(
                    color = Color(0xFF5B6472),
                    start = Offset(sx(a.east), sy(a.north)),
                    end = Offset(sx(b.east), sy(b.north)),
                    strokeWidth = 2f,
                )
            }
            for (seg in contourSegments) {
                drawLine(
                    color = Color(0xFF8E7166),
                    start = Offset(sx(seg.a.east), sy(seg.a.north)),
                    end = Offset(sx(seg.b.east), sy(seg.b.north)),
                    strokeWidth = 1f,
                )
            }
            if (showPoints) {
                for (p in points) {
                    val cx = sx(p.east)
                    val cy = sy(p.north)
                    val color = when (p.role) {
                        "CONTROL" -> Color(0xFF1F6F5C)
                        "POLIGONAL" -> Color(0xFF006398)
                        "RELLENO" -> Color(0xFFA33900)
                        else -> Color(0xFF5B6472)
                    }
                    drawCircle(color = color, radius = if (p.isControl) 6f else 4f, center = Offset(cx, cy))
                    drawContext.canvas.nativeCanvas.drawText(
                        p.code,
                        cx + 8f,
                        cy - 8f,
                        android.graphics.Paint().apply {
                            this.color = android.graphics.Color.rgb(31, 36, 48)
                            textSize = 28f
                        },
                    )
                }
            }

            // Overlay fijo (norte + escala gráfica): se dibuja SIN pan/zoom, siempre en la
            // esquina superior derecha; la escala sí sigue el zoom para que el valor sea real.
            drawNorthArrowAndScale(base.scale * zoom)
        }

        // HUD de coordenadas (esquina inferior izquierda), como en el diseño.
        val h = hud
        Text(
            text = "X: ${h?.first?.let { "%.3f".format(it) } ?: "--"}   Y: ${h?.second?.let { "%.3f".format(it) } ?: "--"}   Z: ${h?.third?.let { "%.3f".format(it) } ?: "--"}",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color(0xE60B1C30), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNorthArrowAndScale(pxPerMeter: Float) {
    val ox = size.width - 60f
    val oy = 50f
    drawLine(Color(0xFFA33900), Offset(ox, oy + 30f), Offset(ox, oy - 10f), strokeWidth = 4f)
    drawLine(Color(0xFFA33900), Offset(ox, oy - 10f), Offset(ox - 8f, oy + 4f), strokeWidth = 4f)
    drawLine(Color(0xFFA33900), Offset(ox, oy - 10f), Offset(ox + 8f, oy + 4f), strokeWidth = 4f)
    drawContext.canvas.nativeCanvas.drawText(
        "N",
        ox - 6f,
        oy - 16f,
        android.graphics.Paint().apply { color = android.graphics.Color.rgb(163, 57, 0); textSize = 26f },
    )

    val barLengthPx = 60f
    val worldLength = if (pxPerMeter > 0f) barLengthPx / pxPerMeter else 0f
    drawLine(Color(0xFF1F2430), Offset(ox - 30f, oy + 50f), Offset(ox + 30f, oy + 50f), strokeWidth = 3f)
    drawContext.canvas.nativeCanvas.drawText(
        "%.1f m".format(worldLength),
        ox - 24f,
        oy + 70f,
        android.graphics.Paint().apply { color = android.graphics.Color.rgb(31, 36, 48); textSize = 22f },
    )
}

@Composable
private fun ProfileCanvas(samples: List<com.toposcribe.calc.ProfileSample>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
        if (samples.size < 2) return@Canvas
        val minChain = samples.minOf { it.chainage }
        val maxChain = samples.maxOf { it.chainage }
        val minElev = samples.minOf { it.elevation }
        val maxElev = samples.maxOf { it.elevation }
        val chainRange = (maxChain - minChain).coerceAtLeast(0.001)
        val elevRange = (maxElev - minElev).coerceAtLeast(0.001)

        fun sx(chainage: Double) = (32 + (chainage - minChain) / chainRange * (size.width - 64)).toFloat()
        fun sy(elevation: Double) = (size.height - 24 - (elevation - minElev) / elevRange * (size.height - 48)).toFloat()

        for (i in 0 until samples.size - 1) {
            drawLine(
                color = Color(0xFF006398),
                start = Offset(sx(samples[i].chainage), sy(samples[i].elevation)),
                end = Offset(sx(samples[i + 1].chainage), sy(samples[i + 1].elevation)),
                strokeWidth = 3f,
            )
        }
        for (s in samples) {
            drawCircle(Color(0xFFA33900), radius = 4f, center = Offset(sx(s.chainage), sy(s.elevation)))
        }
    }
}
