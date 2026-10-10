package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.presentation.contact.ContactClipboard
import org.junit.Assert.assertThrows
import org.junit.Test

class ContactClipboardTest {

    @Test
    fun firstLineIsTheHeaderTheUserSpecified() {
        val text = ContactClipboard.build(card = "4111111111111111", phone = "+34600111222", app = "SPVI")

        assertThat(text.lineSequence().first()).isEqualTo(
            "Tarjeta y numero a confirmar para compra de la licencia"
        )
    }

    @Test
    fun secondLineIsTheApp() {
        val text = ContactClipboard.build(card = "4111111111111111", phone = "+34600111222", app = "SPVI")

        assertThat(text.lineSequence().toList()).containsExactly(
            "Tarjeta y numero a confirmar para compra de la licencia",
            "SPVI",
            "4111111111111111",
            "+34600111222",
        )
    }

    @Test
    fun buildRefusesWhenCardIsBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            ContactClipboard.build(card = "   ", phone = "+34600111222", app = "SPVI")
        }
    }

    @Test
    fun buildRefusesWhenPhoneIsBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            ContactClipboard.build(card = "4111111111111111", phone = "  ", app = "SPVI")
        }
    }

    @Test
    fun buildRefusesWhenAppIsBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            ContactClipboard.build(card = "4111111111111111", phone = "+34600111222", app = "  ")
        }
    }
}
