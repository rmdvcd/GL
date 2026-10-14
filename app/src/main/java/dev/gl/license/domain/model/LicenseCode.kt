package dev.gl.license.domain.model

import dev.gl.license.BuildConfig
import java.security.SecureRandom

/**
 * Generador y validador de códigos de licencia de 16 caracteres alfanuméricos
 * agrupados en 4 grupos de 4 caracteres (ejemplo: ABCD-EFGH-1234-5678).
 */
object LicenseCodeGenerator {
    private const val ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private val rnd = SecureRandom()

    /**
     * Genera un código de 16 caracteres alfanuméricos agrupados en grupos de 4.
     * Ejemplo: "4K7N-9X2M-B8PQ-3T5V"
     */
    fun generate(): String {
        val chars = CharArray(16) { ALPHABET[rnd.nextInt(ALPHABET.length)] }
        return chars.concatToString().chunked(4).joinToString("-")
    }

    /**
     * Comprueba si una cadena contiene un código de 16 caracteres alfanuméricos válido.
     */
    fun isValid(code: String): Boolean {
        val clean = code.replace("-", "").replace(" ", "").trim().uppercase()
        if (clean.length != 16) return false
        return clean.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ" }
    }

    /**
     * Limpia y agrupa en bloques de 4 cualquier código de 16 caracteres.
     */
    fun format(clean: String): String {
        val raw = clean.replace("-", "").replace(" ", "").trim().uppercase()
        return raw.chunked(4).joinToString("-")
    }
}

/**
 * Clave maestra de desarrollo para habilitar cualquier app cliente de forma
 * permanente (sin expiración) y con el número máximo de apps secundarias (10).
 * Tiene 16 caracteres alfanuméricos en 4 grupos de 4.
 */
object MasterKey {
    val CODE: String
        get() = BuildConfig.MASTER_KEY_CODE
    const val MAX_SECUNDARIAS = 10

    /**
     * Comprueba si un código introducido corresponde a la clave maestra.
     * En aplicaciones cliente en producción (release), se debe pasar `false`.
     * El valor por defecto es fail-closed: las llamadas que omitan el entorno ya no habilitan
     * la clave maestra accidentalmente.
     */
    fun isMasterKey(raw: String, isDebug: Boolean = false): Boolean {
        val masterCode = CODE
        if (!isDebug || masterCode.isBlank()) return false
        val clean = raw.replace("-", "").replace(" ", "").trim().uppercase()
        return clean == masterCode.replace("-", "")
    }
}
