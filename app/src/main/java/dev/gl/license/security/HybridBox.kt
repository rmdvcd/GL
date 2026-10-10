package dev.gl.license.security

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.Envelope
import java.security.PrivateKey
import java.security.PublicKey

/**
 * ECIES-like: ECDH P-256 → HKDF-SHA256 → AES-256-GCM + ECDSA sobre transcript.
 */
object HybridBox {

    fun seal(
        plaintext: ByteArray,
        peerPublic: PublicKey,
        signingPrivate: PrivateKey,
        info: String,
        aadParts: List<String>,
        keyId: String = "gl-sign-v1",
    ): Envelope {
        val eph = CryptoEngine.generateEphemeralP256()
        val shared = CryptoEngine.ecdh(eph.private, peerPublic)
        val iv = CryptoEngine.randomBytes(CryptoEngine.GCM_IV_LEN)
        val aes = CryptoEngine.deriveAesFromEcdh(shared, iv, info)
        val aad = CryptoEngine.canonical(aadParts)
        val (usedIv, ct, tag) = CryptoEngine.aesGcmEncrypt(aes, plaintext, aad, iv)
        val epk = eph.public.encoded
        val sig = CryptoEngine.sign(signingPrivate, CryptoEngine.transcript(epk, usedIv, ct, tag))
        return Envelope(
            version = CONTRACT_VERSION,
            alg = CryptoEngine.ALG_HYBRID,
            ephemeralPublicKey = CryptoEngine.b64(epk),
            iv = CryptoEngine.b64(usedIv),
            ciphertext = CryptoEngine.b64(ct),
            tag = CryptoEngine.b64(tag),
            signature = CryptoEngine.b64(sig),
            keyId = keyId,
        )
    }

    fun open(
        env: Envelope,
        staticPrivate: PrivateKey,
        verifyKeys: List<PublicKey>,
        info: String,
        aadParts: List<String>,
    ): Outcome<ByteArray> {
        if (env.version != CONTRACT_VERSION) return Outcome.Err(AppError.InvalidPayload)
        if (env.alg != CryptoEngine.ALG_HYBRID) return Outcome.Err(AppError.InvalidPayload)
        if (env.ephemeralPublicKey.isBlank() || env.iv.isBlank() ||
            env.ciphertext.isBlank() || env.tag.isBlank() || env.signature.isBlank()
        ) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        return try {
            val epk = CryptoEngine.parseEcPublic(env.ephemeralPublicKey)
            val iv = CryptoEngine.b64d(env.iv)
            val ct = CryptoEngine.b64d(env.ciphertext)
            val tag = CryptoEngine.b64d(env.tag)
            val sig = CryptoEngine.b64d(env.signature)
            if (iv.size != CryptoEngine.GCM_IV_LEN || tag.size != CryptoEngine.GCM_TAG_LEN) {
                return Outcome.Err(AppError.InvalidPayload)
            }
            val transcript = CryptoEngine.transcript(epk.encoded, iv, ct, tag)
            val sigOk = verifyKeys.any { CryptoEngine.verify(it, transcript, sig) } ||
                CryptoEngine.verify(epk, transcript, sig)
            if (!sigOk) return Outcome.Err(AppError.InvalidPayload)
            val shared = CryptoEngine.ecdh(staticPrivate, epk)
            val aes = CryptoEngine.deriveAesFromEcdh(shared, iv, info)
            val aad = CryptoEngine.canonical(aadParts)
            Outcome.Ok(CryptoEngine.aesGcmDecrypt(aes, iv, ct, tag, aad))
        } catch (_: Exception) {
            Outcome.Err(AppError.InvalidPayload)
        }
    }
}
