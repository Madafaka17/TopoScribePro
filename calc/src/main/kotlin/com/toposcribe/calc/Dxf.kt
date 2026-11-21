package com.toposcribe.calc

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Escritor DXF ASCII R12 (AC1009) -- legible por AutoCAD, BricsCAD, LibreCAD y ezdxf --, sin
 * dependencias externas. Se escribe en Windows-1252 ($DWGCODEPAGE ANSI_1252) para que acentos y «º»
 * de los códigos de punto se vean bien en AutoCAD; los caracteres fuera de ese juego salen como «?».
 *
 * Entidades usadas: POINT, LINE, TEXT, CIRCLE y POLYLINE/VERTEX/SEQEND (curvas de nivel y líneas).
 */
class DxfBuilder {
    private val sb = StringBuilder()

    fun code(code: Int, value: String): DxfBuilder {
        sb.append(code).append('\n').append(value).append('\n')
        return this
    }

    fun code(code: Int, value: Double): DxfBuilder = code(code, num(value))
    fun code(code: Int, value: Int): DxfBuilder = code(code, value.toString())

    fun build(): String {
        code(0, "EOF")
        return sb.toString()
    }

    companion object {
        /** Número decimal sin notación científica y con punto, nunca con coma del idioma del equipo. */
        fun num(value: Double): String {
            val v = if (abs(value) < 5e-10) 0.0 else value
            return String.format(Locale.ROOT, "%.6f", v)
        }

        /** El texto DXF no admite saltos de línea; se normaliza y se limita a lo que cabe en cp1252. */
        fun text(value: String): String = value.replace('\r', ' ').replace('\n', ' ').map {
            if (it.code < 256 || it in "€‚ƒ„…†‡ˆ‰Š‹ŒŽ‘’“”•–—˜™š›œžŸ") it else '?'
        }.joinToString("")
    }
}

/** Cuántos metros/pies/etc. corresponden al código $INSUNITS del encabezado. */
private fun insUnitsFor(unitsDistance: String): Int = when (unitsDistance.lowercase()) {
    "m" -> 6
    "ft" -> 2
    else -> 0
}

/** Cada cuántos niveles se marca una curva maestra (más gruesa y con rótulo). */
const val MASTER_CONTOUR_EVERY = 5

/** Altura de los rótulos de texto según la extensión del plano (mínimo 0.2 unidades). */
fun planTextHeight(model: PlanModel): Double {
    val b = model.bounds() ?: return 1.0
    return max(0.2, max(b.width, b.height) / 120.0)
}

/**
 * DXF del plano: solo las capas indicadas (y que tengan datos). Debe coincidir con lo mostrado en
 * pantalla: [layers] son las capas activas del plano.
 */
fun buildProjectDxf(model: PlanModel, layers: Set<PlanLayer> = model.availableLayers()): String {
    val w = DxfBuilder()
    val active = layers intersect model.availableLayers()
    val th = planTextHeight(model)
    val byId = model.pointById
    val contourLayerName = PlanLayer.CURVAS.dxfName
    val masterLayerName = "TOPO-CURVAS-MAESTRAS"
    val contourLabelsLayer = "TOPO-CURVAS-COTAS"

    // --- HEADER
    w.code(0, "SECTION").code(2, "HEADER")
    w.code(9, "\$ACADVER").code(1, "AC1009")
    w.code(9, "\$DWGCODEPAGE").code(3, "ANSI_1252")
    w.code(9, "\$INSUNITS").code(70, insUnitsFor(model.unitsDistance))
    val b = model.bounds()
    if (b != null) {
        w.code(9, "\$EXTMIN").code(10, b.minE).code(20, b.minN).code(30, 0.0)
        w.code(9, "\$EXTMAX").code(10, b.maxE).code(20, b.maxN).code(30, 0.0)
    }
    w.code(0, "ENDSEC")

    // --- TABLES
    w.code(0, "SECTION").code(2, "TABLES")
    w.code(0, "TABLE").code(2, "LTYPE").code(70, 1)
    w.code(0, "LTYPE").code(2, "CONTINUOUS").code(70, 0).code(3, "Solid line").code(72, 65).code(73, 0).code(40, 0.0)
    w.code(0, "ENDTAB")
    val layerDefs = PlanLayer.entries.map { it.dxfName to it.dxfColor } +
        listOf(masterLayerName to 30, contourLabelsLayer to 6)
    w.code(0, "TABLE").code(2, "LAYER").code(70, layerDefs.size)
    for ((name, color) in layerDefs) w.code(0, "LAYER").code(2, name).code(70, 0).code(62, color).code(6, "CONTINUOUS")
    w.code(0, "ENDTAB")
    w.code(0, "TABLE").code(2, "STYLE").code(70, 1)
    w.code(0, "STYLE").code(2, "STANDARD").code(70, 0).code(40, 0.0).code(41, 1.0).code(50, 0.0).code(71, 0).code(42, th).code(3, "txt").code(4, "")
    w.code(0, "ENDTAB")
    w.code(0, "ENDSEC")

    w.code(0, "SECTION").code(2, "BLOCKS").code(0, "ENDSEC")

    // --- ENTITIES
    w.code(0, "SECTION").code(2, "ENTITIES")

    fun line(layer: String, x1: Double, y1: Double, x2: Double, y2: Double) {
        w.code(0, "LINE").code(8, layer).code(10, x1).code(20, y1).code(30, 0.0).code(11, x2).code(21, y2).code(31, 0.0)
    }
    fun text(layer: String, x: Double, y: Double, z: Double, h: Double, s: String) {
        w.code(0, "TEXT").code(8, layer).code(10, x).code(20, y).code(30, z).code(40, h).code(1, DxfBuilder.text(s))
    }
    fun polyline(layer: String, elevation: Double, pts: List<Pair<Double, Double>>, closed: Boolean) {
        w.code(0, "POLYLINE").code(8, layer).code(66, 1).code(10, 0.0).code(20, 0.0).code(30, elevation).code(70, if (closed) 1 else 0)
        for ((x, y) in pts) w.code(0, "VERTEX").code(8, layer).code(10, x).code(20, y).code(30, elevation)
        w.code(0, "SEQEND").code(8, layer)
    }

    if (PlanLayer.TRIANGULACION in active) {
        val tin = model.contours!!.triangles
        val drawn = HashSet<Pair<Long, Long>>()
        for (t in tin) for ((p, q) in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
            val key = if (p < q) p to q else q to p
            if (!drawn.add(key)) continue
            val a = byId[p] ?: continue
            val c = byId[q] ?: continue
            line(PlanLayer.TRIANGULACION.dxfName, a.east, a.north, c.east, c.north)
        }
    }
    if (PlanLayer.RADIACIONES in active) {
        for (r in model.radiations) {
            val a = byId[r.stationPointId] ?: continue
            val c = byId[r.targetPointId] ?: continue
            line(PlanLayer.RADIACIONES.dxfName, a.east, a.north, c.east, c.north)
        }
    }
    if (PlanLayer.LINEAS in active) {
        for (l in model.polylines) {
            val v = model.polylinePoints(l)
            if (v.size >= 2) polyline(PlanLayer.LINEAS.dxfName, 0.0, v.map { it.east to it.north }, l.closed && v.size > 2)
        }
    }
    if (PlanLayer.CURVAS in active) {
        val c = model.contours!!
        val levelsSorted = c.levels.sorted()
        for (p in c.polylines) {
            val idx = levelsSorted.indexOfFirst { abs(it - p.level) < 1e-9 }
            val master = idx >= 0 && idx % MASTER_CONTOUR_EVERY == 0
            val layer = if (master) masterLayerName else contourLayerName
            polyline(layer, p.level, p.points.map { it.east to it.north }, p.closed)
            if (master && p.points.size >= 2) {
                val mid = p.points[p.points.size / 2]
                text(contourLabelsLayer, mid.east, mid.north, p.level, th, DxfBuilder.num(p.level).trimEnd('0').trimEnd('.'))
            }
        }
    }
    if (PlanLayer.PUNTOS in active) {
        for (p in model.points) {
            w.code(0, "POINT").code(8, PlanLayer.PUNTOS.dxfName).code(10, p.east).code(20, p.north).code(30, p.elevation ?: 0.0)
        }
    }
    if (PlanLayer.ESTACION in active) {
        for (p in model.points.filter { it.isStation }) {
            w.code(0, "CIRCLE").code(8, PlanLayer.ESTACION.dxfName).code(10, p.east).code(20, p.north).code(30, p.elevation ?: 0.0).code(40, th * 0.8)
        }
    }
    if (PlanLayer.CODIGOS in active) {
        for (p in model.points) text(PlanLayer.CODIGOS.dxfName, p.east + th * 0.5, p.north + th * 0.5, p.elevation ?: 0.0, th, p.code)
    }
    if (PlanLayer.COTAS in active) {
        for (p in model.points) {
            val z = p.elevation ?: continue
            text(PlanLayer.COTAS.dxfName, p.east + th * 0.5, p.north - th * 1.6, z, th * 0.8, String.format(Locale.ROOT, "%.3f", z))
        }
    }
    w.code(0, "ENDSEC")
    return w.build()
}

// ---------------------------------------------------------------------------------------------
// Lectura y verificación automática del DXF generado
// ---------------------------------------------------------------------------------------------

data class DxfEntity(val type: String, val layer: String, val coords: List<Double>, val extra: String = "")

/** Lector mínimo de pares código/valor de un DXF ASCII: devuelve las entidades de la sección ENTITIES. */
fun parseDxfEntities(text: String): List<DxfEntity> {
    val raw = text.split('\n').map { it.trimEnd('\r') }
    val pairs = ArrayList<Pair<Int, String>>()
    var i = 0
    while (i + 1 < raw.size) {
        val c = raw[i].trim().toIntOrNull()
        if (c == null) { i += 1; continue }
        pairs.add(c to raw[i + 1].trim())
        i += 2
    }
    val entities = mutableListOf<DxfEntity>()
    var inEntities = false
    var idx = 0
    while (idx < pairs.size) {
        val (code, value) = pairs[idx]
        if (code == 0 && value == "SECTION" && pairs.getOrNull(idx + 1)?.second == "ENTITIES") { inEntities = true; idx += 2; continue }
        if (code == 0 && value == "ENDSEC") { inEntities = false }
        if (inEntities && code == 0 && value != "ENDSEC") {
            val type = value
            val props = ArrayList<Pair<Int, String>>()
            idx += 1
            while (idx < pairs.size && pairs[idx].first != 0) { props.add(pairs[idx]); idx += 1 }
            if (type == "POLYLINE") {
                val layer = props.firstOrNull { it.first == 8 }?.second ?: ""
                val elev = props.firstOrNull { it.first == 30 }?.second?.toDoubleOrNull() ?: 0.0
                val flags = props.firstOrNull { it.first == 70 }?.second?.toIntOrNull() ?: 0
                val coords = mutableListOf<Double>()
                while (idx < pairs.size && pairs[idx].second != "SEQEND") {
                    if (pairs[idx].first == 0 && pairs[idx].second == "VERTEX") {
                        idx += 1
                        var x = 0.0; var y = 0.0
                        while (idx < pairs.size && pairs[idx].first != 0) {
                            if (pairs[idx].first == 10) x = pairs[idx].second.toDouble()
                            if (pairs[idx].first == 20) y = pairs[idx].second.toDouble()
                            idx += 1
                        }
                        coords.add(x); coords.add(y)
                    } else idx += 1
                }
                entities.add(DxfEntity("POLYLINE", layer, listOf(elev) + coords, if (flags and 1 == 1) "closed" else "open"))
                continue
            }
            val layer = props.firstOrNull { it.first == 8 }?.second ?: ""
            fun d(c: Int) = props.firstOrNull { it.first == c }?.second?.toDoubleOrNull() ?: 0.0
            val coords = when (type) {
                "POINT", "CIRCLE", "TEXT" -> listOf(d(10), d(20), d(30))
                "LINE" -> listOf(d(10), d(20), d(30), d(11), d(21), d(31))
                else -> emptyList()
            }
            val extra = if (type == "TEXT") props.firstOrNull { it.first == 1 }?.second ?: "" else ""
            entities.add(DxfEntity(type, layer, coords, extra))
            continue
        }
        idx += 1
    }
    return entities
}

data class DxfCheck(val label: String, val ok: Boolean, val detail: String)
data class DxfVerification(val checks: List<DxfCheck>) {
    val ok: Boolean get() = checks.isNotEmpty() && checks.all { it.ok }
    fun summary(): String = checks.joinToString("\n") { (if (it.ok) "✔ " else "✘ ") + it.label + ": " + it.detail }
}

/**
 * Relee el DXF generado y lo compara con el plano: número de entidades por capa y coordenadas de
 * cada punto, radiación, línea y curva. Si algo no coincide, [DxfVerification.ok] es falso.
 */
fun verifyDxf(dxfText: String, model: PlanModel, layers: Set<PlanLayer>): DxfVerification {
    val checks = mutableListOf<DxfCheck>()
    val ents = parseDxfEntities(dxfText)
    val active = layers intersect model.availableLayers()
    val tol = 5e-6
    fun near(a: Double, b: Double) = abs(a - b) <= tol

    checks += DxfCheck("Archivo legible", dxfText.contains("AC1009") && dxfText.trimEnd().endsWith("EOF") && ents.isNotEmpty() || model.points.isEmpty(),
        "${ents.size} entidades leídas")

    fun layerOf(l: PlanLayer, type: String) = ents.filter { it.layer == l.dxfName && it.type == type }

    if (PlanLayer.PUNTOS in active) {
        val found = layerOf(PlanLayer.PUNTOS, "POINT")
        val missing = model.points.filter { p -> found.none { near(it.coords[0], p.east) && near(it.coords[1], p.north) && near(it.coords[2], p.elevation ?: 0.0) } }
        checks += DxfCheck("Puntos", found.size == model.points.size && missing.isEmpty(),
            "${found.size} de ${model.points.size} puntos con E/N/Z coincidentes" + if (missing.isNotEmpty()) "; no coinciden: " + missing.joinToString { it.code } else "")
    }
    if (PlanLayer.CODIGOS in active) {
        val found = layerOf(PlanLayer.CODIGOS, "TEXT")
        val missing = model.points.filter { p -> found.none { it.extra == DxfBuilder.text(p.code) } }
        checks += DxfCheck("Códigos", missing.isEmpty() && found.size == model.points.size, "${found.size} rótulos de código")
    }
    if (PlanLayer.ESTACION in active) {
        val expected = model.points.count { it.isStation }
        val found = layerOf(PlanLayer.ESTACION, "CIRCLE")
        checks += DxfCheck("Estación", found.size == expected, "${found.size} de $expected estaciones")
    }
    if (PlanLayer.RADIACIONES in active) {
        val expected = model.radiations.filter { model.pointById.containsKey(it.stationPointId) && model.pointById.containsKey(it.targetPointId) }
        val found = layerOf(PlanLayer.RADIACIONES, "LINE")
        val bad = expected.count { r ->
            val a = model.pointById.getValue(r.stationPointId); val b = model.pointById.getValue(r.targetPointId)
            found.none { near(it.coords[0], a.east) && near(it.coords[1], a.north) && near(it.coords[3], b.east) && near(it.coords[4], b.north) }
        }
        checks += DxfCheck("Radiaciones", found.size == expected.size && bad == 0, "${found.size} de ${expected.size} líneas estación→punto")
    }
    if (PlanLayer.LINEAS in active) {
        val expected = model.polylines.filter { model.polylinePoints(it).size >= 2 }
        val found = layerOf(PlanLayer.LINEAS, "POLYLINE")
        val bad = expected.count { l ->
            val v = model.polylinePoints(l)
            found.none { f -> f.coords.size - 1 == v.size * 2 && v.indices.all { i -> near(f.coords[1 + 2 * i], v[i].east) && near(f.coords[2 + 2 * i], v[i].north) } }
        }
        checks += DxfCheck("Líneas", found.size == expected.size && bad == 0, "${found.size} de ${expected.size} polilíneas con vértices coincidentes")
    }
    if (PlanLayer.COTAS in active) {
        val expected = model.points.count { it.elevation != null }
        val found = layerOf(PlanLayer.COTAS, "TEXT")
        checks += DxfCheck("Cotas", found.size == expected, "${found.size} de $expected rótulos de cota")
    }
    if (PlanLayer.TRIANGULACION in active) {
        val edges = HashSet<Pair<Long, Long>>()
        for (t in model.contours!!.triangles) for ((p, q) in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) edges.add(if (p < q) p to q else q to p)
        val found = layerOf(PlanLayer.TRIANGULACION, "LINE")
        checks += DxfCheck("Triangulación", found.size == edges.size, "${found.size} de ${edges.size} aristas")
    }
    if (PlanLayer.CURVAS in active) {
        val expected = model.contours!!.polylines
        val found = ents.filter { it.type == "POLYLINE" && (it.layer == PlanLayer.CURVAS.dxfName || it.layer == "TOPO-CURVAS-MAESTRAS") }
        val bad = expected.count { c ->
            found.none { f -> near(f.coords[0], c.level) && f.coords.size - 1 == c.points.size * 2 &&
                c.points.indices.all { i -> near(f.coords[1 + 2 * i], c.points[i].east) && near(f.coords[2 + 2 * i], c.points[i].north) } }
        }
        checks += DxfCheck("Curvas de nivel", found.size == expected.size && bad == 0, "${found.size} de ${expected.size} curvas con cota y vértices coincidentes")
    }
    val unknownLayers = ents.map { it.layer }.toSet() - (PlanLayer.entries.map { it.dxfName } + listOf("TOPO-CURVAS-MAESTRAS", "TOPO-CURVAS-COTAS")).toSet()
    checks += DxfCheck("Capas", unknownLayers.isEmpty(), "capas utilizadas: " + ents.map { it.layer }.toSortedSet().joinToString())
    return DxfVerification(checks)
}
