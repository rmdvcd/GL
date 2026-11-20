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
    private val ahoraSec = 1_767_225_600L // FixedClock: 2026-01-01T00:00:00Z

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

    private suspend fun emitir(
        solicitud: SolicitudSpviV2,
        licencias: MemLic = MemLic(),
        txNumber: String? = null,
    ) = EmitirSpvi2UseCase(puerta, licencias, FixedClock)(
        solicitud,
        pagoConfirmado = true,
        txNumber = txNumber,
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

    @Test
    fun rechazaSolicitudSpviCaducadaOFutura() = runTest {
        val caducada = emitir(sol().copy(solicitadaEn = "2025-12-28T00:00:00Z")) as Outcome.Err
        val futura = emitir(sol().copy(solicitadaEn = "2026-01-01T03:00:01Z")) as Outcome.Err

        assertThat(caducada.error).isEqualTo(AppError.RequestExpired)
        assertThat(futura.error).isEqualTo(AppError.RequestExpired)
    }

    @Test
    fun rechazaNumeroDeTransaccionYaUsadoSinDistinguirMayusculas() = runTest {
        val licencias = MemLic()
        licencias.upsert(anterior("previa", venceSec = ahoraSec + 5 * 86_400L).copy(txNumber = "TX-USED"))

        val result = emitir(sol(), licencias, txNumber = "tx-used") as Outcome.Err

        assertThat(result.error).isEqualTo(AppError.TransactionAlreadyUsed("tx-used"))
    }

    @Test
    fun conservaTransaccionEnLicenciaSpvi() = runTest {
        val result = emitir(sol(), MemLic(), txNumber = "TX-SPVI") as Outcome.Ok

        assertThat(result.value.licencia.txNumber).isEqualTo("TX-SPVI")
    }

    @Test
    fun recuperacionExitosaRevocaAnteriorYConservaVencimiento() = runTest {
        val licencias = MemLic()
        val previaVence = ahoraSec + 5 * 86_400L
        licencias.upsert(anterior("previa-uuid", venceSec = previaVence))

        val solRecupera = sol().copy(
            deviceId = "SPVI:nuevo-device-999",
            renueva = null,
            recupera = "previa-uuid",
        )

        val r = emitir(solRecupera, licencias) as Outcome.Ok
        val nueva = r.value.licencia

        // La anterior queda revocada en el registro
        val previaActualizada = licencias.getById("previa-uuid")
        assertThat(previaActualizada?.status).isEqualTo(EstadoLicencia.REVOCADA)

        // La nueva pertenece al nuevo dispositivo, conserva vencimiento, tipo y secundarias, y es gratuita
        assertThat(nueva.deviceId).isEqualTo("SPVI:nuevo-device-999")
        assertThat(nueva.expiresAtIso).isEqualTo(Instant.ofEpochSecond(previaVence).toString())
        assertThat(nueva.type).isEqualTo(TipoLicencia.MENSUAL)
        assertThat(nueva.secundarias).isEqualTo(2)
        assertThat(nueva.precioCobrado).isEqualTo(0)
    }

    @Test
    fun recuperacionRechazaAnteriorInexistente() = runTest {
        val licencias = MemLic()
        val solInexistente = sol().copy(recupera = "no-existe-uuid")

        val r = emitir(solInexistente, licencias) as Outcome.Err
        assertThat(r.error).isEqualTo(AppError.RecuperacionInvalida)
    }

    @Test
    fun recuperacionRechazaAnteriorRevocada() = runTest {
        val licencias = MemLic()
        licencias.upsert(anterior("previa-revocada", status = EstadoLicencia.REVOCADA, venceSec = ahoraSec + 5 * 86_400L))

        val solRecupera = sol().copy(recupera = "previa-revocada")
        val r = emitir(solRecupera, licencias) as Outcome.Err
        assertThat(r.error).isEqualTo(AppError.RecuperacionInvalida)
    }

    @Test
    fun recuperacionRechazaAnteriorVencida() = runTest {
        val licencias = MemLic()
        licencias.upsert(anterior("previa-vencida", venceSec = ahoraSec - 2 * 86_400L))

        val solRecupera = sol().copy(recupera = "previa-vencida")
        val r = emitir(solRecupera, licencias) as Outcome.Err
        assertThat(r.error).isEqualTo(AppError.RecuperacionInvalida)
    }

    @Test
    fun recuperacionRechazaCiDiferente() = runTest {
        val licencias = MemLic()
        licencias.upsert(anterior("previa-ci", venceSec = ahoraSec + 5 * 86_400L))

        val solAjena = sol().copy(ci = "00112233445", recupera = "previa-ci")
        val r = emitir(solAjena, licencias) as Outcome.Err
        assertThat(r.error).isEqualTo(AppError.RecuperacionInvalida)
    }

    @Test
    fun ampliacionExitosaConservaVencimientoYCobraSoloAdicionales() = runTest {
        val licencias = MemLic()
        val previaVence = ahoraSec + 5 * 86_400L
        licencias.upsert(anterior("previa-ampliable", venceSec = previaVence))

        val solAmplia = sol().copy(
            secundarias = 5,
            amplia = "previa-ampliable",
        )

        val r = emitir(solAmplia, licencias, txNumber = "TX-AMP") as Outcome.Ok
        val nueva = r.value.licencia

        assertThat(nueva.secundarias).isEqualTo(5)
        assertThat(nueva.expiresAtIso).isEqualTo(Instant.ofEpochSecond(previaVence).toString())
        assertThat(nueva.precioCobrado).isEqualTo(3_000) // (5 - 2) * 1_000
    }

    @Test
    fun ampliacionRechazaCondicionesInvalidas() = runTest {
        val licencias = MemLic()
        licencias.upsert(anterior("vencida", venceSec = ahoraSec - 2 * 86_400L))
        licencias.upsert(anterior("ajena", deviceId = "SPVI:otro-disp", venceSec = ahoraSec + 5 * 86_400L))
        licencias.upsert(anterior("vigente", venceSec = ahoraSec + 5 * 86_400L))

        val rVencida = emitir(sol().copy(secundarias = 4, amplia = "vencida"), licencias) as Outcome.Err
        val rAjena = emitir(sol().copy(secundarias = 4, amplia = "ajena"), licencias) as Outcome.Err
        val rIgual = emitir(sol().copy(secundarias = 2, amplia = "vigente"), licencias) as Outcome.Err

        assertThat(rVencida.error).isEqualTo(AppError.AmpliacionInvalida)
        assertThat(rAjena.error).isEqualTo(AppError.AmpliacionInvalida)
        assertThat(rIgual.error).isEqualTo(AppError.AmpliacionInvalida)
    }
}
