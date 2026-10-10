package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.model.SpviResponseMessage
import dev.gl.license.domain.model.TipoLicencia
import org.junit.Test

class SpviMensajeTest {
    private val codigo =
        "SPVI2:A8Ntuq3AH5-S0FVM2Itwox5ElM8JLkdH1v90_SJsF6KzCtzdFYKcGWYWoXp1RC0191zsFibEJjMpJmCWwJWjkLnY7KDTfPDFw4lrwTr5VFo_db5f84NP9QfStCUSFOSb9HjaQkeWuTWUZGftUUhs0d0y5eIav9Jn59WIyb9WHNln6Ui7Y_Ce6wOVPP9Do3JcgQ-0a--x8-TLCh1g0A"

    @Test
    fun mensajeDoradoExacto() {
        val esperado =
            "Licencia SPVI\n" +
                "Tipo: Mensual\n" +
                "Apps secundarias: 2\n" +
                "Emitida: 03/10/2026\n" +
                "Vence: 02/11/2026\n" +
                "ID: 7c9e6679-7425-40de-944b-e07fc1f90ae7\n" +
                "\n" +
                codigo
        assertThat(
            SpviResponseMessage.build(
                TipoLicencia.MENSUAL,
                2,
                1791057600L,
                1793649600L,
                "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                codigo,
            ),
        ).isEqualTo(esperado)
    }

    @Test
    fun perpetuaDiceVenceNunca() {
        val msg = SpviResponseMessage.build(
            TipoLicencia.PERPETUA,
            0,
            1791057600L,
            null,
            "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            codigo,
        )
        assertThat(msg).contains("Tipo: Perpetua\n")
        assertThat(msg).contains("Vence: nunca\n")
    }

    @Test
    fun codigoMide210Caracteres() {
        assertThat(codigo.removePrefix("SPVI2:").length).isEqualTo(210)
    }
}
