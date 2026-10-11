package dev.gl.license.security

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Secundarias
import dev.gl.license.domain.model.SolicitudSpviV2
import dev.gl.license.domain.model.TipoLicencia
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.crypto.spec.SecretKeySpec

/**
 * Protocolo SPVI 0.23.1: solicitudes SPVIR1 y licencias cortas SPVI2.
 * Objeto puro (sin Android Keystore) para poder testear en JVM.
 * El flujo v1 de otras apps ([HybridBox]) no se toca.
 */
object Spvi23 {
    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    fun b64urlE(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun b64urlD(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s)

    /** Punto P-256 a SEC1 comprimido (33 B: 0x02/0x03 || x). */
    fun comprimirPunto(pub: PublicKey): ByteArray {
        val w = (pub as ECPublicKey).w
        val x = a32(w.affineX)
        return byteArrayOf(if (w.affineY.testBit(0)) 0x03 else 0x02) + x
    }

    /** SEC1 comprimido (33 B) a clave pública P-256. Lanza IllegalArgumentException si no es válido. */
    fun descomprimirPunto(c: ByteArray): PublicKey {
        require(c.size == 33 && (c[0] == 0x02.toByte() || c[0] == 0x03.toByte())) { "spvi-punto" }
        val x = BigInteger(1, c.copyOfRange(1, 33))
        require(x < P) { "spvi-punto" }
        val rhs = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
        var y = rhs.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (!y.multiply(y).mod(P).equals(rhs)) throw IllegalArgumentException("spvi-punto")
        if (y.testBit(0) != (c[0] == 0x03.toByte())) y = P.subtract(y)
        val params = (CryptoEngine.generateEphemeralP256().public as ECPublicKey).params
        val point = java.security.spec.ECPoint(x, y)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
    }

    private fun a32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        require(raw.size <= 33) { "spvi-x" }
        val striped = if (raw.size == 33) raw.copyOfRange(1, 33) else raw
        return ByteArray(32 - striped.size) + striped
    }

    private val codigoRe = Regex("[A-Za-z0-9\\-_]")
    private val jsonV2 = Json { ignoreUnknownKeys = true }
    private val phoneRe = Regex("^\\+?[1-9]\\d{7,14}$")
    private val ciRe = Regex("^[A-Za-z0-9]{5,20}$")
    private val nameRe = Regex("^[\\p{L} .'-]{2,80}$")
    private val deviceRe = Regex("^[A-Za-z0-9:_-]{8,128}$")

    fun contieneCodigo(raw: String): Boolean = raw.contains("SPVIR1:")

    /** Extrae el Base64url tras `SPVIR1:`, ignorando blancos y parando en el primer otro carácter. */
    fun extraerCodigo(raw: String): Outcome<String> {
        val i = raw.indexOf("SPVIR1:")
        if (i < 0) return Outcome.Err(AppError.SpviNoEs023)
        val sb = StringBuilder()
        for (c in raw.substring(i + "SPVIR1:".length)) {
            if (c.isWhitespace()) continue
            if (!codigoRe.matches(c.toString())) break
            sb.append(c)
        }
        if (sb.isEmpty()) return Outcome.Err(AppError.SpviNoEs023)
        return Outcome.Ok(sb.toString())
    }

    /**
     * Referencia para que el cliente cifre (patrón [dev.gl.license.security.ClientRequestHelper]).
     * No se usa en producción de GL salvo tests.
     */
    fun sellarSolicitud(dto: SolicitudSpviV2, glEcdhPub: PublicKey, efimera: KeyPair? = null): String {
        val eph = efimera ?: CryptoEngine.generateEphemeralP256()
        val epk = comprimirPunto(eph.public)
        val ikm = CryptoEngine.ecdh(eph.private, glEcdhPub)
        val okm = CryptoEngine.hkdfSha256(ikm, epk, "SPVI-R1".toByteArray(Charsets.UTF_8), 44)
        val key = SecretKeySpec(okm.copyOfRange(0, 32), "AES")
        val iv = okm.copyOfRange(32, 44)
        val aad = "SPVI-R1|".toByteArray(Charsets.UTF_8) + epk
        val plano = jsonV2.encodeToString(SolicitudSpviV2.serializer(), dto).toByteArray(Charsets.UTF_8)
        val (_, ct, tag) = CryptoEngine.aesGcmEncrypt(key, plano, aad, iv)
        return b64urlE(epk + ct + tag)
    }

    /** Descifra `epk(33)‖ct‖tag(16)` y valida el JSON v2. GCM o validación fallan → error, nunca lanza. */
    fun abrir(codigoB64: String, ecdhPrivada: PrivateKey): Outcome<SolicitudSpviV2> {
        return try {
            val raw = b64urlD(codigoB64.trim())
            if (raw.size < 50) return Outcome.Err(AppError.SpviDanada)
            val epk = raw.copyOfRange(0, 33)
            if (epk[0] != 0x02.toByte() && epk[0] != 0x03.toByte()) return Outcome.Err(AppError.SpviDanada)
            val ct = raw.copyOfRange(33, raw.size - 16)
            val tag = raw.copyOfRange(raw.size - 16, raw.size)
            val ikm = CryptoEngine.ecdh(ecdhPrivada, descomprimirPunto(epk))
            val okm = CryptoEngine.hkdfSha256(ikm, epk, "SPVI-R1".toByteArray(Charsets.UTF_8), 44)
            val key = SecretKeySpec(okm.copyOfRange(0, 32), "AES")
            val iv = okm.copyOfRange(32, 44)
            val aad = "SPVI-R1|".toByteArray(Charsets.UTF_8) + epk
            val plano = try {
                CryptoEngine.aesGcmDecrypt(key, iv, ct, tag, aad)
            } catch (_: Exception) {
                return Outcome.Err(AppError.SpviDanada)
            }
            val dto = try {
                jsonV2.decodeFromString(SolicitudSpviV2.serializer(), plano.decodeToString())
            } catch (_: Exception) {
                return Outcome.Err(AppError.SpviDanada)
            }
            validar(dto)
        } catch (_: Exception) {
            Outcome.Err(AppError.SpviDanada)
        }
    }

    private fun validar(dto: SolicitudSpviV2): Outcome<SolicitudSpviV2> {        if (dto.v != 2) return Outcome.Err(AppError.InvalidPayload)
        if (!nameRe.matches(dto.nombre.trim()) || !nameRe.matches(dto.apellidos.trim())) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        if (!ciRe.matches(dto.ci.trim())) return Outcome.Err(AppError.InvalidPayload)
        if (!phoneRe.matches(dto.telefono.trim())) return Outcome.Err(AppError.InvalidPayload)
        if (!deviceRe.matches(dto.deviceId.trim()) || !dto.deviceId.startsWith("SPVI:")) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        if (dto.nonce.isBlank()) return Outcome.Err(AppError.InvalidPayload)
        when (val s = Secundarias.validar(null, dto.deviceId, dto.secundarias)) {
            is Outcome.Err -> return s
            is Outcome.Ok -> Unit
        }
        try {
            Instant.parse(dto.solicitadaEn)
        } catch (_: DateTimeParseException) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        try {
            descomprimirPunto(b64urlD(dto.devicePub.trim()))
        } catch (_: Exception) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        return Outcome.Ok(dto)
    }

    /** Primeros 16 B de SHA-256(`"SPVI-L2|" + deviceId + "|"` ‖ devicePub comprimida). */
    fun huella(deviceId: String, devicePub33: ByteArray): ByteArray {
        require(devicePub33.size == 33) { "spvi-pub" }
        val pre = "SPVI-L2|$deviceId|".toByteArray(Charsets.UTF_8) + devicePub33
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(pre).copyOfRange(0, 16)
    }

    /** Cuerpo en claro de 44 B big-endian. `venceSec == null` (perpetua) se escribe como 0. */
    fun cuerpo(
        id: java.util.UUID,
        huella16: ByteArray,
        tipo: TipoLicencia,
        estado: EstadoLicencia,
        secundarias: Int,
        emitidaSec: Long,
        venceSec: Long?,
    ): ByteArray {
        require(huella16.size == 16) { "spvi-huella" }
        val buf = java.nio.ByteBuffer.allocate(44)
        buf.put(0x02.toByte())
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        buf.put(huella16)
        buf.put(tipo.ordinal.toByte())
        buf.put(estado.ordinal.toByte())
        buf.put(secundarias.toByte())
        buf.putInt(emitidaSec.toInt())
        buf.putInt((venceSec ?: 0L).toInt())
        return buf.array()
    }

    data class SelladoSpvi(
        val epk: ByteArray,
        val ct: ByteArray,
        val tag: ByteArray,
        val firma: ByteArray,
    ) {
        /** `"SPVI2:"` + Base64url sin relleno de 157 B = 210 caracteres tras el prefijo. */
        fun codigo(): String = "SPVI2:" + Spvi23.b64urlE(epk + ct + tag + firma)
    }

    /**
     * Cifra el cuerpo hacia `devicePub` con efímera fresca (o la dada, solo tests)
     * y firma `epk‖ct‖tag`. La firma ECDSA usa P1363 si el proveedor lo soporta.
     */
    fun sellarLicencia(
        body: ByteArray,
        devicePub: PublicKey,
        firmaPriv: PrivateKey,
        efimera: KeyPair? = null,
    ): SelladoSpvi {
        val eph = efimera ?: CryptoEngine.generateEphemeralP256()
        val epk = comprimirPunto(eph.public)
        val ikm = CryptoEngine.ecdh(eph.private, devicePub)
        val okm = CryptoEngine.hkdfSha256(ikm, epk, "SPVI-L2".toByteArray(Charsets.UTF_8), 44)
        val key = SecretKeySpec(okm.copyOfRange(0, 32), "AES")
        val iv = okm.copyOfRange(32, 44)
        val aad = "SPVI-L2|".toByteArray(Charsets.UTF_8) + epk
        val (_, ct, tag) = CryptoEngine.aesGcmEncrypt(key, body, aad, iv)
        val firma = firmarRaw(firmaPriv, "SPVI-L2|".toByteArray(Charsets.UTF_8) + epk + ct + tag)
        return SelladoSpvi(epk, ct, tag, firma)
    }

    /** Firma ECDSA P-256/SHA-256 en crudo r‖s (64 B). */
    fun firmarRaw(priv: PrivateKey, msg: ByteArray): ByteArray {
        return try {
            val s = java.security.Signature.getInstance("SHA256withECDSAinP1363Format")
            s.initSign(priv)
            s.update(msg)
            s.sign()
        } catch (_: Exception) {
            derToRaw(CryptoEngine.sign(priv, msg))
        }
    }

    /** DER SEQUENCE(INTEGER r, INTEGER s) → crudo r‖s de 64 B con ceros a la izquierda. */
    fun derToRaw(der: ByteArray): ByteArray {
        var o = 0
        require(der[o++] == 0x30.toByte()) { "spvi-der" }
        o = derLen(der, o).second
        require(der[o++] == 0x02.toByte()) { "spvi-der" }
        val (rLen, rOff) = derLen(der, o)
        val r = der.copyOfRange(rOff, rOff + rLen)
        o = rOff + rLen
        require(der[o++] == 0x02.toByte()) { "spvi-der" }
        val (sLen, sOff) = derLen(der, o)
        val s = der.copyOfRange(sOff, sOff + sLen)
        return a32(BigInteger(1, r)) + a32(BigInteger(1, s))
    }

    private fun derLen(der: ByteArray, o: Int): Pair<Int, Int> {
        val b = der[o].toInt() and 0xff
        return if (b < 0x80) {
            Pair(b, o + 1)
        } else {
            val n = b and 0x7f
            var len = 0
            for (i in 1..n) len = (len shl 8) or (der[o + i].toInt() and 0xff)
            Pair(len, o + 1 + n)
        }
    }

    /** Verifica firma del código con la pública de firma. Acepta con o sin prefijo. Nunca lanza. */
    fun verificar(codigo: String, firmaPub: PublicKey): Boolean {
        return try {
            val raw = b64urlD(codigo.removePrefix("SPVI2:").trim())
            if (raw.size != 33 + 44 + 16 + 64) return false
            val epk = raw.copyOfRange(0, 33)
            val ct = raw.copyOfRange(33, 77)
            val tag = raw.copyOfRange(77, 93)
            val firma = raw.copyOfRange(93, 157)
            val msg = "SPVI-L2|".toByteArray(Charsets.UTF_8) + epk + ct + tag
            try {
                val s = java.security.Signature.getInstance("SHA256withECDSAinP1363Format")
                s.initVerify(firmaPub)
                s.update(msg)
                s.verify(firma)
            } catch (_: Exception) {
                CryptoEngine.verify(firmaPub, msg, rawToDer(firma))
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64) { "spvi-raw" }
        val r = raw.copyOfRange(0, 32).stripLeadingZeros()
        val s = raw.copyOfRange(32, 64).stripLeadingZeros()
        val body = derInt(r) + derInt(s)
        return byteArrayOf(0x30.toByte()) + derLenBytes(body.size) + body
    }

    private fun ByteArray.stripLeadingZeros(): ByteArray {
        var i = 0
        while (i < size - 1 && this[i] == 0.toByte()) i++
        return copyOfRange(i, size)
    }

    private fun derInt(v: ByteArray): ByteArray {
        val padded = if (v[0].toInt() and 0x80 != 0) byteArrayOf(0) + v else v
        return byteArrayOf(0x02.toByte()) + derLenBytes(padded.size) + padded
    }

    private fun derLenBytes(len: Int): ByteArray =
        if (len < 0x80) {
            byteArrayOf(len.toByte())
        } else {
            val raw = BigInteger.valueOf(len.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            byteArrayOf((0x80 or raw.size).toByte()) + raw
        }
}
