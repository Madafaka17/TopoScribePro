package com.toposcribe.calc

import kotlin.math.PI

/** Grados, minutos, segundos -- igual convención que angles.ts del server Node. */
data class Dms(val deg: Int, val min: Int, val sec: Double)

fun toRadians(deg: Double): Double = deg * PI / 180.0

fun toDegrees(rad: Double): Double = rad * 180.0 / PI

fun dmsToDecimal(dms: Dms): Double {
    val sign = if (dms.deg < 0) -1.0 else 1.0
    return sign * (kotlin.math.abs(dms.deg) + dms.min / 60.0 + dms.sec / 3600.0)
}

fun decimalToDms(decimal: Double): Dms {
    val sign = if (decimal < 0) -1 else 1
    val absDecimal = kotlin.math.abs(decimal)
    val deg = absDecimal.toInt()
    val minFloat = (absDecimal - deg) * 60.0
    val min = minFloat.toInt()
    val sec = (minFloat - min) * 60.0
    return Dms(sign * deg, min, sec)
}

fun formatDms(dms: Dms): String {
    val secStr = String.format("%05.2f", dms.sec)
    return "${dms.deg}°${dms.min.toString().padStart(2, '0')}'${secStr}\""
}

/** Normaliza un azimut a [0, 360). */
fun normalizeAzimuth(azimuthDeg: Double): Double {
    var normalized = azimuthDeg % 360.0
    if (normalized < 0) normalized += 360.0
    return normalized
}

private val BEARING_REGEX = Regex("""^([NS])\s*(\d+(?:\.\d+)?)\s*°?\s*([EW])$""", RegexOption.IGNORE_CASE)

/**
 * Convierte un rumbo tipo "S30°E" a azimut decimal (0-360, medido desde el norte en sentido
 * horario). Misma lógica de cuadrantes que bearingToAzimuth en el server Node.
 */
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
