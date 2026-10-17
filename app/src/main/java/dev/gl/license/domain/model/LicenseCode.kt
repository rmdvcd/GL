package dev.gl.license.domain.model

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
