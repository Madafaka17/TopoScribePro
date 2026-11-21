package com.toposcribe.calc

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Grados, minutos, segundos. */
data class Dms(val deg: Int, val min: Int, val sec: Double)

fun toRadians(deg: Double): Double = deg * PI / 180.0

fun toDegrees(rad: Double): Double = rad * 180.0 / PI

fun dmsToDecimal(dms: Dms): Double {
    val sign = if (dms.deg < 0) -1.0 else 1.0
    return sign * (abs(dms.deg) + dms.min / 60.0 + dms.sec / 3600.0)
}

fun decimalToDms(decimal: Double): Dms {
    val sign = if (decimal < 0) -1 else 1
    val absDecimal = abs(decimal)
    val deg = absDecimal.toInt()
    val minFloat = (absDecimal - deg) * 60.0
    val min = minFloat.toInt()
    val sec = (minFloat - min) * 60.0
    return Dms(sign * deg, min, sec)
}

fun formatDms(dms: Dms): String {
    val secStr = String.format(Locale.US, "%05.2f", dms.sec)
    return "${dms.deg}°${dms.min.toString().padStart(2, '0')}'${secStr}\""
}

/**
 * Formatea grados decimales como G°MM'SS.ss" redondeando los segundos y propagando el acarreo
 * (59.996" -> 1'00.00") para no imprimir nunca 60 segundos ni 60 minutos.
 */
fun formatDegreesAsDms(decimalDeg: Double, secDecimals: Int = 2): String {
    val negative = decimalDeg < 0
    val factor = Math.pow(10.0, secDecimals.toDouble())
    var totalCentis = (abs(decimalDeg) * 3600.0 * factor).roundToLong()
    val perDeg = (3600.0 * factor).roundToLong()
    val perMin = (60.0 * factor).roundToLong()
    val deg = totalCentis / perDeg
    totalCentis -= deg * perDeg
    val min = totalCentis / perMin
    totalCentis -= min * perMin
    val sec = totalCentis / factor
    val secStr = if (secDecimals == 0) String.format(Locale.US, "%02d", sec.toLong())
    else String.format(Locale.US, "%0${3 + secDecimals}.${secDecimals}f", sec)
    return "${if (negative) "-" else ""}$deg°${min.toString().padStart(2, '0')}'$secStr\""
}

/** Normaliza un azimut a [0, 360). */
fun normalizeAzimuth(azimuthDeg: Double): Double {
    var normalized = azimuthDeg % 360.0
    if (normalized < 0) normalized += 360.0
    // -1e-15 % 360 + 360 puede dar exactamente 360.0 por redondeo.
    if (normalized >= 360.0) normalized = 0.0
    return normalized
}

private val BEARING_REGEX = Regex("""^([NS])\s*(\d+(?:\.\d+)?)\s*°?\s*([EW])$""", RegexOption.IGNORE_CASE)

/** Convierte un rumbo tipo "S30°E" a azimut decimal (0-360, medido desde el norte en sentido horario). */
fun bearingToAzimuth(bearing: String): Double? {
    val match = BEARING_REGEX.find(bearing.trim()) ?: return null
    val (ns, angleStr, ew) = match.destructured
    val angle = angleStr.toDoubleOrNull() ?: return null
    val north = ns.uppercase() == "N"
    val east = ew.uppercase() == "E"
    return when {
        north && east -> angle
        !north && east -> 180.0 - angle
        !north && !east -> 180.0 + angle
        else -> 360.0 - angle
    }
}

/** Convierte un azimut decimal a rumbo tipo "S30°00'00\"E". */
fun azimuthToBearing(azimuthDeg: Double): String {
    val az = normalizeAzimuth(azimuthDeg)
    val (ns, ew, angle) = when {
        az <= 90.0 -> Triple("N", "E", az)
        az <= 180.0 -> Triple("S", "E", 180.0 - az)
        az <= 270.0 -> Triple("S", "W", az - 180.0)
        else -> Triple("N", "W", 360.0 - az)
    }
    return "$ns${formatDms(decimalToDms(angle))}$ew"
}

/** Resultado de interpretar un ángulo escrito por el usuario. */
data class AngleParse(val degrees: Double?, val error: String?)

/**
 * Interpreta un ángulo escrito como grados decimales ("90.5", "90,5") o como G°M'S" con separadores
 * flexibles ("90 30 15.5", "90°30'15.5\""). Devuelve el valor en grados decimales o un mensaje de
 * error en español (formato inválido, minutos/segundos fuera de 0..59.99...).
 */
fun parseAngleText(text: String, label: String): AngleParse {
    val clean = text.trim().replace(',', '.')
    if (clean.isEmpty()) return AngleParse(null, "«$label» es obligatorio.")
    clean.toDoubleOrNull()?.let { return AngleParse(it, null) }
    val parts = clean.replace("°", " ").replace("'", " ").replace("’", " ").replace("\"", " ")
        .split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.size !in 2..3) return AngleParse(null, "«$label» tiene un formato no válido (use 90.5 o 90 30 15).")
    val d = parts[0].toDoubleOrNull() ?: return AngleParse(null, "«$label»: los grados no son un número.")
    val m = parts[1].toDoubleOrNull() ?: return AngleParse(null, "«$label»: los minutos no son un número.")
    val s = if (parts.size == 3) parts[2].toDoubleOrNull() ?: return AngleParse(null, "«$label»: los segundos no son un número.") else 0.0
    if (m < 0 || m >= 60) return AngleParse(null, "«$label»: los minutos van de 0 a 59.")
    if (s < 0 || s >= 60) return AngleParse(null, "«$label»: los segundos van de 0 a 59.99.")
    if (d != floor(d)) return AngleParse(null, "«$label»: con minutos y segundos los grados deben ser enteros.")
    val negative = parts[0].startsWith("-")
    val total = (if (negative) -1 else 1) * (abs(d) + m / 60.0 + s / 3600.0)
    return AngleParse(total, null)
}
