package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.model.LicenseCodeGenerator
import org.junit.Test

class LicenseCodeTest {

    @Test
    fun generates16AlphanumericCharsInGroupsOf4() {
        val code = LicenseCodeGenerator.generate()

        // 16 caracteres + 3 guiones = 19 caracteres totales
        assertThat(code).hasLength(19)
        val parts = code.split("-")
        assertThat(parts).hasSize(4)
        for (part in parts) {
            assertThat(part).hasLength(4)
            for (c in part) {
                assertThat(c.isLetterOrDigit()).isTrue()
                if (c.isLetter()) {
                    assertThat(c.isUpperCase()).isTrue()
                }
            }
        }
        assertThat(LicenseCodeGenerator.isValid(code)).isTrue()
    }

    @Test
    fun validatesValidCodes() {
        assertThat(LicenseCodeGenerator.isValid("ABCD-EFGH-1234-5678")).isTrue()
        assertThat(LicenseCodeGenerator.isValid("ABCDEFGH12345678")).isTrue()
        assertThat(LicenseCodeGenerator.isValid("4K7N-9X2M-B8PQ-3T5V")).isTrue()
    }

    @Test
    fun rejectsInvalidCodes() {
        assertThat(LicenseCodeGenerator.isValid("")).isFalse()
        assertThat(LicenseCodeGenerator.isValid("ABC")).isFalse()
        assertThat(LicenseCodeGenerator.isValid("ABCD-EFGH-1234-567")).isFalse() // 15 chars
        assertThat(LicenseCodeGenerator.isValid("ABCD-EFGH-1234-56789")).isFalse() // 17 chars
        assertThat(LicenseCodeGenerator.isValid("ABCD-EFGH-1234-$$$$")).isFalse() // non-alphanumeric
    }
}
