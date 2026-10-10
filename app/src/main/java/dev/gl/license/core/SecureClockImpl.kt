package dev.gl.license.core

import dev.gl.license.domain.repository.SecureClock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureClockImpl @Inject constructor() : SecureClock {
    private val iso = DateTimeFormatter.ISO_INSTANT
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
    override fun nowIso(): String = Instant.ofEpochMilli(nowEpochMillis()).atOffset(ZoneOffset.UTC).format(iso)
}
