package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.model.PrecioTabla
import dev.gl.license.domain.model.TipoLicencia
import org.junit.Test

class PrecioTablaTest {
    @Test
    fun mensualConDosSecundariasSumaLaBaseMasElExtra() {
        assertThat(PrecioTabla.total(TipoLicencia.MENSUAL, 2)).isEqualTo(8_000)
    }

    @Test
    fun semestralConDosSecundariasSumaLaBaseMasElExtra() {
        assertThat(PrecioTabla.total(TipoLicencia.SEMESTRAL, 2)).isEqualTo(40_000)
    }

    @Test
    fun anualConDiezSecundariasSumaLaBaseMasElExtra() {
        assertThat(PrecioTabla.total(TipoLicencia.ANUAL, 10)).isEqualTo(140_000)
    }

    @Test
    fun perpetuaSinSecundariasCobraSoloLaBase() {
        assertThat(PrecioTabla.total(TipoLicencia.PERPETUA, 0)).isEqualTo(90_000)
    }

    @Test
    fun desgloseMuestraBaseExtraYTotalParaCobrar() {
        assertThat(PrecioTabla.desglose(TipoLicencia.MENSUAL, 2))
            .isEqualTo("Mensual 6 000 + 2 secundarias × 1 000 = 8 000 CUP")
    }

    @Test
    fun sinSecundariasIndicaAppAnteriorYLaBaseParaCobrar() {
        assertThat(PrecioTabla.desglose(TipoLicencia.MENSUAL, null))
            .isEqualTo("Secundarias: no indicado (app anterior) · 6 000 CUP")
    }

    @Test
    fun formatoPrecioAgrupaMilesAntesDeCUP() {
        assertThat(PrecioTabla.formatoPrecio(8_000)).isEqualTo("8 000 CUP")
    }
}
