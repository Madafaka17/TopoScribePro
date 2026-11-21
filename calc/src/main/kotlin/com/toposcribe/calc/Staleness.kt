package com.toposcribe.calc

import java.security.MessageDigest

/**
 * Huella estable de los datos de entrada de un cálculo. La app guarda la huella en el momento de
 * calcular; si la huella actual de las lecturas cambia, el resultado guardado está DESACTUALIZADO
 * hasta que se vuelva a calcular. Cualquier cambio en un valor, tipo, orden, punto, estación u
 * orientación que intervenga produce una huella distinta.
 */
fun inputFingerprint(parts: List<String>): String {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) {
        md.update(p.toByteArray(Charsets.UTF_8))
        md.update(0x1F)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

enum class ResultState(val labelEs: String) {
    SIN_CALCULAR("Sin calcular"),
    ACTUALIZADO("Calculado y actualizado"),
    VERSION_ANTERIOR("Resultado de una versión anterior de la aplicación: vuelva a calcular para verificarlo"),
    DESACTUALIZADO("DESACTUALIZADO: hay lecturas modificadas desde el último cálculo; vuelva a calcular"),
}

fun resultState(savedFingerprint: String?, currentFingerprint: String): ResultState = when {
    savedFingerprint == null -> ResultState.SIN_CALCULAR
    savedFingerprint == currentFingerprint -> ResultState.ACTUALIZADO
    else -> ResultState.DESACTUALIZADO
}
