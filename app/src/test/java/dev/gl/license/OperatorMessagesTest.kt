package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.model.*
import org.junit.Test

class OperatorMessagesTest {
    // Oráculo independiente de la validación usada por el formateador.
    private val alphabet = (('A'..'Z') + ('a'..'z') + ('0'..'9') +
        "ñÑéèùìòÇäöüÄÖÜ \n!\"#%&'()*+,-./:;<=>?@_\$".toList()).toSet()

    @Test fun mensajesDelEmisor() {
        for (tipo in TipoLicencia.values()) {
            val code = "A".repeat(16) // dato sintético, no canjeable
            val canje = RedeemCodeMessage.build(RedeemBatchEntry(code, tipo))
            assertThat(canje).contains("AAAA-AAAA-AAAA-AAAA")
            assertThat(canje.length).isAtMost(160)
            val spvi = SpviResponseMessage.build(tipo, 10, 1234L, null, "id", "SPVI2:Abc-12_X")
            val envelope = "{\"v\":1}" // El contrato v1 requiere JSON literal.
            val legacy = LicenseMessage.build("Aplicación", tipo, 0, 100, "id", envelope, "1234")
            assertThat(legacy).endsWith(envelope)
            for (text in listOf(canje, spvi, legacy.removeSuffix(envelope))) {
                assertThat(text.all { it in alphabet }).isTrue()
            }
        }
    }
}
