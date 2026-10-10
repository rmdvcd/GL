package dev.gl.license.domain.model

object PrecioTabla {
    private val base = mapOf(
        TipoLicencia.MENSUAL to 6_000,
        TipoLicencia.SEMESTRAL to 30_000,
        TipoLicencia.ANUAL to 50_000,
        TipoLicencia.PERPETUA to 90_000,
    )
    private val extraPorSecundaria = mapOf(
        TipoLicencia.MENSUAL to 1_000,
        TipoLicencia.SEMESTRAL to 5_000,
        TipoLicencia.ANUAL to 9_000,
        TipoLicencia.PERPETUA to 17_000,
    )

    fun total(tipo: TipoLicencia, secundarias: Int): Int =
        base.getValue(tipo) + extraPorSecundaria.getValue(tipo) * secundarias

    fun desglose(tipo: TipoLicencia, secundarias: Int?): String {
        val valor = secundarias ?: return "Secundarias: no indicado (app anterior) · ${miles(total(tipo, 0))} CUP"
        val nombre = tipo.name.lowercase().replaceFirstChar { it.uppercase() }
        return "$nombre ${miles(base.getValue(tipo))} + $valor secundarias × " +
            "${miles(extraPorSecundaria.getValue(tipo))} = ${miles(total(tipo, valor))} CUP"
    }

    fun formatoPrecio(valor: Int): String = "${miles(valor)} CUP"

    private fun miles(valor: Int): String =
        valor.toString().reversed().chunked(3).joinToString(" ").reversed()
}
