package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.SolicitudSpviV2
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.Spvi23
import org.junit.Test

class Spvi23SolicitudTest {
    private fun dto() = SolicitudSpviV2(
        v = 2, nombre = "María", apellidos = "Pérez González", ci = "85010112345",
        via = ViaSolicitud.WHATSAPP, telefono = "+5352345678", deviceId = "SPVI:abc-12345",
        tipo = TipoLicencia.MENSUAL, solicitadaEn = "2026-10-03T20:00:00Z", nonce = "n1234567890123456",
        devicePub = Spvi23.b64urlE(Spvi23.comprimirPunto(CryptoEngine.generateEphemeralP256().public)),
        secundarias = 2, renueva = null,
    )

    @Test
    fun sellarAbrirRoundTrip() {
        val gl = CryptoEngine.generateEphemeralP256()
        val d = dto()
        val codigo = Spvi23.sellarSolicitud(d, gl.public)
        val abierto = Spvi23.abrir(codigo, gl.private)
        assertThat((abierto as Outcome.Ok).value).isEqualTo(d)
    }

    @Test
    fun mutarUnByteFalla() {
        val gl = CryptoEngine.generateEphemeralP256()
        val raw = Spvi23.b64urlD(Spvi23.sellarSolicitud(dto(), gl.public))
        for (pos in listOf(0, 33, raw.size - 1)) {
            val mut = raw.copyOf().also { it[pos] = (it[pos].toInt() xor 0x01).toByte() }
            assertThat(Spvi23.abrir(Spvi23.b64urlE(mut), gl.private)).isInstanceOf(Outcome.Err::class.java)
        }
    }

    @Test
    fun extraerIgnoraSaltosYParaEnPunto() {
        val r = Spvi23.extraerCodigo("Solicitud SPVI\nSPVIR1:AB\nCD-ef_gh.IJ")
        assertThat((r as Outcome.Ok).value).isEqualTo("ABCD-ef_gh")
    }

    @Test
    fun rechazaTextoDeSolicitudDemasiadoGrandeAntesDeExtraerCodigo() {
        val oversized = "SPVIR1:" + "A".repeat(Spvi23.MAX_REQUEST_TEXT_CHARS)

        assertThat(Spvi23.contieneCodigo(oversized)).isFalse()
        assertThat(Spvi23.extraerCodigo(oversized)).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun sellarAbrirRecuperacionYAmpliacion() {
        val gl = CryptoEngine.generateEphemeralP256()

        val rec = dto().copy(recupera = "lic-antigua-uuid")
        val codRec = Spvi23.sellarSolicitud(rec, gl.public)
        val resRec = Spvi23.abrir(codRec, gl.private)
        assertThat((resRec as Outcome.Ok).value).isEqualTo(rec)

        val amp = dto().copy(secundarias = 5, amplia = "lic-activa-uuid")
        val codAmp = Spvi23.sellarSolicitud(amp, gl.public)
        val resAmp = Spvi23.abrir(codAmp, gl.private)
        assertThat((resAmp as Outcome.Ok).value).isEqualTo(amp)
    }

    @Test
    fun rechazaCamposExcluyentesSimultaneos() {
        val gl = CryptoEngine.generateEphemeralP256()

        val conflicto = dto().copy(renueva = "lic-1", recupera = "lic-2")
        val codConflicto = Spvi23.sellarSolicitud(conflicto, gl.public)
        assertThat(Spvi23.abrir(codConflicto, gl.private)).isInstanceOf(Outcome.Err::class.java)

        val conflictoAmp = dto().copy(renueva = "lic-1", amplia = "lic-2")
        val codConflictoAmp = Spvi23.sellarSolicitud(conflictoAmp, gl.public)
        assertThat(Spvi23.abrir(codConflictoAmp, gl.private)).isInstanceOf(Outcome.Err::class.java)
    }
}
