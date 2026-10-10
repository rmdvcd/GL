package dev.gl.license.domain.usecase

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.PrecioTabla
import dev.gl.license.domain.model.RegistroLicencia
import dev.gl.license.domain.model.Secundarias
import dev.gl.license.domain.model.SolicitudLicencia
import dev.gl.license.domain.model.SolicitudValidada
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.repository.LicenciaRepository
import dev.gl.license.domain.repository.SeguridadRepository
import dev.gl.license.domain.repository.RegistroRepository
import dev.gl.license.domain.repository.SecureClock
import dev.gl.license.security.CryptoEngine
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class ValidarSolicitudUseCase @Inject constructor(
    private val crypto: SeguridadRepository,
) {
    operator fun invoke(raw: String): Outcome<SolicitudValidada> {
        return when (val d = crypto.descifrarSolicitud(raw)) {
            is Outcome.Err -> d
            is Outcome.Ok -> when (val v = FieldValidator.validateRequest(d.value)) {
                is Outcome.Err -> v
                is Outcome.Ok -> Outcome.Ok(SolicitudValidada(v.value, raw))
            }
        }
    }
}

typealias ValidateRequestUseCase = ValidarSolicitudUseCase

class GenerarLicenciaUseCase @Inject constructor(
    private val crypto: SeguridadRepository,
    private val clock: SecureClock,
) {
    operator fun invoke(
        req: SolicitudLicencia,
        paymentConfirmed: Boolean,
        secundarias: Int? = null,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): Outcome<Pair<Licencia, String>> {
        if (!paymentConfirmed) return Outcome.Err(AppError.InvalidPayload)
        if (req.appName == "SPVI" || req.deviceId.startsWith("SPVI:")) {
            return Outcome.Err(AppError.SpviDesactualizada)
        }
        when (val v = FieldValidator.validateRequest(req)) {
            is Outcome.Err -> return v
            is Outcome.Ok -> Unit
        }
        when (val s = Secundarias.validar(req.appName, req.deviceId, secundarias)) {
            is Outcome.Err -> return s
            is Outcome.Ok -> Unit
        }
        val issued = clock.nowIso()
        val exp = req.type.days()?.let {
            Instant.parse(issued).plus(it, ChronoUnit.DAYS).toString()
        }
        val status =
            if (req.type == TipoLicencia.PERPETUA) EstadoLicencia.PERPETUA else EstadoLicencia.ACTIVA
        val secundarias = Secundarias.efectivas(req.appName, req.deviceId, secundarias ?: req.secundarias)
        val license = Licencia(
            version = CONTRACT_VERSION,
            id = idFactory(),
            firstName = req.firstName.trim(),
            lastName = req.lastName.trim(),
            nationalId = req.nationalId.trim(),
            channel = req.channel,
            phone = req.phone.trim(),
            deviceId = req.deviceId.trim(),
            appName = req.appName.trim(),
            type = req.type,
            requestedAtIso = req.requestedAtIso,
            issuedAtIso = issued,
            expiresAtIso = exp,
            status = status,
            secundarias = secundarias,
            precioCobrado = secundarias?.let { PrecioTabla.total(req.type, it) },
            nonce = CryptoEngine.b64(CryptoEngine.randomBytes(16)),
        )
        return when (val enc = crypto.cifrarLicencia(license, req.devicePublicKeyPem)) {
            is Outcome.Err -> enc
            is Outcome.Ok -> Outcome.Ok(license to enc.value)
        }
    }
}

typealias GenerateLicenseUseCase = GenerarLicenciaUseCase

class RegistrarLicenciaUseCase @Inject constructor(
    private val licencias: LicenciaRepository,
    private val registro: RegistroRepository,
    private val clock: SecureClock,
) {    suspend operator fun invoke(license: Licencia): Outcome<Unit> {
        when (val v = FieldValidator.validateLicense(license)) {
            is Outcome.Err -> return v
            is Outcome.Ok -> Unit
        }
        when (val u = licencias.upsert(license)) {
            is Outcome.Err -> return u
            is Outcome.Ok -> Unit
        }
        return registro.registrar(RegistroLicencia(license, clock.nowIso()))
    }
}

typealias RegisterLicenseUseCase = RegistrarLicenciaUseCase

class ImportarRegistroUseCase @Inject constructor(
    private val crypto: SeguridadRepository,
    private val licencias: LicenciaRepository,
    private val registro: RegistroRepository,
    private val clock: SecureClock,
) {
    suspend operator fun invoke(bytes: ByteArray): Outcome<Int> {
        return when (val parsed = crypto.importar(bytes)) {
            is Outcome.Err -> parsed
            is Outcome.Ok -> {
                val n = licencias.merge(parsed.value)
                if (n is Outcome.Ok) {
                    registro.mergePorId(
                        parsed.value.map { RegistroLicencia(it, clock.nowIso()) }
                    )
                }
                n
            }
        }
    }
}

typealias ImportRegistryUseCase = ImportarRegistroUseCase

class ExportarRegistroUseCase @Inject constructor(
    private val crypto: SeguridadRepository,
    private val licencias: LicenciaRepository,
) {
    suspend operator fun invoke(): Outcome<ByteArray> = crypto.exportar(licencias.all())
}

typealias ExportRegistryUseCase = ExportarRegistroUseCase
