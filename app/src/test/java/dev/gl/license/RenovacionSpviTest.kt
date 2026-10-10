package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.SolicitudSpviV2
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.domain.usecase.EmitirSpvi2UseCase
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.Spvi23
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RenovacionSpviTest {
    private val ahoraSec = 1_735_689_600L // FixedClock: 2026-01-01T00:00:00Z

    private val dispositivo = CryptoEngine.generateEphemeralP256()
    private val firma = CryptoEngine.generateEphemeralP256()
    private val deviceId = "SPVI:abc-12345"
    private val devicePub33 = Spvi23.comprimirPunto(dispositivo.public)

    private val puerta = object : dev.gl.license.domain.repository.SpviPuerta {
        override fun abrirSolicitud(raw: String) = Outcome.Err(AppError.InvalidPayload)
        override fun emitirCodigo(lic: Licencia, devicePub33: ByteArray): Outcome<String> {
            val emitida = Instant.parse(lic.issuedAtIso).epochSecond
            val vence = lic.expiresAtIso?.let { Instant.parse(it).epochSecond }
            val body = Spvi23.cuerpo(
                java.util.UUID.fromString(lic.id),
                Spvi23.huella(lic.deviceId, devicePub33),
                lic.type,
                lic.status,
                lic.secundarias ?: 0,
                emitida,
                vence,
            )
            val sellado = Spvi23.sellarLicencia(body, dispositivo.public, firma.private)
            return Outcome.Ok(sellado.codigo())
        }
    }

    private fun sol(renueva: String? = null) = SolicitudSpviV2(
        v = 2,
        nombre = "Maria",
        apellidos = "Perez Gonzalez",
        ci = "85010112345",
        via = ViaSolicitud.WHATSAPP,
        telefono = "+5352345678",
        deviceId = deviceId,
        tipo = TipoLicencia.MENSUAL,
        solicitadaEn = "2026-01-01T00:00:00Z",
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
        devicePub = Spvi23.b64urlE(devicePub33),
        secundarias = 2,
        renueva = renueva,
    )

    private fun anterior(
        id: String,
        deviceId: String = this.deviceId,
        status: EstadoLicencia = EstadoLicencia.ACTIVA,
        venceSec: Long,
    ) = Licencia(
        version = 2,
        id = id,
        firstName = "Maria",
        lastName = "Perez Gonzalez",
        nationalId = "85010112345",
        channel = ViaSolicitud.WHATSAPP,
        phone = "+5352345678",
        deviceId = deviceId,
        appName = "SPVI",
        type = TipoLicencia.MENSUAL,
        requestedAtIso = "2025-12-01T00:00:00Z",
        issuedAtIso = Instant.ofEpochSecond(venceSec - 30 * 86_400L).toString(),
        expiresAtIso = Instant.ofEpochSecond(venceSec).toString(),
        status = status,
        secundarias = 2,
        precioCobrado = 8_000,
        codigoCorto = "SPVI2:previa",
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
    )

    private suspend fun emitir(solicitud: SolicitudSpviV2, licencias: MemLic = MemLic()) =
        EmitirSpvi2UseCase(puerta, licencias, FixedClock)(
            solicitud,
            pagoConfirmado = true,
            idFactory = { "7c9e6679-7425-40de-944b-e07fc1f90ae7" },
        )

    @Test
    fun anteriorVigenteSumaDuracionSobreSuVencimiento() = runTest {
        val licencias = MemLic()
        licencias.upsert(anterior("previa", venceSec = ahoraSec + 5 * 86_400L))

        val r = emitir(sol(renueva = "previa"), licencias) as Outcome.Ok

        assertThat(r.value.renuevaIgnorada).isFalse()
        assertThat(r.value.licencia.expiresAtIso)
            .isEqualTo(Instant.ofEpochSecond(ahoraSec + 35 * 86_400L).toString())
    }

    @Test
    fun anteriorVencidaSumaDuracionDesdeAhora() = runTest {
        val licencias = MemLic()
        licencias.upsert(anterior("previa", venceSec = ahoraSec - 3 * 86_400L))

        val r = emitir(sol(renueva = "previa"), licencias) as Outcome.Ok

        assertThat(r.value.renuevaIgnorada).isFalse()
        assertThat(r.value.licencia.expiresAtIso)
            .isEqualTo(Instant.ofEpochSecond(ahoraSec + 30 * 86_400L).toString())
    }

    @Test
    fun renuevaDeOtroDeviceSeIgnoraYAvisa() = runTest {
        val licencias = MemLic()
        licencias.upsert(anterior("ajena", deviceId = "SPVI:otro-99999", venceSec = ahoraSec + 5 * 86_400L))

        val r = emitir(sol(renueva = "ajena"), licencias) as Outcome.Ok

        assertThat(r.value.renuevaIgnorada).isTrue()
        assertThat(r.value.licencia.expiresAtIso)
            .isEqualTo(Instant.ofEpochSecond(ahoraSec + 30 * 86_400L).toString())
    }

    @Test
    fun renuevaRevocadaSeIgnoraYAvisa() = runTest {
        val licencias = MemLic()
        licencias.upsert(anterior("previa", status = EstadoLicencia.REVOCADA, venceSec = ahoraSec + 5 * 86_400L))

        val r = emitir(sol(renueva = "previa"), licencias) as Outcome.Ok

        assertThat(r.value.renuevaIgnorada).isTrue()
        assertThat(r.value.licencia.expiresAtIso)
            .isEqualTo(Instant.ofEpochSecond(ahoraSec + 30 * 86_400L).toString())
    }
}
