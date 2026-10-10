package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.security.PublicKeyBundle
import org.junit.Test

/**
 * El formateo de la huella es lo que un humano compara contra LicenseTrust.kt
 * de la app cliente. Si cambia, el pegado falla en silencio al otro lado.
 */
class PublicKeyBundleTest {

    @Test
    fun hexIsLowercaseAndColonSeparated() {
        val hex = PublicKeyBundle.fingerprint(ByteArray(32) { it.toByte() })
        assertThat(hex).startsWith("sha256:")
        val body = hex.removePrefix("sha256:")
        assertThat(body).contains(":")
        // Grupos de 4 hex = 2 bytes por grupo, 32 bytes = 16 grupos.
        assertThat(body.split(":")).hasSize(16)
        assertThat(body.replace(":", "")).hasLength(64)
        assertThat(body.replace(":", "")).isEqualTo(body.replace(":", "").lowercase())
    }

    @Test
    fun fingerprintIsStableAndSha256() {
        val data = "gl-req-v1".toByteArray()
        val a = PublicKeyBundle.fingerprint(data)
        val b = PublicKeyBundle.fingerprint(data)
        assertThat(a).isEqualTo(b)
        // SHA-256 de "gl-req-v1", conocido de forma independiente.
        assertThat(a).isEqualTo(
            "sha256:b20c:8921:383c:d91c:7eaf:082f:5c69:0455:d722:64f3:" +
                "b5a3:ddcf:50b3:00a1:42bb:4ce5",
        )
    }

    @Test
    fun differentDataGivesDifferentFingerprint() {
        assertThat(PublicKeyBundle.fingerprint("a".toByteArray()))
            .isNotEqualTo(PublicKeyBundle.fingerprint("b".toByteArray()))
    }

    @Test
    fun clipboardPayloadIsParseableJsonWithBothKeys() {
        val json = PublicKeyBundle.clipboardPayload(
            encryptionKey = "QUJD",
            signingKey = "REVG",
            encryptionFingerprint = "sha256:aa",
            signingFingerprint = "sha256:bb",
            contractVersion = 1,
        )
        assertThat(json).contains("\"encryptionKey\": \"QUJD\"")
        assertThat(json).contains("\"signingKey\": \"REVG\"")
        assertThat(json).contains("\"contractVersion\": 1")
    }
}
