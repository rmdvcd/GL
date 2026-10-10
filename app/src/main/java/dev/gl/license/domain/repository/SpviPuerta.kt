package dev.gl.license.domain.repository

import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.SolicitudSpviV2

/**
 * Puerta SPVI 0.23.1. La implementación real usa las claves del Android
 * Keystore sin exportarlas; los tests usan pares efímeros en memoria.
 */
interface SpviPuerta {
    fun abrirSolicitud(raw: String): Outcome<SolicitudSpviV2>
    fun emitirCodigo(lic: Licencia, devicePub33: ByteArray): Outcome<String>
}
