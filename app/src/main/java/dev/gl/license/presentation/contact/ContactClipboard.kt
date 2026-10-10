package dev.gl.license.presentation.contact

/**
 * Construye el texto que se copia al portapapeles para confirmar una compra.
 *
 * Puro a propósito: es el mismo patrón que [dev.gl.license.security.PublicKeyBundle],
 * así que se testea en JVM sin dispositivo. El encabezado va aquí y no en
 * strings.xml porque es contenido del payload, no chrome de la pantalla, igual
 * que las claves del JSON de PublicKeyBundle.
 */
object ContactClipboard {

    /**
     * Literal tal cual lo pidió el usuario. Sin tilde en «numero»: es lo que va
     * a leer la pasarela de pago de destino y cambiarlo rompería el pegado.
     */
    const val HEADER = "Tarjeta y numero a confirmar para compra de la licencia"

    fun build(card: String, phone: String, app: String): String {
        // Un payload con una línea en blanco se pegaría roto en la pasarela de
        // pago. La UI ya deshabilita el botón sin las tres, esto es la red detrás.
        require(card.isNotBlank()) { "card is blank" }
        require(phone.isNotBlank()) { "phone is blank" }
        require(app.isNotBlank()) { "app is blank" }
        return "$HEADER\n$app\n$card\n$phone"
    }
}
