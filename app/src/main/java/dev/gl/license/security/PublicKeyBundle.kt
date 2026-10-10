package dev.gl.license.security

import java.security.MessageDigest

/**
 * Formateo de las claves públicas de GL para exportarlas a una app cliente.
 *
 * Todo aquí es puro y sin Keystore a propósito: la parte que formatea es
 * testeable en JVM, y solo la lectura de las claves toca hardware.
 *
 * OJO: las claves son **de este dispositivo**. Android Keystore las genera
 * localmente la primera vez que se usan. Si se borran los datos de GL o se
 * cambia de teléfono, salen otras y la app cliente rechazará las licencias.
 */
object PublicKeyBundle {
    private const val PREFIX = "sha256:"

    /** Formato `sha256:aa:bb:cc:...`, 64 hex en 16 grupos de 4. */
    fun fingerprint(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            hex.append("0123456789abcdef"[v ushr 4])
            hex.append("0123456789abcdef"[v and 0x0F])
        }
        return PREFIX + hex.toString().chunked(4).joinToString(":")
    }

    /** SPKI DER en Base64, tal y como espera el cliente. */
    fun toBase64(spki: ByteArray): String = CryptoEngine.b64(spki)

    /**
     * Lo que se pega en el `LicenseTrust.kt` del cliente. Sin material
     * privado: aquí solo hay claves públicas.
     */
    fun clipboardPayload(
        encryptionKey: String,
        signingKey: String,
        encryptionFingerprint: String,
        signingFingerprint: String,
        contractVersion: Int,
    ): String = buildString {
        append("{\n")
        append("  \"contractVersion\": ").append(contractVersion).append(",\n")
        append("  \"encryptionKey\": \"").append(encryptionKey).append("\",\n")
        append("  \"encryptionFingerprint\": \"").append(encryptionFingerprint).append("\",\n")
        append("  \"signingKey\": \"").append(signingKey).append("\",\n")
        append("  \"signingFingerprint\": \"").append(signingFingerprint).append("\"\n")
        append("}")
    }
}
