package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.usecase.RequestFreshness
import java.time.Instant
import org.junit.Test

class RequestFreshnessTest {
    private val now = Instant.parse("2026-01-08T00:00:00Z")

    @Test fun sieteDiasMenosUnSegundoSeAcepta() {
        assertThat(RequestFreshness.accepts(now.minusSeconds(7 * 86_400L - 1), now)).isTrue()
    }

    @Test fun sieteDiasExactosSeAceptanPorSerInclusivo() {
        assertThat(RequestFreshness.accepts(now.minusSeconds(7 * 86_400L), now)).isTrue()
    }

    @Test fun sieteDiasMasUnSegundoSeRechaza() {
        assertThat(RequestFreshness.accepts(now.minusSeconds(7 * 86_400L + 1), now)).isFalse()
    }

    @Test fun adelantoDeDosHorasSeAceptaYUnSegundoMasNo() {
        assertThat(RequestFreshness.accepts(now.plusSeconds(2 * 3600L), now)).isTrue()
        assertThat(RequestFreshness.accepts(now.plusSeconds(2 * 3600L + 1), now)).isFalse()
    }

    @Test fun extremosNoRepresentablesFallanCerrado() {
        assertThat(RequestFreshness.accepts(Instant.MAX, now)).isFalse()
        assertThat(RequestFreshness.accepts(Instant.MIN, now)).isFalse()
    }
}
