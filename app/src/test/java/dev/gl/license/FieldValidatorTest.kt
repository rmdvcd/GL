package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.LicenseRequest
import dev.gl.license.domain.model.IssuedLicense
import dev.gl.license.domain.model.LicenseType
import dev.gl.license.domain.model.RequestChannel
import dev.gl.license.domain.usecase.FieldValidator
import org.junit.Test

class FieldValidatorTest {
    private companion object {
        const val TEST_PUB_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEq+BkCJzxNvUEV6J0XQyY0g71EvOfZ87syGdhURoMlOdgWFlEVs2aDxsGlia4Df+ZS6LPE6dmfCpCGZhSoC6J1Q=="
    }

    private fun valid() = LicenseRequest(
        version = CONTRACT_VERSION,
        firstName = "Ana",
        lastName = "Perez",
        nationalId = "85010112345",
        channel = RequestChannel.WHATSAPP,
        phone = "+5355512345",
        deviceId = "device-abc-12345",
        appName = "MiApp",
        type = LicenseType.MENSUAL,
        requestedAtIso = "2026-01-01T00:00:00Z",
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
        devicePublicKeyPem = TEST_PUB_KEY,
    )

    @Test
    fun acceptsValid() {
        assertThat(FieldValidator.validateRequest(valid())).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun rejectsMissingDevicePub() {
        val r = valid().copy(devicePublicKeyPem = null)
        val res = FieldValidator.validateRequest(r)
        assertThat(res).isInstanceOf(Outcome.Err::class.java)
        assertThat((res as Outcome.Err).error).isEqualTo(AppError.DevicePubRequired)
    }

    @Test
    fun rejectsInvalidDevicePub() {
        val r = valid().copy(devicePublicKeyPem = "NOT_A_KEY")
        val res = FieldValidator.validateRequest(r)
        assertThat(res).isInstanceOf(Outcome.Err::class.java)
        assertThat((res as Outcome.Err).error).isEqualTo(AppError.InvalidPayload)
    }

    @Test
    fun rejectsRequestOlderThan72Hours() {
        val r = valid().copy(requestedAtIso = "2026-01-01T00:00:00Z")
        val now = java.time.Instant.parse("2026-01-04T01:00:00Z") // 73 hours
        val res = FieldValidator.validateRequest(r, now = now)
        assertThat(res).isInstanceOf(Outcome.Err::class.java)
        assertThat((res as Outcome.Err).error).isEqualTo(AppError.RequestExpired)
    }

    @Test
    fun acceptsRequestWithin72Hours() {
        val r = valid().copy(requestedAtIso = "2026-01-01T00:00:00Z")
        val now = java.time.Instant.parse("2026-01-03T12:00:00Z") // 60 hours
        val res = FieldValidator.validateRequest(r, now = now)
        assertThat(res).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun rejectsBadPhone() {
        val r = valid().copy(phone = "123")
        assertThat(FieldValidator.validateRequest(r)).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun rejectsEmptyAppName() {
        val r = valid().copy(appName = "")
        assertThat(FieldValidator.validateRequest(r)).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun rejectsTooLongAppName() {
        val r = valid().copy(appName = "a".repeat(81))
        assertThat(FieldValidator.validateRequest(r)).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun rejectsAppNameWithIllegalChars() {
        val r = valid().copy(appName = "MiApp/../etc")
        assertThat(FieldValidator.validateRequest(r)).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun acceptsAppNameWithSpaces() {
        val r = valid().copy(appName = "Mi App v2")
        assertThat(FieldValidator.validateRequest(r)).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun acceptsRequestWithDispositivoAndUsuario() {
        val r = valid().copy(
            dispositivo = "Pixel 8 Pro",
            usuario = dev.gl.license.domain.model.DatosUsuario(
                nombre = "Carlos",
                apellidos = "Ramirez",
                ci = "90020212345",
                telefono = "+5351234567",
                via = RequestChannel.SMS,
            ),
        )
        assertThat(FieldValidator.validateRequest(r)).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun rejectsSecundariasAboveMaximumForSpvi() {
        val r = valid().copy(appName = "SPVI", deviceId = "SPVI:abc-12345", secundarias = 11)
        val result = FieldValidator.validateRequest(r)

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error).isEqualTo(AppError.InvalidSecundarias)
    }

    private fun validLicense() = IssuedLicense(
        version = CONTRACT_VERSION,
        id = "lic-123",
        firstName = "Ana",
        lastName = "Perez",
        nationalId = "85010112345",
        channel = RequestChannel.WHATSAPP,
        phone = "+5355512345",
        deviceId = "SPVI:abc-12345",
        appName = "SPVI",
        type = LicenseType.MENSUAL,
        requestedAtIso = "2026-01-01T00:00:00Z",
        issuedAtIso = "2026-01-02T00:00:00Z",
        expiresAtIso = "2026-02-01T00:00:00Z",
        status = dev.gl.license.domain.model.LicenseStatus.ACTIVA,
        secundarias = 11,
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
    )

    @Test
    fun rejectsLicenseWhenSecundariasAreAboveMaximumForSpvi() {
        val result = FieldValidator.validateLicense(validLicense())

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error).isEqualTo(AppError.InvalidSecundarias)
    }

    private fun validLicenseV2() = IssuedLicense(
        version = 2,
        id = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        firstName = "Maria",
        lastName = "Perez Gonzalez",
        nationalId = "85010112345",
        channel = RequestChannel.WHATSAPP,
        phone = "+5352345678",
        deviceId = "SPVI:golden0000000001",
        appName = "SPVI",
        type = LicenseType.MENSUAL,
        requestedAtIso = "2026-10-03T20:00:00Z",
        issuedAtIso = "2026-10-03T20:00:01Z",
        expiresAtIso = "2026-11-02T20:00:01Z",
        status = dev.gl.license.domain.model.LicenseStatus.ACTIVA,
        secundarias = 2,
        codigoCorto = "SPVI2:x",
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
    )

    @Test
    fun acceptsLicenseV2WithCodigoCorto() {
        assertThat(FieldValidator.validateLicense(validLicenseV2()))
            .isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun rejectsLicenseV2WithoutCodigoCorto() {
        val result = FieldValidator.validateLicense(validLicenseV2().copy(codigoCorto = null))

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun rejectsLicenseV2WithInvalidRequestedAt() {
        val result = FieldValidator.validateLicense(validLicenseV2().copy(requestedAtIso = "ayer"))

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun rejectsLicenseV2WithSecundariasAboveMaximum() {
        val result = FieldValidator.validateLicense(validLicenseV2().copy(secundarias = 11))

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error).isEqualTo(AppError.InvalidSecundarias)
    }
}