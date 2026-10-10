package dev.gl.license.security

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.Envelope
import dev.gl.license.domain.model.IssuedLicense
import dev.gl.license.domain.usecase.FieldValidator

object EnvelopeValidator {

    fun structural(env: Envelope): Outcome<Envelope> {
        if (env.version != CONTRACT_VERSION) return Outcome.Err(AppError.InvalidPayload)
        if (env.alg != CryptoEngine.ALG_HYBRID) return Outcome.Err(AppError.InvalidPayload)
        if (env.keyId.isBlank()) return Outcome.Err(AppError.InvalidPayload)
        return try {
            val iv = CryptoEngine.b64d(env.iv)
            val tag = CryptoEngine.b64d(env.tag)
            val ct = CryptoEngine.b64d(env.ciphertext)
            val sig = CryptoEngine.b64d(env.signature)
            CryptoEngine.parseEcPublic(env.ephemeralPublicKey)
            if (iv.size != CryptoEngine.GCM_IV_LEN) return Outcome.Err(AppError.InvalidPayload)
            if (tag.size != CryptoEngine.GCM_TAG_LEN) return Outcome.Err(AppError.InvalidPayload)
            if (ct.isEmpty() || sig.isEmpty()) return Outcome.Err(AppError.InvalidPayload)
            Outcome.Ok(env)
        } catch (_: Exception) {
            Outcome.Err(AppError.InvalidPayload)
        }
    }

    fun license(l: IssuedLicense): Outcome<IssuedLicense> = FieldValidator.validateLicense(l)

    /**
     * Licencia corta SPVI2: validación estructural v2 + firma ECDSA de gl-sign-v1
     * sobre el transcrito del código. La firma no necesita el secreto ECDH.
     */
    fun licenciaCorta(l: IssuedLicense, firmaPub: java.security.PublicKey): Outcome<IssuedLicense> {
        when (val v = FieldValidator.validateLicense(l)) {
            is Outcome.Err -> return v
            is Outcome.Ok -> Unit
        }
        val codigo = l.codigoCorto ?: return Outcome.Err(AppError.InvalidPayload)
        return if (Spvi23.verificar(codigo, firmaPub)) Outcome.Ok(l)
        else Outcome.Err(AppError.InvalidPayload)
    }
}
