package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.model.LicenseMessage
import dev.gl.license.domain.model.TipoLicencia
import org.junit.Test

class LicenseMessageTest {
    @Test
    fun mensajeConSecundariasUsaTresLineasConIdFueraDelJson() {
        val mensaje = LicenseMessage.build(
            appName = "SPVI",
            tipo = TipoLicencia.MENSUAL,
            secundarias = 2,
            total = 8_000,
            licenseId = "uuid",
            envelope = "{}",
        )

        assertThat(mensaje).isEqualTo(
            "Licencia SPVI MENSUAL · 2 secundarias · 8 000 CUP\nID: uuid\n{}",
        )
    }

    @Test
    fun mensajeSinSecundariasIndicaQueLaAppEsAnterior() {
        val mensaje = LicenseMessage.build(
            appName = "MiApp",
            tipo = TipoLicencia.MENSUAL,
            secundarias = null,
            total = 6_000,
            licenseId = "uuid",
            envelope = "{}",
        )

        assertThat(mensaje).isEqualTo(
            "Licencia MiApp MENSUAL · 6 000 CUP\nID: uuid\n{}",
        )
    }
}
