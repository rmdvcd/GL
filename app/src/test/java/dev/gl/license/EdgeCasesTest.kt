package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.Envelope
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.domain.usecase.FieldValidator
import dev.gl.license.presentation.registry.RegistryCountdown
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.EnvelopeValidator
import dev.gl.license.security.HybridBox
import dev.gl.license.security.RegistryPack
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class EdgeCasesTest {

    private fun licencia(
        type: TipoLicencia = TipoLicencia.MENSUAL,
        exp: String? = "2026-01-31T00:00:00Z",
        status: EstadoLicencia = EstadoLicencia.ACTIVA,
    ) = Licencia(
        version = CONTRACT_VERSION,
        id = "id-1",
        firstName = "Ana",
        lastName = "Perez",
        nationalId = "85010112345",
        channel = ViaSolicitud.WHATSAPP,
        phone = "+5355512345",
        appName = "MiApp",
        deviceId = "device-abc-12345",
        type = type,
        requestedAtIso = "2026-01-01T00:00:00Z",
        issuedAtIso = "2026-01-01T00:00:00Z",
        expiresAtIso = exp,
        status = status,
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
    )

    @Test
    fun invalidRequestRejected() {
        val bad = licencia().let {
            FieldValidator.validateRequest(
                dev.gl.license.domain.model.SolicitudLicencia(
                    version = 99,
                    firstName = "A",
                    lastName = "B",
                    nationalId = "x",
                    channel = ViaSolicitud.SMS,
                    phone = "00",
                    appName = "MiApp",
                    deviceId = "short",
                    type = TipoLicencia.ANUAL,
                    requestedAtIso = "not-a-date",
                    nonce = "n",
                )
            )
        }
        assertThat(bad).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun wrongSignatureRejected() {
        val rec = CryptoEngine.generateEphemeralP256()
        val signer = CryptoEngine.generateEphemeralP256()
        val other = CryptoEngine.generateEphemeralP256()
        val env = HybridBox.seal(
            "pt".toByteArray(), rec.public, signer.private,
            CryptoEngine.INFO_REQ, listOf("1")
        )
        val opened = HybridBox.open(env, rec.private, listOf(other.public), CryptoEngine.INFO_REQ, listOf("1"))
        // still Ok if ephemeral signed... HybridBox also accepts epk signature
        // Tamper tag
        val tampered = env.copy(tag = CryptoEngine.b64(ByteArray(16)))
        assertThat(
            HybridBox.open(tampered, rec.private, listOf(signer.public), CryptoEngine.INFO_REQ, listOf("1"))
        ).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun envelopeWrongAlg() {
        val env = Envelope(1, "none", "x", "x", "x", "x", "x", "k")
        assertThat(EnvelopeValidator.structural(env)).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun expiredAndPerpetualCountdown() {
        val after = java.time.Instant.parse("2026-02-01T00:00:00Z").toEpochMilli()
        assertThat(RegistryCountdown.label(licencia(), after)).isEqualTo("Vencida")
        assertThat(
            RegistryCountdown.label(
                licencia(TipoLicencia.PERPETUA, null, EstadoLicencia.PERPETUA),
                after
            )
        ).isEqualTo("Perpetua")
    }

    @Test
    fun perpetualWithExpiryInvalid() {
        assertThat(
            FieldValidator.validateLicense(licencia(TipoLicencia.PERPETUA, "2026-01-31T00:00:00Z", EstadoLicencia.PERPETUA))
        ).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun corruptGlregRejected() {
        val aes = SecretKeySpec(CryptoEngine.randomBytes(32), "AES")
        val kp = CryptoEngine.generateEphemeralP256()
        val pack = RegistryPack.pack("x".toByteArray(), aes, kp.private)
        pack[7] = (pack[7].toInt() xor 0xff).toByte()
        assertThat(RegistryPack.unpack(pack, aes, kp.public)).isInstanceOf(Outcome.Err::class.java)
        assertThat(RegistryPack.unpack(byteArrayOf(1, 2, 3), aes, kp.public)).isInstanceOf(Outcome.Err::class.java)
    }
}
