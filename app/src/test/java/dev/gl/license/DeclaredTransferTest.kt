package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.SolicitudSpviV2
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.Spvi23
import java.security.KeyPair
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class DeclaredTransferTest {
    private fun dto(tx: String? = null) = SolicitudSpviV2(
        v = 2, nombre = "María", apellidos = "Pérez González", ci = "85010112345",
        via = ViaSolicitud.WHATSAPP, telefono = "+5352345678", deviceId = "SPVI:abc-12345",
        tipo = TipoLicencia.MENSUAL, solicitadaEn = "2026-10-03T20:00:00Z",
        nonce = "n1234567890123456",
        devicePub = Spvi23.b64urlE(Spvi23.comprimirPunto(CryptoEngine.generateEphemeralP256().public)),
        secundarias = 2, tx = tx,
    )

    @Test
    fun solicitudSinTxSigueAbriendo() {
        val gl = CryptoEngine.generateEphemeralP256()
        val abierta = Spvi23.abrir(Spvi23.sellarSolicitud(dto(), gl.public), gl.private) as Outcome.Ok
        assertThat(abierta.value.tx).isNull()
    }

    @Test
    fun solicitudConTxValidaSeConservaRecortada() {
        val gl = CryptoEngine.generateEphemeralP256()
        val abierta = Spvi23.abrir(Spvi23.sellarSolicitud(dto("  TX-123456  "), gl.public), gl.private) as Outcome.Ok
        assertThat(abierta.value.tx).isEqualTo("TX-123456")
    }

    @Test
    fun txInvalidaSeIgnoraSinRechazarLaSolicitud() {
        val gl = CryptoEngine.generateEphemeralP256()
        for (mala in listOf("ab", "TX 123", "TX@1234", "A".repeat(31), "ñ-1234")) {
            val abierta = Spvi23.abrir(Spvi23.sellarSolicitud(dto(mala), gl.public), gl.private)
            assertThat(abierta).isInstanceOf(Outcome.Ok::class.java)
            assertThat((abierta as Outcome.Ok).value.tx).isNull()
        }
    }

    /** Cifra un JSON v=2 crudo: simula clientes antiguos, campos futuros o JSON de prueba. */
    private fun sellarJsonCrudo(json: String, gl: KeyPair): String {
        val eph = CryptoEngine.generateEphemeralP256()
        val epk = Spvi23.comprimirPunto(eph.public)
        val ikm = CryptoEngine.ecdh(eph.private, gl.public)
        val okm = CryptoEngine.hkdfSha256(ikm, epk, "SPVI-R1".toByteArray(), 44)
        val aad = "SPVI-R1|".toByteArray() + epk
        val (_, ct, tag) = CryptoEngine.aesGcmEncrypt(
            SecretKeySpec(okm.copyOfRange(0, 32), "AES"), json.toByteArray(), aad, okm.copyOfRange(32, 44),
        )
        return Spvi23.b64urlE(epk + ct + tag)
    }

    private fun jsonSinTx() = buildJsonObject {
        put("v", 2); put("nombre", "María"); put("apellidos", "Pérez González"); put("ci", "85010112345")
        put("via", "WHATSAPP"); put("telefono", "+5352345678"); put("deviceId", "SPVI:abc-12345")
        put("tipo", "MENSUAL"); put("solicitadaEn", "2026-10-03T20:00:00Z")
        put("nonce", "n1234567890123456")
        put("devicePub", Spvi23.b64urlE(Spvi23.comprimirPunto(CryptoEngine.generateEphemeralP256().public)))
        put("secundarias", 2)
    }

    @Test
    fun clienteAntiguoSinTxYCampoDesconocidoSiguenAbriendo() {
        val gl = CryptoEngine.generateEphemeralP256()
        val sinTx = Spvi23.abrir(sellarJsonCrudo(jsonSinTx().toString(), gl), gl.private)
        assertThat((sinTx as Outcome.Ok).value.tx).isNull()

        val futuro = Json.parseToJsonElement(jsonSinTx().toString()) as JsonObject
        val conFuturo = JsonObject(futuro.toMutableMap().apply { put("campoFuturo", JsonPrimitive("x")) })
        val abierta = Spvi23.abrir(sellarJsonCrudo(conFuturo.toString(), gl), gl.private)
        assertThat((abierta as Outcome.Ok).value.tx).isNull()
    }

    @Test
    fun declaredTxAceptaLimitesDelFormato() {
        assertThat(Spvi23.declaredTx("ABCD")).isEqualTo("ABCD")
        assertThat(Spvi23.declaredTx("A".repeat(30))).isEqualTo("A".repeat(30))
        assertThat(Spvi23.declaredTx("ABC")).isNull()
        assertThat(Spvi23.declaredTx("A".repeat(31))).isNull()
        assertThat(Spvi23.declaredTx(null)).isNull()
        assertThat(Spvi23.declaredTx("   ")).isNull()
    }
}
