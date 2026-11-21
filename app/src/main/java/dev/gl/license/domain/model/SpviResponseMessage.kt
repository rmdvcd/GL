package dev.gl.license.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object SpviResponseMessage {
    private val zona = ZoneId.of("America/Havana")
    private val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** El registro v1 no conserva su sobre cifrado; su código visible no lo sustituye. */
    fun fromRecord(lic: Licencia): String? {
        val code = lic.codigoCorto ?: return null
        if (lic.version != 2 || lic.status == EstadoLicencia.REVOCADA || !code.startsWith("SPVI2:")) return null
        return try {
            build(lic.type, lic.secundarias ?: 0, Instant.parse(lic.issuedAtIso).epochSecond,
                lic.expiresAtIso?.let { Instant.parse(it).epochSecond }, lic.id, code)
        } catch (_: java.time.DateTimeException) { null }
    }

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
        return "Licencia Shalev\n" +
            "Tipo: $nombre\n" +
            "Apps secundarias: $secundarias\n" +
            "Emitida: $emitida\n" +
            "Vence: $vence\n" +
            "ID: $licenseId\n" +
            "\n" +
            codigo
    }
}
