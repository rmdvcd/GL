package dev.gl.license.domain.usecase

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.DatosUsuario
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.LicenseCodeGenerator
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
    private val clock: SecureClock,
) {
    constructor(crypto: SeguridadRepository) : this(crypto, object : SecureClock {
        override fun nowEpochMillis() = System.currentTimeMillis()
        override fun nowIso() = Instant.now().toString()
    })

    operator fun invoke(raw: String): Outcome<SolicitudValidada> {
        return when (val d = crypto.descifrarSolicitud(raw)) {
            is Outcome.Err -> d
            is Outcome.Ok -> {
                val now = try { Instant.parse(clock.nowIso()) } catch (_: Exception) { null }
                when (val v = FieldValidator.validateRequest(d.value, now)) {
                    is Outcome.Err -> v
                    is Outcome.Ok -> Outcome.Ok(SolicitudValidada(v.value, raw))
                }
            }
        }
    }
}

typealias ValidateRequestUseCase = ValidarSolicitudUseCase

class GenerarLicenciaUseCase @Inject constructor(
    private val crypto: SeguridadRepository,
    private val clock: SecureClock,
    private val licencias: LicenciaRepository,
) {
    constructor(crypto: SeguridadRepository, clock: SecureClock) : this(
        crypto, clock, object : LicenciaRepository {
            override fun observeAll() = kotlinx.coroutines.flow.emptyFlow<List<Licencia>>()
            override suspend fun upsert(license: Licencia) = Outcome.Ok(Unit)
            override suspend fun getById(id: String): Licencia? = null
            override suspend fun merge(incoming: List<Licencia>) = Outcome.Ok(0)
            override suspend fun all(): List<Licencia> = emptyList()
            override suspend fun isTransactionUsed(txNumber: String) = false
        }
    )

    operator fun invoke(
        req: SolicitudLicencia,
        paymentConfirmed: Boolean,
        secundarias: Int? = null,
        diasAdicionales: Long = 0L,
        txNumber: String? = null,
        idFactory: () -> String = { UUID.randomUUID().toString() },
        codigoFactory: () -> String = { LicenseCodeGenerator.generate() },
    ): Outcome<Pair<Licencia, String>> {
        if (!paymentConfirmed) return Outcome.Err(AppError.InvalidPayload)
        val cleanTx = txNumber?.trim()
        if (!cleanTx.isNullOrBlank() && kotlinx.coroutines.runBlocking { licencias.isTransactionUsed(cleanTx) }) {
            return Outcome.Err(AppError.TransactionAlreadyUsed(cleanTx))
        }
        if (req.appName == "SPVI" || req.deviceId.startsWith("SPVI:")) {
            return Outcome.Err(AppError.SpviDesactualizada)
        }
        val now = try { Instant.parse(clock.nowIso()) } catch (_: Exception) { null }
        when (val v = FieldValidator.validateRequest(req, now)) {
            is Outcome.Err -> return v
            is Outcome.Ok -> Unit
        }
        when (val s = Secundarias.validar(req.appName, req.deviceId, secundarias)) {
            is Outcome.Err -> return s
            is Outcome.Ok -> Unit
        }
        val issued = clock.nowIso()
        val exp = if (req.type == TipoLicencia.PERPETUA) {
            null
        } else {
            val baseDays = req.type.days() ?: 0L
            val totalDays = baseDays + maxOf(0L, diasAdicionales)
            Instant.parse(issued).plus(totalDays, ChronoUnit.DAYS).toString()
        }
        val status =
            if (req.type == TipoLicencia.PERPETUA) EstadoLicencia.PERPETUA else EstadoLicencia.ACTIVA
        val effectiveSecundarias = Secundarias.efectivas(req.appName, req.deviceId, secundarias ?: req.secundarias)
        val codigoLicencia = codigoFactory()
        val license = Licencia(
            version = CONTRACT_VERSION,
            id = idFactory(),
            deviceId = req.deviceId.trim(),
            appName = req.appName.trim(),
            dispositivo = req.dispositivo.trim(),
            firstName = req.effectiveFirstName.trim(),
            lastName = req.effectiveLastName.trim(),
            nationalId = req.effectiveNationalId.trim(),
            channel = req.effectiveChannel,
            phone = req.effectivePhone.trim(),
            usuario = DatosUsuario(
                nombre = req.effectiveFirstName.trim(),
                apellidos = req.effectiveLastName.trim(),
                ci = req.effectiveNationalId.trim(),
                telefono = req.effectivePhone.trim(),
                via = req.effectiveChannel,
            ),
            tipo = req.type,
            licenciaObtenida = codigoLicencia,
            secundarias = effectiveSecundarias,
            periodoRestante = if (diasAdicionales > 0) diasAdicionales else null,
            requestedAtIso = req.requestedAtIso,
            issuedAtIso = issued,
            expiresAtIso = exp,
            status = status,
            precioCobrado = effectiveSecundarias?.let { PrecioTabla.total(req.type, it) },
            codigoCorto = codigoLicencia,
            nonce = CryptoEngine.b64(CryptoEngine.randomBytes(16)),
            txNumber = cleanTx,
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
