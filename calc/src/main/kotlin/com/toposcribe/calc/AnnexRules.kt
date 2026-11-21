package com.toposcribe.calc

/**
 * Reglas de validación de anexos: tipos permitidos y tamaño máximo. Son puras para poder probarlas
 * sin Android; la lectura real del archivo (¿se puede abrir?) la hace la app.
 */
object AnnexRules {
    /** Tamaño máximo por archivo. */
    const val MAX_BYTES: Long = 25L * 1024 * 1024

    /** Extensión permitida → tipo MIME que se guarda. */
    private val ALLOWED: Map<String, String> = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "bmp" to "image/bmp", "gif" to "image/gif",
        "pdf" to "application/pdf",
        "txt" to "text/plain", "csv" to "text/csv",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "odt" to "application/vnd.oasis.opendocument.text", "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "dxf" to "image/vnd.dxf", "dwg" to "application/acad",
    )

    fun allowedExtensions(): List<String> = ALLOWED.keys.sorted()

    fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    fun mimeFor(fileName: String): String? = ALLOWED[extensionOf(fileName)]

    fun kindOf(mime: String): AnnexFileKind = when {
        mime.startsWith("image/") && mime != "image/vnd.dxf" -> AnnexFileKind.IMAGE
        mime == "application/pdf" -> AnnexFileKind.PDF
        else -> AnnexFileKind.OTHER
    }

    /** Descripción corta del tipo para el índice del informe. */
    fun typeLabel(fileName: String): String = when (val e = extensionOf(fileName)) {
        "jpg", "jpeg", "png", "webp", "bmp", "gif" -> "Imagen ${e.uppercase()}"
        "pdf" -> "Documento PDF"
        "" -> "Archivo"
        else -> "Archivo ${e.uppercase()}"
    }

    /** null si el archivo es válido; si no, el mensaje de error para el usuario. */
    fun validate(fileName: String, sizeBytes: Long?): String? {
        if (fileName.isBlank()) return "El archivo no tiene nombre."
        if (mimeFor(fileName) == null) {
            val ext = extensionOf(fileName)
            return "Tipo de archivo no permitido${if (ext.isNotEmpty()) " (.$ext)" else ""}. Permitidos: " + allowedExtensions().joinToString(", ") + "."
        }
        if (sizeBytes != null && sizeBytes <= 0L) return "El archivo está vacío."
        if (sizeBytes != null && sizeBytes > MAX_BYTES) {
            return "El archivo pesa ${"%.1f".format(sizeBytes / 1024.0 / 1024.0)} MB y el máximo es ${MAX_BYTES / 1024 / 1024} MB."
        }
        return null
    }
}
