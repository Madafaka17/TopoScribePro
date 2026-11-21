package com.toposcribe.io

/**
 * Escritor CSV (RFC 4180 básico): entrecomilla un campo solo si contiene coma, comillas o salto de
 * línea. [withBom] antepone el BOM UTF-8 para que Excel muestre bien los acentos.
 */
fun buildCsv(headers: List<String>, rows: List<List<String>>, withBom: Boolean = true): String {
    val sb = StringBuilder()
    if (withBom) sb.append('\uFEFF')
    sb.append(headers.joinToString(",") { escapeCsvField(it) }).append("\r\n")
    for (row in rows) sb.append(row.joinToString(",") { escapeCsvField(it) }).append("\r\n")
    return sb.toString()
}

private fun escapeCsvField(value: String): String {
    val needsQuoting = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
    return if (needsQuoting) "\"${value.replace("\"", "\"\"")}\"" else value
}
