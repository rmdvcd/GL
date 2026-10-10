package dev.gl.license.security

import java.security.InvalidAlgorithmParameterException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitivas puras (sin Android Keystore) para poder testear en JVM.
 * AES-256-GCM, ECDH P-256, HKDF-SHA256, ECDSA P-256, PBKDF2.
 */
object CryptoEngine {
    const val GCM_IV_LEN = 12
    const val GCM_TAG_LEN = 16
    const val GCM_TAG_BITS = 128
    const val GCM_TRANSFORM = "AES/GCM/NoPadding"
    const val ALG_HYBRID = "ECIES-P256-AES256GCM-v1"
    const val SIG_ALG = "SHA256withECDSA"
    const val REG_MAGIC = "GLRG"
    const val AAD_DB = "gl-db-v1"
    const val AAD_REG = "glreg-v1"
    const val INFO_REQ = "gl-req-v1"
    const val INFO_LIC = "gl-lic-v1"
    const val PBKDF2_ITERS = 310_000

    private val rnd = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
    fun b64d(s: String): ByteArray = Base64.getDecoder().decode(s)

    /**
     * Cifra AES-256-GCM. El IV devuelto es el que el llamante debe persistir.
     *
     * Las claves de AndroidKeyStore creadas con `setRandomizedEncryptionRequired(true)`
     * (ver [KeystoreManager]) rechazan un IV aportado por el llamante con
     * `InvalidAlgorithmParameterException: Caller-provided IV not permitted`. Para esas
     * claves se deja que el propio Keystore genere el IV y se devuelve, de modo que el
     * contrato hacia el llamante no cambia. El resto de claves (JVM, BouncyCastle) siguen
     * usando el IV generado aquí, así que los tests en JVM se comportan igual.
     *
     * La detección NO se puede hacer por `key.format`: en Android devuelve `null` para
     * las claves de AndroidKeyStore (comprobado en dispositivo con API 35), así que se
     * intenta primero con el IV del llamante y se reintenta sin él si el Keystore lo
     * rechaza.
     */
    fun aesGcmEncrypt(
        key: SecretKey,
        plaintext: ByteArray,
        aad: ByteArray? = null,
        iv: ByteArray = randomBytes(GCM_IV_LEN),
    ): Triple<ByteArray, ByteArray, ByteArray> {
        require(iv.size == GCM_IV_LEN)
        var c: Cipher
        var effectiveIv: ByteArray
        try {
            c = Cipher.getInstance(GCM_TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            effectiveIv = iv
        } catch (_: InvalidAlgorithmParameterException) {
            // El Keystore rechazó el IV del llamante. Que lo genere él y se devuelve,
            // para que el llamante lo persista igual que en el resto de claves.
            c = Cipher.getInstance(GCM_TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key)
            effectiveIv = c.iv
            require(effectiveIv.size == GCM_IV_LEN) { "keystore-iv" }
        }
        if (aad != null) c.updateAAD(aad)
        val out = c.doFinal(plaintext)
        val ct = out.copyOfRange(0, out.size - GCM_TAG_LEN)
        val tag = out.copyOfRange(out.size - GCM_TAG_LEN, out.size)
        return Triple(effectiveIv, ct, tag)
    }

    fun aesGcmDecrypt(
        key: SecretKey,
        iv: ByteArray,
        ct: ByteArray,
        tag: ByteArray,
        aad: ByteArray? = null,
    ): ByteArray {
        if (iv.size != GCM_IV_LEN || tag.size != GCM_TAG_LEN) {
            throw IllegalArgumentException("gcm")
        }
        val c = Cipher.getInstance(GCM_TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(ct + tag)
    }

    fun generateEphemeralP256(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), rnd)
        return kpg.generateKeyPair()
    }

    fun ecdh(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        return ka.generateSecret()
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val saltKey = SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256")
        mac.init(saltKey)
        val prk = mac.doFinal(ikm)
        var t = ByteArray(0)
        val okm = ByteArray(len)
        var copied = 0
        var i = 1
        while (copied < len) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, len - copied)
            System.arraycopy(t, 0, okm, copied, n)
            copied += n
            i++
        }
        return okm
    }

    fun deriveAesFromEcdh(shared: ByteArray, salt: ByteArray, info: String): SecretKey {
        val okm = hkdfSha256(shared, salt, info.toByteArray(Charsets.UTF_8), 32)
        return SecretKeySpec(okm, "AES")
    }

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val s = Signature.getInstance(SIG_ALG)
        s.initSign(privateKey, rnd)
        s.update(data)
        return s.sign()
    }

    fun verify(publicKey: PublicKey, data: ByteArray, sig: ByteArray): Boolean {
        return try {
            val s = Signature.getInstance(SIG_ALG)
            s.initVerify(publicKey)
            s.update(data)
            s.verify(sig)
        } catch (_: Exception) {
            false
        }
    }

    fun parseEcPublic(pemOrDerB64: String): PublicKey {
        val cleaned = pemOrDerB64
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = b64d(cleaned)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }

    fun pbkdf2HmacSha256(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERS): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }

    fun canonical(parts: List<String>): ByteArray =
        parts.joinToString("|").toByteArray(Charsets.UTF_8)

    fun transcript(epkDer: ByteArray, iv: ByteArray, ct: ByteArray, tag: ByteArray): ByteArray =
        epkDer + iv + ct + tag

    fun intToBe(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xff).toByte(),
        ((v ushr 16) and 0xff).toByte(),
        ((v ushr 8) and 0xff).toByte(),
        (v and 0xff).toByte(),
    )

    fun beToInt(b: ByteArray, o: Int): Int {
        if (o + 4 > b.size) throw IllegalArgumentException("be")
        return ((b[o].toInt() and 0xff) shl 24) or
            ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or
            (b[o + 3].toInt() and 0xff)
    }
}
