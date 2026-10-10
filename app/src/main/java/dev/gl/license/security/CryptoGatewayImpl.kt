package dev.gl.license.security

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.Envelope
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.SolicitudLicencia
import dev.gl.license.domain.model.SolicitudSpviV2
import dev.gl.license.domain.repository.SeguridadRepository
import dev.gl.license.domain.repository.SpviPuerta
import dev.gl.license.domain.usecase.FieldValidator
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CryptoGatewayImpl @Inject constructor(
    private val json: Json,
) : SeguridadRepository, SpviPuerta {

    private fun master(): SecretKey = KeystoreManager.getOrCreateAes()

    override fun descifrarSolicitud(raw: String): Outcome<SolicitudLicencia> {
        return try {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > 16_384) return Outcome.Err(AppError.InvalidPayload)
            val env = json.decodeFromString(Envelope.serializer(), trimmed)
            when (val st = EnvelopeValidator.structural(env)) {
                is Outcome.Err -> return st
                is Outcome.Ok -> Unit
            }
            val ecdh = KeystoreManager.ecdhPair()
            val signing = KeystoreManager.signingPair()
            val aad = listOf(env.version.toString(), env.alg, env.ephemeralPublicKey, env.keyId)
            val pt = when (
                val o = HybridBox.open(
                    env = env,
                    staticPrivate = ecdh.private,
                    verifyKeys = listOf(signing.public),
                    info = CryptoEngine.INFO_REQ,
                    aadParts = aad,
                )
            ) {
                is Outcome.Err -> return o
                is Outcome.Ok -> o.value
            }
            val req = json.decodeFromString(SolicitudLicencia.serializer(), pt.decodeToString())
            FieldValidator.validateRequest(req)
        } catch (_: Exception) {
            Outcome.Err(AppError.InvalidPayload)
        }
    }

    override fun cifrarLicencia(license: Licencia, devicePubPem: String?): Outcome<String> {
        return try {
            when (val v = EnvelopeValidator.license(license)) {
                is Outcome.Err -> return v
                is Outcome.Ok -> Unit
            }
            val payload = json.encodeToString(Licencia.serializer(), license).toByteArray()
            val signing = KeystoreManager.signingPair()
            val peer = if (!devicePubPem.isNullOrBlank()) {
                CryptoEngine.parseEcPublic(devicePubPem)
            } else {
                KeystoreManager.ecdhPair().public
            }
            val env = HybridBox.seal(
                plaintext = payload,
                peerPublic = peer,
                signingPrivate = signing.private,
                info = CryptoEngine.INFO_LIC,
                aadParts = listOf(CONTRACT_VERSION.toString(), CryptoEngine.ALG_HYBRID, license.id),
            )
            Outcome.Ok(json.encodeToString(Envelope.serializer(), env))
        } catch (_: Exception) {
            Outcome.Err(AppError.CryptoFailure)
        }
    }

    override fun abrirSolicitud(raw: String): Outcome<SolicitudSpviV2> {
        return try {
            val codigo = when (val e = Spvi23.extraerCodigo(raw)) {
                is Outcome.Err -> return e
                is Outcome.Ok -> e.value
            }
            Spvi23.abrir(codigo, KeystoreManager.ecdhPair().private)
        } catch (_: Exception) {
            Outcome.Err(AppError.InvalidPayload)
        }
    }

    override fun emitirCodigo(lic: Licencia, devicePub33: ByteArray): Outcome<String> {
        return try {
            val devicePub = Spvi23.descomprimirPunto(devicePub33)
            val emitida = java.time.Instant.parse(lic.issuedAtIso).epochSecond
            val vence = lic.expiresAtIso?.let { java.time.Instant.parse(it).epochSecond }
            val body = Spvi23.cuerpo(
                java.util.UUID.fromString(lic.id),
                Spvi23.huella(lic.deviceId, devicePub33),
                lic.type,
                lic.status,
                lic.secundarias ?: return Outcome.Err(AppError.InvalidPayload),
                emitida,
                vence,
            )
            val sellado = Spvi23.sellarLicencia(
                body, devicePub, KeystoreManager.signingPair().private,
            )
            val codigo = sellado.codigo()
            if (!Spvi23.verificar(codigo, KeystoreManager.signingPair().public)) {
                return Outcome.Err(AppError.CryptoFailure)
            }
            Outcome.Ok(codigo)
        } catch (_: Exception) {
            Outcome.Err(AppError.CryptoFailure)
        }
    }

    override fun exportar(licencias: List<Licencia>): Outcome<ByteArray> {
        return try {
            licencias.forEach {
                if (EnvelopeValidator.license(it) is Outcome.Err) return Outcome.Err(AppError.ExportFailure)
            }
            val payload = json.encodeToString(ListSerializer(Licencia.serializer()), licencias).toByteArray()
            Outcome.Ok(RegistryPack.pack(payload, master(), KeystoreManager.signingPair().private))
        } catch (_: Exception) {
            Outcome.Err(AppError.ExportFailure)
        }
    }

    override fun importar(bytes: ByteArray): Outcome<List<Licencia>> {
        return when (val pt = RegistryPack.unpack(bytes, master(), KeystoreManager.signingPair().public)) {
            is Outcome.Err -> pt
            is Outcome.Ok -> try {
                val list = json.decodeFromString(ListSerializer(Licencia.serializer()), pt.value.decodeToString())
                if (list.any { !filaValida(it) }) {
                    Outcome.Err(AppError.ImportFailure)
                } else Outcome.Ok(list)
            } catch (_: Exception) {
                Outcome.Err(AppError.ImportFailure)
            }
        }
    }

    private fun filaValida(lic: Licencia): Boolean {
        // v2 trae firma ECDSA propia y se revalida sin el secreto ECDH;
        // v1 sigue la regla estructural de siempre.
        return if (lic.version == 2) {
            EnvelopeValidator.licenciaCorta(lic, KeystoreManager.signingPair().public) is Outcome.Ok
        } else {
            EnvelopeValidator.license(lic) is Outcome.Ok
        }
    }
}
