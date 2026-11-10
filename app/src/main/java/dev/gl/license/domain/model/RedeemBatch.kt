package dev.gl.license.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Lote de codigos de canje acuñado por GL para el servicio de activacion.
 *
 * El lote se firma con la misma clave ECDSA `gl-sign-v1` del Keystore que ya
 * emite licencias. La canonicalizacion es identica a la del servicio
 * (`services/redeem/src/batch.js`): cada campo UTF-8 precedido por su longitud
 * en 4 bytes big-endian. La firma no cubre el JSON: cubre esos bytes.
 *
 * Este formato es aditivo y no modifica el contrato v1 ni SPVI2.
 */
const val REDEEM_BATCH_VERSION = 1
const val REDEEM_BATCH_ALG = "GL-BATCH-P256-SHA256-v1"
const val REDEEM_BATCH_DOMAIN = "GL-BATCH1"
const val REDEEM_BATCH_ENTRY_DOMAIN = "GL-BATCH1-ENTRY"
const val REDEEM_BATCH_MAX_ENTRIES = 5000

@Serializable
data class RedeemBatchEntry(
    @SerialName("code") val code: String,
    @SerialName("type") val type: TipoLicencia,
    @SerialName("secundarias") val secundarias: Int = 0,
    @SerialName("redeemBefore") val redeemBefore: String? = null,
)

@Serializable
data class RedeemBatch(
    @SerialName("v") val version: Int = REDEEM_BATCH_VERSION,
    @SerialName("alg") val alg: String = REDEEM_BATCH_ALG,
    @SerialName("batchId") val batchId: String,
    @SerialName("issuerKid") val issuerKid: String = "gl-sign-v1",
    @SerialName("createdAt") val createdAt: String,
    @SerialName("appId") val appId: String,
    @SerialName("entries") val entries: List<RedeemBatchEntry>,
    @SerialName("sig") val signature: String,
)

object RedeemBatchCanonical {
    private val normalizedRe = Regex("^[0-9A-HJ-NP-Z]{16}$")
    private val appIdRe = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$")
    private val batchIdRe = Regex("^[A-Za-z0-9._:-]{8,64}$")

    /** Quita guiones/espacios y valida el alfabeto sin I ni O. Null si no sirve. */
    fun normalize(code: String): String? {
        if (code.length > 64) return null
        val clean = code.replace("-", "").replace(" ", "").trim().uppercase()
        return if (normalizedRe.matches(clean)) clean else null
    }

    fun format(normalized: String): String {
        require(normalizedRe.matches(normalized)) { "redeem-code" }
        return normalized.chunked(4).joinToString("-")
    }

    fun isValidAppId(appId: String): Boolean = appIdRe.matches(appId)

    fun isValidBatchId(batchId: String): Boolean = batchIdRe.matches(batchId)

    /** Length-Prefixing de 4 bytes big-endian, igual que `CryptoEngine.canonical`. */
    fun canonical(parts: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            out.write(
                byteArrayOf(
                    ((bytes.size ushr 24) and 0xff).toByte(),
                    ((bytes.size ushr 16) and 0xff).toByte(),
                    ((bytes.size ushr 8) and 0xff).toByte(),
                    (bytes.size and 0xff).toByte(),
                ),
            )
            out.write(bytes)
        }
        return out.toByteArray()
    }

    fun entryBytes(entry: RedeemBatchEntry): ByteArray {
        val normalized = normalize(entry.code) ?: throw IllegalArgumentException("redeem-code")
        require(entry.secundarias in 0..10) { "redeem-secondary" }
        return canonical(
            listOf(
                REDEEM_BATCH_ENTRY_DOMAIN,
                normalized,
                entry.type.name,
                entry.secundarias.toString(),
                entry.redeemBefore ?: "",
            ),
        )
    }

    fun signingBytes(batch: RedeemBatch): ByteArray {
        require(batch.version == REDEEM_BATCH_VERSION) { "redeem-version" }
        require(batch.alg == REDEEM_BATCH_ALG) { "redeem-alg" }
        require(batch.issuerKid == "gl-sign-v1") { "redeem-kid" }
        require(isValidBatchId(batch.batchId)) { "redeem-batch-id" }
        require(isValidAppId(batch.appId)) { "redeem-app-id" }
        require(batch.entries.isNotEmpty()) { "redeem-entries" }
        require(batch.entries.size <= REDEEM_BATCH_MAX_ENTRIES) { "redeem-entries" }

        val parts = mutableListOf(
            REDEEM_BATCH_DOMAIN,
            batch.version.toString(),
            batch.alg,
            batch.batchId,
            batch.issuerKid,
            batch.createdAt,
            batch.appId,
            batch.entries.size.toString(),
        )
        val digest = MessageDigest.getInstance("SHA-256")
        for (entry in batch.entries) {
            val hash = digest.digest(entryBytes(entry))
            parts += Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        }
        return canonical(parts)
    }
}
