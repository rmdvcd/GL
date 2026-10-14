package dev.gl.license.domain.model

object LicenseMessage {
    fun build(
        appName: String,
        tipo: TipoLicencia,
        secundarias: Int?,
        total: Int,
        licenseId: String,
        envelope: String,
        codigoLicencia: String? = null,
    ): String {
        val importe = total.toString().reversed().chunked(3).joinToString(" ").reversed()
        val detalle = if (secundarias == null) {
            "$importe CUP"
        } else {
            "$secundarias secundarias · $importe CUP"
        }
        val primera = "Licencia $appName $tipo · $detalle"
        val lineaCodigo = if (!codigoLicencia.isNullOrBlank()) "Código: $codigoLicencia\n" else ""
        return "$primera\n${lineaCodigo}ID: $licenseId\n$envelope"
    }
}
