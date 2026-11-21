package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.model.DeliveryAddress
import org.junit.Test

class DeliveryAddressTest {
    @Test fun normalizaMovilesCubanosSinInventarDestinatarios() {
        for (phone in listOf("51234567", "+53 5123-4567", "5351234567")) {
            assertThat(DeliveryAddress.normalize(phone)).isEqualTo("+5351234567")
        }
        for (phone in listOf("", "12345678", "+535123", "+5341234567", "5O234567", "++5351234567", "51234567?body=x")) {
            assertThat(DeliveryAddress.sms(phone)).isNull()
            assertThat(DeliveryAddress.whatsapp(phone, "texto")).isNull()
        }
        assertThat(DeliveryAddress.normalize("+12025550123")).isEqualTo("+12025550123")
    }

    @Test fun destinosSeparanNumeroYCuerpo() {
        assertThat(DeliveryAddress.sms("51234567")).isEqualTo("smsto:+5351234567")
        assertThat(DeliveryAddress.whatsapp("51234567", "A +&\nñ?"))
            .isEqualTo("https://wa.me/5351234567?text=A%20%2B%26%0A%C3%B1%3F")
    }
}
