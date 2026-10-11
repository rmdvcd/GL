package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.Spvi23
import org.junit.Assert.assertThrows
import org.junit.Test

class Spvi23CodecTest {
    @Test
    fun b64urlSinRellenoRoundTrip() {
        val raw = byteArrayOf(0x03, 0xc3.toByte(), 0x6d.toByte(), 0xba.toByte())
        val s = Spvi23.b64urlE(raw)
        assertThat(s).doesNotContain("=")
        assertThat(s).doesNotContain("+")
        assertThat(s).doesNotContain("/")
        assertThat(Spvi23.b64urlD(s)).isEqualTo(raw)
    }

    @Test
    fun comprimirDescomprimirEsIdentidad() {
        val pub = CryptoEngine.generateEphemeralP256().public
        assertThat(Spvi23.descomprimirPunto(Spvi23.comprimirPunto(pub))).isEqualTo(pub)
    }

    @Test
    fun puntoInvalidoLanza() {
        assertThrows(IllegalArgumentException::class.java) {
            Spvi23.descomprimirPunto(ByteArray(33) { 0x04 })
        }
    }

    @Test
    fun puntoConCoordenadaFueraDelCampoLanza() {
        val p = "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff"
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            Spvi23.descomprimirPunto(byteArrayOf(0x02) + p)
        }
    }
}
