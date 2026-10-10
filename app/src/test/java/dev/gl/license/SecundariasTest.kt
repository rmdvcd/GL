package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.Secundarias
import org.junit.Test

class SecundariasTest {
    @Test
    fun soloSpviAplicaLasSecundarias() {
        assertThat(Secundarias.aplicaA("SPVI", "device-abc-12345")).isTrue()
        assertThat(Secundarias.aplicaA("MiApp", "SPVI:abc-12345")).isTrue()
        assertThat(Secundarias.aplicaA("MiApp", "device-abc-12345")).isFalse()
    }

    @Test
    fun rangoSpviAceptaAusenteLimitesYRechazaElResto() {
        assertThat(Secundarias.validar("SPVI", "device-abc-12345", null))
            .isInstanceOf(Outcome.Ok::class.java)
        assertThat(Secundarias.validar("SPVI", "device-abc-12345", 0))
            .isInstanceOf(Outcome.Ok::class.java)
        assertThat(Secundarias.validar("SPVI", "device-abc-12345", 10))
            .isInstanceOf(Outcome.Ok::class.java)

        val abajo = Secundarias.validar("SPVI", "device-abc-12345", -1)
        assertThat(abajo).isInstanceOf(Outcome.Err::class.java)
        assertThat((abajo as Outcome.Err).error).isEqualTo(AppError.InvalidSecundarias)
        assertThat(abajo.error.userMessage).isEqualTo("Secundarias debe ser un entero entre 0 y 10.")

        val arriba = Secundarias.validar("SPVI", "device-abc-12345", 11)
        assertThat(arriba).isInstanceOf(Outcome.Err::class.java)
        assertThat((arriba as Outcome.Err).error).isEqualTo(AppError.InvalidSecundarias)
    }

    @Test
    fun fueraDeSpviElValorSeIgnora() {
        assertThat(Secundarias.validar("MiApp", "device-abc-12345", 11))
            .isInstanceOf(Outcome.Ok::class.java)
        assertThat(Secundarias.efectivas("MiApp", "device-abc-12345", 11)).isNull()
        assertThat(Secundarias.efectivas("SPVI", "device-abc-12345", 2)).isEqualTo(2)
        assertThat(Secundarias.efectivas("SPVI", "device-abc-12345", null)).isNull()
    }
}
