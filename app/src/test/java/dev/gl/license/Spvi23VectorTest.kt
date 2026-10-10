package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.Spvi23
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.UUID
import org.bouncycastle.jce.ECNamedCurveTable
import org.junit.Test

class Spvi23VectorTest {
    private val deviceId = "SPVI:golden0000000001"
    private val devicePubB64 = "AiuZGHe7sUgbFLfqufeF1BMCvKuYZQuJKYSxnIoc0zMK"
    private val licenseId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val emitidaSec = Instant.parse("2026-10-03T20:00:00Z").epochSecond
    private val venceSec = Instant.parse("2026-11-02T20:00:00Z").epochSecond
    private val huellaHex = "fb445359b06179d282c7970ea4adab32"
    private val cuerpoHex =
        "027c9e6679742540de944be07fc1f90ae7fb445359b06179d282c7970ea4adab320000026ac15ec06ae8ebc0"
    private val efimeraHex = "0a0b0c0d0e0f10111213141516171819a0b0c0d0e0f1f2f3f4f5f6f7f8f9fafb"
    private val epkHex = "03c36dbaadc01f9f92d0554cd88b70a31e4494cf092e4747d6ff74fd226c17a2b3"
    private val ctTagHex =
        "0adcdd15829c196616a17a75442d35f75cec1626c4263329266096c095a390b9d8eca0d37cf0c5c3896bc13af9545a3f75be5ff3834ff507d2b42512"
    private val firmaSpkiB64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEvXxzuIsum0ztpiAistqL4TGTpbVu3Cbn33hC4kzQtesGBa2nvag6xqK4DX4xQED6R/8WuDushc7bAURRu3znGg=="
    private val firmaDHex = "1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c5b6a7988"

    private fun hex(s: String): ByteArray {
        val b = ByteArray(s.length / 2)
        for (i in b.indices) b[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return b
    }

    private fun hexOf(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun parConEscalar(dHex: String): KeyPair {
        val params = (CryptoEngine.generateEphemeralP256().public as ECPublicKey).params
        val bc = ECNamedCurveTable.getParameterSpec("secp256r1")
        val d = BigInteger(dHex, 16)
        val q = bc.g.multiply(d).normalize()
        val kf = KeyFactory.getInstance("EC")
        val priv = kf.generatePrivate(ECPrivateKeySpec(d, params))
        val pub = kf.generatePublic(
            ECPublicKeySpec(
                java.security.spec.ECPoint(
                    q.affineXCoord.toBigInteger(),
                    q.affineYCoord.toBigInteger(),
                ),
                params,
            ),
        )
        return KeyPair(pub, priv)
    }

    @Test
    fun huellaDorado() {
        val huella = Spvi23.huella(deviceId, Spvi23.b64urlD(devicePubB64))
        assertThat(hexOf(huella)).isEqualTo(huellaHex)
    }

    @Test
    fun cuerpoDorado() {
        val cuerpo = Spvi23.cuerpo(
            UUID.fromString(licenseId),
            hex(huellaHex),
            TipoLicencia.MENSUAL,
            EstadoLicencia.ACTIVA,
            2,
            emitidaSec,
            venceSec,
        )
        assertThat(cuerpo.size).isEqualTo(44)
        assertThat(hexOf(cuerpo)).isEqualTo(cuerpoHex)
    }

    @Test
    fun selladoConEfimeraFijaEsByteAByte() {
        val devicePub = Spvi23.descomprimirPunto(Spvi23.b64urlD(devicePubB64))
        val efimera = parConEscalar(efimeraHex)
        val firma = parConEscalar(firmaDHex)
        val sellado = Spvi23.sellarLicencia(
            hex(cuerpoHex),
            devicePub,
            firma.private,
            efimera,
        )
        assertThat(hexOf(sellado.epk)).isEqualTo(epkHex)
        assertThat(hexOf(sellado.ct + sellado.tag)).isEqualTo(ctTagHex)
        val firmaPub = CryptoEngine.parseEcPublic(firmaSpkiB64)
        assertThat(Spvi23.verificar(sellado.codigo(), firmaPub)).isTrue()
        assertThat(sellado.codigo().removePrefix("SPVI2:").length).isEqualTo(210)
    }

    @Test
    fun derToRawRoundtripVerificaEnP1363() {
        val par = parConEscalar(firmaDHex)
        val msg = "mensaje-spvi".toByteArray()
        val der = CryptoEngine.sign(par.private, msg)
        val raw = Spvi23.derToRaw(der)
        assertThat(raw.size).isEqualTo(64)
        val v = Signature.getInstance("SHA256withECDSAinP1363Format")
        v.initVerify(par.public)
        v.update(msg)
        assertThat(v.verify(raw)).isTrue()
    }
}
