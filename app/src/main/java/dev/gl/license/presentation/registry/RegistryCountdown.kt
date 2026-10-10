package dev.gl.license.presentation.registry

import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.TipoLicencia
import java.time.Duration
import java.time.Instant

object RegistryCountdown {
    fun label(item: Licencia, nowMillis: Long): String {
        if (item.type == TipoLicencia.PERPETUA || item.status == EstadoLicencia.PERPETUA) {
            return "Perpetua"
        }
        val exp = item.expiresAtIso ?: return "—"
        return try {
            val end = Instant.parse(exp).toEpochMilli()
            val d = Duration.ofMillis(end - nowMillis)
            if (d.isNegative || d.isZero) "Vencida"
            else {
                val days = d.toDays()
                val h = d.minusDays(days).toHours()
                val m = d.minusDays(days).minusHours(h).toMinutes()
                val s = d.minusDays(days).minusHours(h).minusMinutes(m).seconds
                "%dd %02d:%02d:%02d".format(days, h, m, s)
            }
        } catch (_: Exception) {
            "—"
        }
    }
}
