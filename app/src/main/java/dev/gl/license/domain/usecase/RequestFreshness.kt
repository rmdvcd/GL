package dev.gl.license.domain.usecase

import java.time.Duration
import java.time.Instant

object RequestFreshness {
    /** Desfase solicitud − emisor, inclusivo. Misma política para envelope v1 y SPVIR1. */
    val window = Duration.ofHours(-72)..Duration.ofHours(2)

    fun accepts(requestedAt: Instant, now: Instant): Boolean =
        Duration.between(now, requestedAt) in window
}
