package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.Envelope
import dev.gl.license.domain.model.LicenseRequest
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.domain.usecase.FieldValidator
import dev.gl.license.security.ClientRequestHelper
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.HybridBox
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.Instant

class ClientRequestHelperTest {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun helperEnvelopeRoundTripsWithThePublishedIvAndRequiredDeviceKey() {
        val glEcdh = CryptoEngine.generateEphemeralP256()
        val clientDevice = CryptoEngine.generateEphemeralP256()
        val now = Instant.parse("2026-10-14T12:00:00Z")
        val request = LicenseRequest(
            version = CONTRACT_VERSION,
            deviceId = "client-device-123",
            appName = "MiApp",
            firstName = "Ana",
            lastName = "Perez",
            nationalId = "85010112345",
            channel = ViaSolicitud.WHATSAPP,
            phone = "+5355512345",
            type = TipoLicencia.MENSUAL,
            requestedAtIso = now.toString(),
            nonce = CryptoEngine.b64(CryptoEngine.randomBytes(16)),
            devicePublicKeyPem = CryptoEngine.b64(clientDevice.public.encoded),
        )

        val raw = ClientRequestHelper.buildEnvelope(request, glEcdh.public, json)
        val envelope = json.decodeFromString(Envelope.serializer(), raw)
        val opened = HybridBox.open(
            env = envelope,
            staticPrivate = glEcdh.private,
            verifyKeys = emptyList(),
            info = CryptoEngine.INFO_REQ,
            aadParts = listOf(
                envelope.version.toString(),
                envelope.alg,
                envelope.ephemeralPublicKey,
                envelope.keyId,
            ),
        )

        assertThat(opened).isInstanceOf(Outcome.Ok::class.java)
        val plaintext = (opened as Outcome.Ok).value.decodeToString()
        val decoded = json.decodeFromString(LicenseRequest.serializer(), plaintext)
        assertThat(decoded.devicePublicKeyPem).isEqualTo(request.devicePublicKeyPem)
        assertThat(FieldValidator.validateRequest(decoded, now)).isInstanceOf(Outcome.Ok::class.java)
    }
}
