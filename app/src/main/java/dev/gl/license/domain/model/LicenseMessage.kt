package dev.gl.license.domain.model

object LicenseMessage {
    fun build(
        appName: String,
        tipo: TipoLicencia,
        secundarias: Int?,
        total: Int,
        licenseId: String,
        envelope: String,
    ): String {
        val importe = total.toString().reversed().chunked(3).joinToString(" ").reversed()
        val detalle = if (secundarias == null) {
            "$importe CUP"
        } else {
            "$secundarias secundarias · $importe CUP"
        }
        val primera = "Licencia $appName $tipo · $detalle"
        return "$primera\nID: $licenseId\n$envelope"
    }
}
