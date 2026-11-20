package com.toposcribe.io

/** Escritor CSV genérico (encabezados + filas de texto) -- la correspondencia dominio->fila la arma
 * quien llama (en :app), este escritor no conoce ninguna entidad del dominio. RFC 4180 básico:
 * entrecomilla un campo solo si contiene coma, comillas o salto de línea. */
fun buildCsv(headers: List<String>, rows: List<List<String>>): String {
    val sb = StringBuilder()
    sb.append(headers.joinToString(",") { escapeCsvField(it) }).append("\r\n")
    for (row in rows) {
        sb.append(row.joinToString(",") { escapeCsvField(it) }).append("\r\n")
    }
    return sb.toString()
}

private fun escapeCsvField(value: String): String {
    val needsQuoting = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
    return if (needsQuoting) "\"${value.replace("\"", "\"\"")}\"" else value
}
