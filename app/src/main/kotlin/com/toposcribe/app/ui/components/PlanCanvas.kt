package com.toposcribe.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.toposcribe.calc.PlanLayer
import com.toposcribe.calc.PlanModel
import com.toposcribe.io.PlanRenderer
import com.toposcribe.io.PlanStyle
import java.util.Locale

/**
 * Lienzo del plano con zoom (pellizco), desplazamiento y lectura de coordenadas al tocar. Dibuja con
 * el MISMO PlanRenderer que usa el PDF, así que la pantalla y el informe muestran lo mismo.
 */
@Composable
fun PlanCanvas(model: PlanModel, layers: Set<PlanLayer>, resetKey: Int, modifier: Modifier = Modifier) {
    var zoom by remember(resetKey) { mutableFloatStateOf(1f) }
    var panX by remember(resetKey) { mutableFloatStateOf(0f) }
    var panY by remember(resetKey) { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var hud by remember { mutableStateOf("Toque un punto o el plano para leer coordenadas") }
    val density = LocalDensity.current.density
    val bounds = model.bounds()

    Box(modifier.fillMaxWidth().aspectRatio(1.0f).onSizeChanged { size = it }.testTag("plano_canvas")) {
        if (bounds == null) {
            Text("No hay puntos con coordenadas calculadas.", Modifier.align(Alignment.Center).padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Box
        }
        val rect = android.graphics.RectF(0f, 0f, size.width.toFloat(), size.height.toFloat())
        val base = if (size.width > 0) PlanRenderer.fit(bounds, rect, 44f * density) else null
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, gz, _ ->
                        val newZoom = (zoom * gz).coerceIn(0.3f, 40f)
                        val k = newZoom / zoom
                        panX = centroid.x - (centroid.x - panX) * k + pan.x
                        panY = centroid.y - (centroid.y - panY) * k + pan.y
                        zoom = newZoom
                    }
                }
                .pointerInput(model, size) {
                    detectTapGestures { tap ->
                        val b = base ?: return@detectTapGestures
                        val vp = b.zoomed(zoom, panX, panY)
                        val near = PlanRenderer.nearestPoint(model, vp, tap.x, tap.y, 36f * density)
                        hud = if (near != null) String.format(Locale.US, "%s   E %.3f   N %.3f   Z %s", near.code, near.east, near.north, near.elevation?.let { String.format(Locale.US, "%.3f", it) } ?: "—")
                        else String.format(Locale.US, "E %.3f   N %.3f", vp.worldE(tap.x), vp.worldN(tap.y))
                    }
                },
        ) {
            drawRect(Color.White)
            val b = base ?: return@Canvas
            val vp = b.zoomed(zoom, panX, panY)
            drawIntoCanvas { c ->
                val style = PlanStyle(density)
                PlanRenderer.draw(c.nativeCanvas, model, layers, vp, rect, style)
                PlanRenderer.drawFurniture(c.nativeCanvas, model, layers, vp, rect, style)
            }
        }
        Text(
            hud, Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color(0xE60B1C30), MaterialTheme.shapes.extraSmall).padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = Color.White,
        )
    }
}
