package dev.gl.license.domain.model

/** Subconjunto GSM-7 del emisor: un carácter = un septeto, sin tabla extendida. */
internal fun String.isOperatorGsm7() = all {
    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' ||
        it in "ñÑéèùìòÇäöüÄÖÜ \n!\"#%&'()*+,-./:;<=>?@_\$"
}

object RedeemCodeMessage {
    fun build(entry: RedeemBatchEntry): String {
        val code = RedeemBatchCanonical.format(requireNotNull(RedeemBatchCanonical.normalize(entry.code)))
        val tipo = entry.type.name.lowercase().replaceFirstChar { it.uppercase() }
        return "Shalev. Codigo de licencia: $code ($tipo). En la app: Licencia > Tengo un codigo. Necesita internet."
    }
}
