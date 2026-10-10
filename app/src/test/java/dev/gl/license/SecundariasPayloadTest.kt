package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.SolicitudLicencia
import kotlinx.serialization.json.Json
import org.junit.Test

class SecundariasPayloadTest {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    private fun solicitud(secundarias: String) = """{
        "v": 1,
        "nombre": "Ana",
        "apellidos": "Perez",
        "ci": "85010112345",
        "via": "WHATSAPP",
        "telefono": "+5355512345",
        "deviceId": "SPVI:abc-12345",
        "appName": "SPVI",
        "tipo": "MENSUAL",
        "solicitadaEn": "2026-01-01T00:00:00Z",
        "nonce": "AAAAAAAAAAAAAAAAAAAAAA==",
        "secundarias": $secundarias
    }""".trimIndent()

    @Test
    fun solicitudSpviAceptaDosSecundarias() {
        val req = json.decodeFromString(SolicitudLicencia.serializer(), solicitud("2"))

        assertThat(req.secundarias).isEqualTo(2)
    }

    @Test
    fun solicitudSinSecundariasOmiteLaClave() {
        val req = json.decodeFromString(
            SolicitudLicencia.serializer(),
            solicitud("2").replace("\"secundarias\": 2", "\"secundarias\": null"),
        )

        assertThat(json.encodeToString(SolicitudLicencia.serializer(), req))
            .doesNotContain("\"secundarias\"")
    }

    @Test
    fun licenciaSpviAceptaDosSecundarias() {
        val payload = """{
            "v": 1,
            "id": "lic-123",
            "nombre": "Ana",
            "apellidos": "Perez",
            "ci": "85010112345",
            "via": "WHATSAPP",
            "telefono": "+5355512345",
            "deviceId": "SPVI:abc-12345",
            "appName": "SPVI",
            "tipo": "MENSUAL",
            "solicitadaEn": "2026-01-01T00:00:00Z",
            "emitidaEn": "2026-01-02T00:00:00Z",
            "venceEn": "2026-02-01T00:00:00Z",
            "estado": "ACTIVA",
            "secundarias": 2,
            "nonce": "AAAAAAAAAAAAAAAAAAAAAA=="
        }""".trimIndent()
        val licencia = json.decodeFromString(Licencia.serializer(), payload)

        assertThat(licencia.secundarias).isEqualTo(2)
    }
}
