package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.SolicitudSpviV2
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.Spvi23
import org.junit.Test

class ExtraccionSpviTest {
    private fun dto() = SolicitudSpviV2(
        v = 2, nombre = "María", apellidos = "Pérez González", ci = "85010112345",
        via = ViaSolicitud.WHATSAPP, telefono = "+5352345678", deviceId = "SPVI:abc-12345",
        tipo = TipoLicencia.MENSUAL, solicitadaEn = "2026-10-03T20:00:00Z", nonce = "n1234567890123456",
        devicePub = Spvi23.b64urlE(Spvi23.comprimirPunto(CryptoEngine.generateEphemeralP256().public)),
        secundarias = 2, renueva = null,
    )

    private val gl = CryptoEngine.generateEphemeralP256()

    @Test
    fun codigoPartidoEnLineasAbreIgualQueEnUnaLinea() {
        val d = dto()
        val codigo = "SPVIR1:" + Spvi23.sellarSolicitud(d, gl.public)
        val enUnaLinea = "Solicitud de licencia SPVI\nNombre: María\n\n$codigo\n"
        val partido = "Solicitud de licencia SPVI\nNombre: María\n\n" +
            codigo.chunked(64).joinToString("\n ") + "\n"

        val a = Spvi23.abrir((Spvi23.extraerCodigo(enUnaLinea) as Outcome.Ok).value, gl.private)
        val b = Spvi23.abrir((Spvi23.extraerCodigo(partido) as Outcome.Ok).value, gl.private)

        assertThat((a as Outcome.Ok).value.deviceId).isEqualTo("SPVI:abc-12345")
        assertThat((b as Outcome.Ok).value.deviceId).isEqualTo("SPVI:abc-12345")
    }

    @Test
    fun textoSinMarcadorNoEsSolicitudSpvi() {
        val r = Spvi23.extraerCodigo("Hola mundo")
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isEqualTo(AppError.SpviNoEs023)
    }

    @Test
    fun marcadorSinCodigoNoEsSolicitudSpvi() {
        val r = Spvi23.extraerCodigo("SPVIR1:")
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isEqualTo(AppError.SpviNoEs023)
    }
}
