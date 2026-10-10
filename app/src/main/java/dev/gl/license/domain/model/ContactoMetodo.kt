package dev.gl.license.domain.model

/**
 * Un dato de contacto que el usuario teclea y reutiliza para construir el
 * texto de confirmación de compra.
 *
 * No forma parte del contrato criptográfico v1: esto nunca entra en un
 * envelope ni se firma. Solo vive en la tabla `contact_methods`, que la BD
 * SQLCipher cifra igual que el resto.
 */
data class ContactoMetodo(
    val id: String,
    val kind: String,
    val value: String,
    val createdAtIso: String,
)

/**
 * Discriminante de [ContactoMetodo]. Se guardan como texto plano en la columna
 * `kind`; cambiar estos valores rompe las filas ya guardadas.
 */
object ContactKind {
    const val CARD = "card"
    const val PHONE = "phone"
    const val APP = "app"
}
