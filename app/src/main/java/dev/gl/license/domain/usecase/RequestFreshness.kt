package dev.gl.license.domain.usecase

import java.time.Duration
import java.time.Instant

/** Window shared by request validators: 72 hours old, at most 2 hours in the future. */
object RequestFreshness {
    val maxAge: Duration = Duration.ofHours(72)
    val maxFutureSkew: Duration = Duration.ofHours(2)

    fun accepts(requestedAt: Instant, now: Instant): Boolean = try {
        !requestedAt.isBefore(now.minus(maxAge)) &&
            !requestedAt.isAfter(now.plus(maxFutureSkew))
    } catch (_: Exception) {
        false
    }
}
