package dev.gl.license.security

import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.Envelope
import dev.gl.license.domain.model.LicenseRequest
import kotlinx.serialization.json.Json

/**
 * Utilidad de referencia para que las apps cliente construyan una solicitud.
 * Debe ejecutarse en el dispositivo del cliente con su propio par ECDH.
 * No se usa en producción de GL salvo tests.
 *
 * Una solicitud v1 (envelope JSON, legada) puede fijar `LicenseRequest.secundarias` en 0–10 en otras
 * apps; GL la ignora si no aplica. SPVI 0.23.1 ya no usa este formato (ver `Spvi23.sellarSolicitud`
 * como referencia del formato nuevo y `CONTEXTO_LICENCIAS.md`). El `Json` del llamador decide la política de
 * valores por defecto al serializar (GL usa omisión cuando es `null`).
 */
object ClientRequestHelper {
    fun buildEnvelope(req: LicenseRequest, peerPublic: java.security.PublicKey, json: Json): String {
        val eph = CryptoEngine.generateEphemeralP256()
        val shared = CryptoEngine.ecdh(eph.private, peerPublic)
        val payload = json.encodeToString(LicenseRequest.serializer(), req).toByteArray()
        val iv0 = CryptoEngine.randomBytes(12)
        val aes = CryptoEngine.deriveAesFromEcdh(shared, iv0, "gl-req-v1")
        val aad = CryptoEngine.canonical(
            listOf(CONTRACT_VERSION.toString(), CryptoEngine.ALG_HYBRID, CryptoEngine.b64(eph.public.encoded), "gl-sign-v1")
        )
        val (iv, ct, tag) = CryptoEngine.aesGcmEncrypt(aes, payload, aad)
        val toSign = eph.public.encoded + iv + ct + tag
        val sig = CryptoEngine.sign(eph.private, toSign)
        val env = Envelope(
            version = CONTRACT_VERSION,
            alg = CryptoEngine.ALG_HYBRID,
            ephemeralPublicKey = CryptoEngine.b64(eph.public.encoded),
            iv = CryptoEngine.b64(iv),
            ciphertext = CryptoEngine.b64(ct),
            tag = CryptoEngine.b64(tag),
            signature = CryptoEngine.b64(sig),
            keyId = "gl-sign-v1",
        )
        return json.encodeToString(Envelope.serializer(), env)
    }

    /**
     * Construye una solicitud con todos los campos requeridos:
     * - ID del dispositivo solicitante
     * - Nombre de la app que solicita la licencia
     * - Dispositivo desde el cual se solicita la licencia
     * - Datos del usuario
     * - Tipo de licencia solicitada y cantidad de apps secundarias
     */
    fun buildRequest(
        deviceId: String,
        appName: String,
        dispositivo: String,
        nombre: String,
        apellidos: String,
        ci: String,
        telefono: String,
        via: dev.gl.license.domain.model.ViaSolicitud,
        tipo: dev.gl.license.domain.model.TipoLicencia,
        secundarias: Int? = null,
        devicePublicKeyPem: String? = null,
    ): LicenseRequest {
        val nowIso = java.time.Instant.now().toString()
        val nonce = CryptoEngine.b64(CryptoEngine.randomBytes(16))
        return LicenseRequest(
            version = CONTRACT_VERSION,
            deviceId = deviceId,
            appName = appName,
            dispositivo = dispositivo,
            firstName = nombre,
            lastName = apellidos,
            nationalId = ci,
            channel = via,
            phone = telefono,
            usuario = dev.gl.license.domain.model.DatosUsuario(
                nombre = nombre,
                apellidos = apellidos,
                ci = ci,
                telefono = telefono,
                via = via,
            ),
            tipo = tipo,
            secundarias = secundarias,
            requestedAtIso = nowIso,
            nonce = nonce,
            devicePublicKeyPem = devicePublicKeyPem,
        )
    }
}
