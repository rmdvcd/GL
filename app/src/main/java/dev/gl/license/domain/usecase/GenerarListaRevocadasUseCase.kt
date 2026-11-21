package dev.gl.license.domain.usecase

import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.repository.LicenciaRepository
import dev.gl.license.domain.repository.SecureClock
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.KeystoreManager
import dev.gl.license.security.Spvi23
import kotlinx.serialization.json.*
import java.security.MessageDigest
import javax.inject.Inject

class GenerarListaRevocadasUseCase @Inject constructor(private val repo: LicenciaRepository, private val clock: SecureClock) {
    suspend operator fun invoke(): ByteArray = encode(repo.all().filter { it.status == EstadoLicencia.REVOCADA }.map { it.id }, clock.nowEpochMillis()/1000) {
        Spvi23.derToRaw(CryptoEngine.sign(KeystoreManager.signingPair().private, it))
    }

    companion object {
        internal fun encode(ids: List<String>, now: Long, sign: (ByteArray) -> ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashes = ids.map { id -> digest.digest(("SPVI-REV|" + id.lowercase()).toByteArray()).take(16)
                .joinToString("") { "%02x".format(it) } }.toSortedSet()
            val datos = buildJsonObject {
                put("v", 1); put("emitidaEn", now)
                putJsonArray("revocadas") { hashes.forEach { add(it) } }
            }.toString()
            val raw = sign(("SPVI-REV1|" + datos).toByteArray())
            require(raw.size == 64)
            return buildJsonObject { put("datos", datos); put("firma", Spvi23.b64urlE(raw)) }.toString().toByteArray().also {
                require(it.size <= 512 * 1024) { "La lista supera 512 KiB." }
            }
        }
    }
}
