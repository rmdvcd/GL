package dev.gl.license.security

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.CONTRACT_VERSION
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.SecretKey

/**
 * Archivo .glreg:
 * magic "GLRG" | u8 version | iv12 | tag16 | u32be ctLen | ct | u32be sigLen | ECDSA(iv||ct||tag)
 * Cipher: AES-256-GCM AAD=glreg-v1
 */
object RegistryPack {

    fun pack(plaintext: ByteArray, aes: SecretKey, signingPrivate: PrivateKey): ByteArray {
        val (iv, ct, tag) = CryptoEngine.aesGcmEncrypt(
            aes,
            plaintext,
            aad = CryptoEngine.AAD_REG.toByteArray()
        )
        val sig = CryptoEngine.sign(signingPrivate, iv + ct + tag)
        return CryptoEngine.REG_MAGIC.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(CONTRACT_VERSION.toByte()) +
            iv + tag +
            CryptoEngine.intToBe(ct.size) + ct +
            CryptoEngine.intToBe(sig.size) + sig
    }

    fun unpack(bytes: ByteArray, aes: SecretKey, verifyPublic: PublicKey): Outcome<ByteArray> {
        return try {
            if (bytes.size < 5 + 12 + 16 + 8) return Outcome.Err(AppError.ImportFailure)
            val magic = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            if (magic != CryptoEngine.REG_MAGIC) return Outcome.Err(AppError.ImportFailure)
            if (bytes[4].toInt() != CONTRACT_VERSION) return Outcome.Err(AppError.ImportFailure)
            var o = 5
            val iv = bytes.copyOfRange(o, o + 12); o += 12
            val tag = bytes.copyOfRange(o, o + 16); o += 16
            val ctLen = CryptoEngine.beToInt(bytes, o); o += 4
            if (ctLen <= 0 || o + ctLen > bytes.size) return Outcome.Err(AppError.ImportFailure)
            val ct = bytes.copyOfRange(o, o + ctLen); o += ctLen
            val sigLen = CryptoEngine.beToInt(bytes, o); o += 4
            if (sigLen <= 0 || o + sigLen > bytes.size) return Outcome.Err(AppError.ImportFailure)
            val sig = bytes.copyOfRange(o, o + sigLen)
            if (!CryptoEngine.verify(verifyPublic, iv + ct + tag, sig)) {
                return Outcome.Err(AppError.ImportFailure)
            }
            Outcome.Ok(
                CryptoEngine.aesGcmDecrypt(aes, iv, ct, tag, aad = CryptoEngine.AAD_REG.toByteArray())
            )
        } catch (_: Exception) {
            Outcome.Err(AppError.ImportFailure)
        }
    }
}
