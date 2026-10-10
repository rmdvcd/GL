package dev.gl.license.domain.repository

import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.ContactoMetodo
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.RegistroLicencia
import dev.gl.license.domain.model.SolicitudLicencia
import kotlinx.coroutines.flow.Flow

interface LicenciaRepository {
    fun observeAll(): Flow<List<Licencia>>
    suspend fun upsert(license: Licencia): Outcome<Unit>
    suspend fun getById(id: String): Licencia?
    suspend fun merge(incoming: List<Licencia>): Outcome<Int>
    suspend fun all(): List<Licencia>
}

typealias LicenseRepository = LicenciaRepository

interface RegistroRepository {
    fun observe(): Flow<List<RegistroLicencia>>
    suspend fun registrar(registro: RegistroLicencia): Outcome<Unit>
    suspend fun mergePorId(incoming: List<RegistroLicencia>): Outcome<Int>
    suspend fun todos(): List<RegistroLicencia>
}

interface SeguridadRepository {
    fun descifrarSolicitud(raw: String): Outcome<SolicitudLicencia>
    fun cifrarLicencia(license: Licencia, devicePubPem: String?): Outcome<String>
    fun exportar(licencias: List<Licencia>): Outcome<ByteArray>
    fun importar(bytes: ByteArray): Outcome<List<Licencia>>
}

typealias CryptoGateway = SeguridadRepository

interface SecureClock {
    fun nowEpochMillis(): Long
    fun nowIso(): String
}

/**
 * Almacén de tarjetas/cuentas, teléfonos y apps que el usuario teclea. Expone solo el
 * `value` porque es lo único que la pantalla necesita para los desplegables; el
 * resto del modelo es detalle de persistencia.
 */
interface ContactMethodRepository {
    fun observe(kind: String): Flow<List<String>>
    suspend fun add(item: ContactoMetodo)
    suspend fun remove(kind: String, value: String)
}
