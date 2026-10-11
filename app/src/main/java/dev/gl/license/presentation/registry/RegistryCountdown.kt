package dev.gl.license.presentation.registry

import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.TipoLicencia
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object RegistryCountdown {
    private val displayZone = ZoneId.of("America/Havana")
    private val displayFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy · HH:mm").withZone(displayZone)

    /** Fecha legible para la lista y el detalle; el dato original sigue en Room en ISO-8601. */
    fun dateLabel(iso: String): String = try {
        displayFormat.format(Instant.parse(iso))
    } catch (_: Exception) {
        iso
    }

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
