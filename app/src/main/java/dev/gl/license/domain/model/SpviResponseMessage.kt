package dev.gl.license.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object SpviResponseMessage {
    private val zona = ZoneId.of("America/Havana")
    private val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun build(
        tipo: TipoLicencia,
        secundarias: Int,
        emitidaSec: Long,
        venceSec: Long?,
        licenseId: String,
        codigo: String,
    ): String {
        val nombre = tipo.name.lowercase().replaceFirstChar { it.uppercase() }
        val emitida = formato.format(Instant.ofEpochSecond(emitidaSec).atZone(zona))
        val vence = if (venceSec == null) "nunca" else formato.format(Instant.ofEpochSecond(venceSec).atZone(zona))
        return "Licencia SPVI\n" +
            "Tipo: $nombre\n" +
            "Apps secundarias: $secundarias\n" +
            "Emitida: $emitida\n" +
            "Vence: $vence\n" +
            "ID: $licenseId\n" +
            "\n" +
            codigo
    }
}
