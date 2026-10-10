package dev.gl.license.domain.usecase

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.Secundarias
import dev.gl.license.domain.model.SolicitudLicencia
import dev.gl.license.domain.model.TipoLicencia
import java.time.Instant
import java.time.format.DateTimeParseException

object FieldValidator {
    private val phoneRe = Regex("^\\+?[1-9]\\d{7,14}$")
    private val ciRe = Regex("^[A-Za-z0-9]{5,20}$")
    private val nameRe = Regex("^[\\p{L} .'-]{2,80}$")
    private val deviceRe = Regex("^[A-Za-z0-9:_-]{8,128}$")
    private val appNameRe = Regex("^[\\p{L}0-9 ._'-]{2,80}$")
    private val nonceRe = Regex("^[A-Za-z0-9+/=_-]{16,128}$")

    fun validateRequest(r: SolicitudLicencia): Outcome<SolicitudLicencia> {
        if (r.version != CONTRACT_VERSION) return Outcome.Err(AppError.InvalidPayload)
        if (!nameRe.matches(r.firstName.trim()) || !nameRe.matches(r.lastName.trim())) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        if (!ciRe.matches(r.nationalId.trim())) return Outcome.Err(AppError.InvalidPayload)
        if (!phoneRe.matches(r.phone.trim())) return Outcome.Err(AppError.InvalidPayload)
        if (!deviceRe.matches(r.deviceId.trim())) return Outcome.Err(AppError.InvalidPayload)
        if (!appNameRe.matches(r.appName.trim())) return Outcome.Err(AppError.InvalidPayload)
        if (!nonceRe.matches(r.nonce.trim())) return Outcome.Err(AppError.InvalidPayload)
        when (val secundarias = Secundarias.validar(r.appName, r.deviceId, r.secundarias)) {
            is Outcome.Err -> return secundarias
            is Outcome.Ok -> Unit
        }
        try {
            Instant.parse(r.requestedAtIso)
        } catch (_: DateTimeParseException) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        return Outcome.Ok(r)
    }

    fun validateLicense(l: Licencia): Outcome<Licencia> {
        if (l.version == 2) return validateLicenseV2(l)
        val asReq = SolicitudLicencia(
            version = l.version,
            firstName = l.firstName,
            lastName = l.lastName,
            nationalId = l.nationalId,
            channel = l.channel,
            phone = l.phone,
            deviceId = l.deviceId,
            appName = l.appName,
            secundarias = l.secundarias,
            type = l.type,
            requestedAtIso = l.requestedAtIso,
            nonce = l.nonce,
        )
        when (val v = validateRequest(asReq)) {
            is Outcome.Err -> return v
            is Outcome.Ok -> Unit
        }
        if (l.id.isBlank() || l.id.length > 64) return Outcome.Err(AppError.InvalidPayload)
        try {
            Instant.parse(l.issuedAtIso)
            l.expiresAtIso?.let { Instant.parse(it) }
        } catch (_: DateTimeParseException) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        if (l.type == TipoLicencia.PERPETUA) {
            if (l.expiresAtIso != null) return Outcome.Err(AppError.InvalidPayload)
            if (l.status != EstadoLicencia.PERPETUA && l.status != EstadoLicencia.REVOCADA) {
                return Outcome.Err(AppError.InvalidPayload)
            }
        } else if (l.expiresAtIso == null) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        return Outcome.Ok(l)
    }

    /**
     * Licencia corta SPVI2: la app se deriva del prefijo de deviceId (el JSON v2
     * ya no trae appName) y secundarias es obligatorio 0–10. La firma del código
     * la verifica [dev.gl.license.security.EnvelopeValidator.licenciaCorta].
     */
    private fun validateLicenseV2(l: Licencia): Outcome<Licencia> {
        if (l.appName != "SPVI") return Outcome.Err(AppError.InvalidPayload)
        if (!l.deviceId.startsWith("SPVI:")) return Outcome.Err(AppError.InvalidPayload)
        if (!nameRe.matches(l.firstName.trim()) || !nameRe.matches(l.lastName.trim())) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        if (!ciRe.matches(l.nationalId.trim())) return Outcome.Err(AppError.InvalidPayload)
        if (!phoneRe.matches(l.phone.trim())) return Outcome.Err(AppError.InvalidPayload)
        if (!deviceRe.matches(l.deviceId.trim())) return Outcome.Err(AppError.InvalidPayload)
        if (!nonceRe.matches(l.nonce.trim())) return Outcome.Err(AppError.InvalidPayload)
        if (l.secundarias == null) return Outcome.Err(AppError.InvalidSecundarias)
        when (val s = Secundarias.validar(null, l.deviceId, l.secundarias)) {
            is Outcome.Err -> return s
            is Outcome.Ok -> Unit
        }
        if (l.id.isBlank() || l.id.length > 64) return Outcome.Err(AppError.InvalidPayload)
        try {
            Instant.parse(l.issuedAtIso)
            l.expiresAtIso?.let { Instant.parse(it) }
        } catch (_: DateTimeParseException) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        if (l.type == TipoLicencia.PERPETUA) {
            if (l.expiresAtIso != null) return Outcome.Err(AppError.InvalidPayload)
            if (l.status != EstadoLicencia.PERPETUA && l.status != EstadoLicencia.REVOCADA) {
                return Outcome.Err(AppError.InvalidPayload)
            }
        } else if (l.expiresAtIso == null) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        if (l.codigoCorto == null) return Outcome.Err(AppError.InvalidPayload)
        return Outcome.Ok(l)
    }
}
