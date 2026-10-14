package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.security.RegistryArchive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Test

class RegistryArchiveTest {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }

    private fun license(price: Int? = 8_000) = Licencia(
        version = 1,
        id = "archive-license-1",
        deviceId = "device-abc-12345",
        appName = "MiApp",
        firstName = "Ana",
        lastName = "Perez",
        nationalId = "85010112345",
        channel = ViaSolicitud.WHATSAPP,
        phone = "+5355512345",
        type = TipoLicencia.MENSUAL,
        requestedAtIso = "2026-01-01T00:00:00Z",
        issuedAtIso = "2026-01-01T00:00:01Z",
        expiresAtIso = "2026-01-31T00:00:01Z",
        status = EstadoLicencia.ACTIVA,
        precioCobrado = price,
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
    )

    @Test
    fun backupPreservesHistoricalChargedPriceWithoutChangingLicensePayload() {
        val original = license(price = 12_345)

        val decoded = RegistryArchive.decode(RegistryArchive.encode(listOf(original), json), json)

        assertThat(decoded).hasSize(1)
        assertThat(decoded.single().precioCobrado).isEqualTo(12_345)
        assertThat(decoded.single().id).isEqualTo(original.id)
    }

    @Test
    fun oldDirectLicenseArrayRemainsImportable() {
        val original = license(price = null)
        val oldPayload = json.encodeToString(ListSerializer(Licencia.serializer()), listOf(original))
            .toByteArray(Charsets.UTF_8)

        val decoded = RegistryArchive.decode(oldPayload, json)

        assertThat(decoded).containsExactly(original)
        assertThat(decoded.single().precioCobrado).isNull()
    }

    @Test
    fun emptyArchiveRoundTrips() {
        val decoded = RegistryArchive.decode(RegistryArchive.encode(emptyList(), json), json)
        assertThat(decoded).isEmpty()
    }

    @Test
    fun rejectsMoreThanTheArchiveEntryLimitBeforeDeserialization() {
        val tooMany = ("[" + List(RegistryArchive.MAX_ENTRIES + 1) { "{}" }.joinToString(",") + "]")
            .toByteArray(Charsets.UTF_8)
        var rejected = false
        try {
            RegistryArchive.decode(tooMany, json)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertThat(rejected).isTrue()
    }
}

