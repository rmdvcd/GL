package dev.gl.license.domain.model

import java.net.URLEncoder

/** Dirección de entrega; no modifica el teléfono almacenado ni el contrato de solicitud. */
internal object DeliveryAddress {
    private val local = Regex("5[0-9]{7}")
    private val cuba = Regex("\\+?535[0-9]{7}")
    private val international = Regex("\\+[1-9][0-9]{7,14}")

    fun normalize(raw: String): String? {
        val n = raw.trim().filterNot { it in " -()" }
        return when {
            local.matches(n) -> "+53$n"
            cuba.matches(n) -> "+${n.removePrefix("+")}"
            n.startsWith("+53") -> null // No redirigir números cubanos ambiguos/no móviles.
            international.matches(n) -> n
            else -> null
        }
    }

    fun sms(phone: String) = normalize(phone)?.let { "smsto:$it" }
    fun whatsapp(phone: String, body: String) = normalize(phone)?.let {
        val text = URLEncoder.encode(body, "UTF-8").replace("+", "%20")
        "https://wa.me/${it.removePrefix("+")}?text=$text"
    }
}
