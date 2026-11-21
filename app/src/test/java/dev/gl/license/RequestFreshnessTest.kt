package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.usecase.RequestFreshness
import java.time.Instant
import org.junit.Test

class RequestFreshnessTest {
    @Test fun ventanaInclusivaYSinRedondear() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val earliest = now.minusSeconds(72L * 3600)
        val latest = now.plusSeconds(2L * 3600)
        assertThat(RequestFreshness.accepts(earliest.minusNanos(1), now)).isFalse()
        assertThat(RequestFreshness.accepts(earliest, now)).isTrue()
        assertThat(RequestFreshness.accepts(now, now)).isTrue()
        assertThat(RequestFreshness.accepts(latest, now)).isTrue()
        assertThat(RequestFreshness.accepts(latest.plusNanos(1), now)).isFalse()
    }
}
