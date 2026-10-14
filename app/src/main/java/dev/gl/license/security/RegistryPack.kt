package dev.gl.license.security

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.SecretKey

/**
 * Archivo .glreg. El layout binario se conserva; v1 sigue siendo legible y las
 * exportaciones nuevas usan v2 para versionar el payload con metadatos de precio.
 *
 * Layout: magic "GLRG" | u8 formatVersion | iv12 | tag16 | u32be ctLen | ct |
 *         u32be sigLen | ECDSA(transcript de la versión)
 */
object RegistryPack {
    const val MAX_PACK_BYTES = 32 * 1024 * 1024
    private const val LEGACY_FORMAT_VERSION = 1
    private const val CURRENT_FORMAT_VERSION = 2
    private const val MAX_CIPHERTEXT_BYTES = MAX_PACK_BYTES - (5 + 12 + 16 + 8 + 256)
    private const val MAX_SIGNATURE_BYTES = 256
    private val magicBytes = CryptoEngine.REG_MAGIC.toByteArray(Charsets.US_ASCII)

    fun pack(plaintext: ByteArray, aes: SecretKey, signingPrivate: PrivateKey): ByteArray {
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_CIPHERTEXT_BYTES) { "glreg-size" }
        val version = CURRENT_FORMAT_VERSION
        val (iv, ct, tag) = CryptoEngine.aesGcmEncrypt(
            aes,
            plaintext,
            aad = CryptoEngine.AAD_REG_V2.toByteArray(Charsets.UTF_8),
        )
        val transcript = signatureTranscript(version, iv, ct, tag)
        val sig = CryptoEngine.sign(signingPrivate, transcript)
        return magicBytes +
            byteArrayOf(version.toByte()) +
            iv + tag +
            CryptoEngine.intToBe(ct.size) + ct +
            CryptoEngine.intToBe(sig.size) + sig
    }

    fun unpack(bytes: ByteArray, aes: SecretKey, verifyPublic: PublicKey): Outcome<ByteArray> {
        return try {
            if (bytes.size < 5 + 12 + 16 + 8 || bytes.size > MAX_PACK_BYTES) {
                return Outcome.Err(AppError.ImportFailure)
            }
            val magic = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            if (magic != CryptoEngine.REG_MAGIC) return Outcome.Err(AppError.ImportFailure)
            val version = bytes[4].toInt() and 0xff
            if (version != LEGACY_FORMAT_VERSION && version != CURRENT_FORMAT_VERSION) {
                return Outcome.Err(AppError.ImportFailure)
            }
            var offset = 5
            val iv = bytes.copyOfRange(offset, offset + 12)
            offset += 12
            val tag = bytes.copyOfRange(offset, offset + 16)
            offset += 16
            val ctLen = CryptoEngine.beToInt(bytes, offset)
            offset += 4
            if (ctLen <= 0 || ctLen > MAX_CIPHERTEXT_BYTES || ctLen > bytes.size - offset - 4) {
                return Outcome.Err(AppError.ImportFailure)
            }
            val ciphertext = bytes.copyOfRange(offset, offset + ctLen)
            offset += ctLen
            val sigLen = CryptoEngine.beToInt(bytes, offset)
            offset += 4
            if (sigLen <= 0 || sigLen > MAX_SIGNATURE_BYTES || sigLen != bytes.size - offset) {
                return Outcome.Err(AppError.ImportFailure)
            }
            val signature = bytes.copyOfRange(offset, offset + sigLen)
            if (!CryptoEngine.verify(
                    verifyPublic,
                    signatureTranscript(version, iv, ciphertext, tag),
                    signature,
                )
            ) {
                return Outcome.Err(AppError.ImportFailure)
            }
            val aad = when (version) {
                LEGACY_FORMAT_VERSION -> CryptoEngine.AAD_REG
                CURRENT_FORMAT_VERSION -> CryptoEngine.AAD_REG_V2
                else -> return Outcome.Err(AppError.ImportFailure)
            }
            Outcome.Ok(
                CryptoEngine.aesGcmDecrypt(
                    aes,
                    iv,
                    ciphertext,
                    tag,
                    aad = aad.toByteArray(Charsets.UTF_8),
                )
            )
        } catch (_: Exception) {
            Outcome.Err(AppError.ImportFailure)
        }
    }

    private fun signatureTranscript(
        version: Int,
        iv: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
    ): ByteArray = when (version) {
        LEGACY_FORMAT_VERSION -> iv + ciphertext + tag
        CURRENT_FORMAT_VERSION -> magicBytes + byteArrayOf(version.toByte()) + iv + ciphertext + tag
        else -> throw IllegalArgumentException("glreg-version")
    }
}
