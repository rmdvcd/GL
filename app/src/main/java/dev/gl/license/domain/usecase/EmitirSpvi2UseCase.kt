package dev.gl.license.domain.usecase

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.PrecioTabla
import dev.gl.license.domain.model.SolicitudSpviV2
import dev.gl.license.domain.model.SpviResponseMessage
import dev.gl.license.domain.repository.LicenciaRepository
import dev.gl.license.domain.repository.SecureClock
import dev.gl.license.domain.repository.SpviPuerta
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.Spvi23
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SpviEmitida(
    val licencia: Licencia,
    val mensaje: String,
    val renuevaIgnorada: Boolean,
)

/**
 * Emite una licencia corta SPVI2.
 * - `recupera`: gratuita, transfiere a nuevo deviceId, conserva tipo/vencimiento/secundarias y revoca la anterior.
 * - `amplia`: conserva vencimiento, amplía secundarias sobre licencia activa y cobra solo adicionales.
 * - `renueva`: extiende la duración sumando sobre el vencimiento vigente si existe y no está revocada ni es perpetua.
 */
@Singleton
class EmitirSpvi2UseCase @Inject constructor(
    private val puerta: SpviPuerta,
    private val licencias: LicenciaRepository,
    private val clock: SecureClock,
) {
    suspend fun registrada(id: String): Licencia? = licencias.getById(id)

    suspend operator fun invoke(
        sol: SolicitudSpviV2,
        pagoConfirmado: Boolean,
        txNumber: String? = null,
        verificacionManual: Boolean = false,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): Outcome<SpviEmitida> {
        if (sol.recupera == null && !pagoConfirmado) return Outcome.Err(AppError.InvalidPayload)
        when (val valid = Spvi23.validarSolicitud(sol)) {
            is Outcome.Err -> return valid
            is Outcome.Ok -> Unit
        }
        val ahora = try {
            Instant.parse(clock.nowIso())
        } catch (_: Exception) {
            return Outcome.Err(AppError.ClockFailure)
        }
        val requestedAt = try {
            Instant.parse(sol.solicitadaEn)
        } catch (_: Exception) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        if (!RequestFreshness.accepts(requestedAt, ahora)) {
            return Outcome.Err(AppError.RequestExpired)
        }
        val cleanTx = txNumber?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanTx != null) {
            if (cleanTx.length > 128 || cleanTx.any { it.isISOControl() }) {
                return Outcome.Err(AppError.InvalidPayload)
            }
            val used = try {
                licencias.isTransactionUsed(cleanTx)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return Outcome.Err(AppError.RegistryFailure)
            }
            if (used) return Outcome.Err(AppError.TransactionAlreadyUsed(cleanTx))
        }
        val ahoraSec = try {
            val ultima = licencias.all().asSequence().filter { it.appName == "SPVI" && it.deviceId == sol.deviceId }
                .maxOfOrNull { Instant.parse(it.issuedAtIso).epochSecond }
            maxOf(ahora.epochSecond, (ultima ?: (ahora.epochSecond - 1)) + 1)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { return Outcome.Err(AppError.RegistryFailure) }
        if (ahoraSec - ahora.epochSecond > 86_400) return Outcome.Err(AppError.EmisorDesajustado)

        // 1. Recuperación de licencia
        if (sol.recupera != null) {
            val prevRecupera = try {
                licencias.getById(sol.recupera.trim())
                    ?: licencias.all().firstOrNull { it.id.equals(sol.recupera.trim(), ignoreCase = true) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return Outcome.Err(AppError.RegistryFailure)
            } ?: return Outcome.Err(AppError.RecuperacionInvalida)

            if (prevRecupera.status == EstadoLicencia.REVOCADA || prevRecupera.status == EstadoLicencia.VENCIDA) {
                return Outcome.Err(AppError.RecuperacionInvalida)
            }
            fun nombre(s: String) = java.text.Normalizer.normalize(s.trim().lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("""\p{M}+"""), "").replace(Regex("""\s+"""), " ")
            if (!verificacionManual && (prevRecupera.phone.trim() != sol.telefono.trim() ||
                    nombre(prevRecupera.firstName) != nombre(sol.nombre) || nombre(prevRecupera.lastName) != nombre(sol.apellidos)))
                return Outcome.Err(AppError.IdentidadNoVerificada)
            val esPerpetua = prevRecupera.type == dev.gl.license.domain.model.TipoLicencia.PERPETUA ||
                prevRecupera.status == EstadoLicencia.PERPETUA
            val prevVenceSec = try {
                prevRecupera.expiresAtIso?.let { Instant.parse(it).epochSecond }
            } catch (_: Exception) {
                null
            }
            if (!esPerpetua && (prevVenceSec == null || prevVenceSec <= ahoraSec)) {
                return Outcome.Err(AppError.RecuperacionInvalida)
            }
            if (!prevRecupera.nationalId.trim().equals(sol.ci.trim(), ignoreCase = true)) {
                return Outcome.Err(AppError.RecuperacionInvalida)
            }

            val id = idFactory()
            val lic = Licencia(
                version = 2,
                id = id,
                firstName = sol.nombre,
                lastName = sol.apellidos,
                nationalId = sol.ci,
                channel = sol.via,
                phone = sol.telefono,
                deviceId = sol.deviceId,
                appName = "SPVI",
                type = prevRecupera.type,
                requestedAtIso = sol.solicitadaEn,
                issuedAtIso = Instant.ofEpochSecond(ahoraSec).toString(),
                expiresAtIso = if (esPerpetua) null else prevRecupera.expiresAtIso,
                status = if (esPerpetua) EstadoLicencia.PERPETUA else EstadoLicencia.ACTIVA,
                secundarias = prevRecupera.secundarias ?: sol.secundarias,
                verificacionManual = verificacionManual,
                precioCobrado = 0,
                codigoCorto = null,
                nonce = CryptoEngine.b64(CryptoEngine.randomBytes(16)),
                txNumber = cleanTx,
            )
            val devicePub33 = try {
                Spvi23.b64urlD(sol.devicePub)
            } catch (_: Exception) {
                return Outcome.Err(AppError.InvalidPayload)
            }
            return when (val c = puerta.emitirCodigo(lic, devicePub33)) {
                is Outcome.Err -> c
                is Outcome.Ok -> {
                    val conCodigo = lic.copy(codigoCorto = c.value)
                    val saved = licencias.issue(conCodigo, sol.nonce, sol.recupera?.trim())
                    if (saved is Outcome.Err) return saved
                    val venceSec = if (esPerpetua) null else prevVenceSec
                    val mensaje = SpviResponseMessage.build(
                        prevRecupera.type, lic.secundarias ?: 0, ahoraSec, venceSec, id, c.value,
                    )
                    Outcome.Ok(SpviEmitida(conCodigo, mensaje, renuevaIgnorada = false))
                }
            }
        }

        // 2. Ampliación de secundarias
        if (sol.amplia != null) {
            val prevAmplia = try {
                licencias.getById(sol.amplia.trim())
                    ?: licencias.all().firstOrNull { it.id.equals(sol.amplia.trim(), ignoreCase = true) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return Outcome.Err(AppError.RegistryFailure)
            } ?: return Outcome.Err(AppError.AmpliacionInvalida)

            val prevSec = (prevAmplia.secundarias ?: 0).coerceAtLeast(0)
            val prevVenceSec = try {
                prevAmplia.expiresAtIso?.let { Instant.parse(it).epochSecond }
            } catch (_: Exception) {
                return Outcome.Err(AppError.AmpliacionInvalida)
            }
            val esPerpetua = prevAmplia.type == dev.gl.license.domain.model.TipoLicencia.PERPETUA ||
                prevAmplia.status == EstadoLicencia.PERPETUA
            val vigente = prevAmplia.status != EstadoLicencia.REVOCADA &&
                prevAmplia.status != EstadoLicencia.VENCIDA &&
                (esPerpetua || (prevVenceSec != null && prevVenceSec > ahoraSec))
            if (!vigente ||
                prevAmplia.deviceId != sol.deviceId ||
                prevAmplia.type != sol.tipo ||
                sol.secundarias !in (prevSec + 1)..10
            ) {
                return Outcome.Err(AppError.AmpliacionInvalida)
            }
            val adicionales = sol.secundarias - prevSec
            val venceAmpliaSec = if (esPerpetua) null else prevVenceSec
            val id = idFactory()
            val lic = Licencia(
                version = 2,
                id = id,
                firstName = sol.nombre,
                lastName = sol.apellidos,
                nationalId = sol.ci,
                channel = sol.via,
                phone = sol.telefono,
                deviceId = sol.deviceId,
                appName = "SPVI",
                type = prevAmplia.type,
                requestedAtIso = sol.solicitadaEn,
                issuedAtIso = Instant.ofEpochSecond(ahoraSec).toString(),
                expiresAtIso = venceAmpliaSec?.let { Instant.ofEpochSecond(it).toString() },
                status = if (esPerpetua) EstadoLicencia.PERPETUA else EstadoLicencia.ACTIVA,
                secundarias = sol.secundarias,
                precioCobrado = PrecioTabla.ampliacion(prevAmplia.type, adicionales),
                codigoCorto = null,
                nonce = CryptoEngine.b64(CryptoEngine.randomBytes(16)),
                txNumber = cleanTx,
            )
            val devicePub33 = try {
                Spvi23.b64urlD(sol.devicePub)
            } catch (_: Exception) {
                return Outcome.Err(AppError.InvalidPayload)
            }
            return when (val c = puerta.emitirCodigo(lic, devicePub33)) {
                is Outcome.Err -> c
                is Outcome.Ok -> {
                    val conCodigo = lic.copy(codigoCorto = c.value)
                    val saved = licencias.issue(conCodigo, sol.nonce, sol.recupera?.trim())
                    if (saved is Outcome.Err) return saved
                    val mensaje = SpviResponseMessage.build(
                        prevAmplia.type, sol.secundarias, ahoraSec, venceAmpliaSec, id, c.value,
                    )
                    Outcome.Ok(SpviEmitida(conCodigo, mensaje, renuevaIgnorada = false))
                }
            }
        }

        // 3. Renovación o nueva emisión
        val dias = sol.tipo.days()
        val prev = sol.renueva?.let { idRen ->
            licencias.getById(idRen.trim()) ?: licencias.all().firstOrNull { it.id.equals(idRen.trim(), ignoreCase = true) }
        }
        val aplicaRenueva = prev != null &&
            prev.deviceId == sol.deviceId &&
            prev.status != EstadoLicencia.REVOCADA &&
            prev.type != dev.gl.license.domain.model.TipoLicencia.PERPETUA &&
            prev.status != EstadoLicencia.PERPETUA
        val (emitidaSec, venceSec) = if (aplicaRenueva) {
            val prevVence = prev!!.expiresAtIso?.let { Instant.parse(it).epochSecond } ?: ahoraSec
            ahoraSec to (maxOf(ahoraSec, prevVence) + (dias ?: 0) * 86_400L).takeIf { dias != null }
        } else {
            ahoraSec to dias?.let { ahoraSec + it * 86_400L }
        }
        val id = idFactory()
        val lic = Licencia(
            version = 2,
            id = id,
            firstName = sol.nombre,
            lastName = sol.apellidos,
            nationalId = sol.ci,
            channel = sol.via,
            phone = sol.telefono,
            deviceId = sol.deviceId,
            appName = "SPVI",
            type = sol.tipo,
            requestedAtIso = sol.solicitadaEn,
            issuedAtIso = Instant.ofEpochSecond(emitidaSec).toString(),
            expiresAtIso = venceSec?.let { Instant.ofEpochSecond(it).toString() },
            status = if (dias == null) EstadoLicencia.PERPETUA else EstadoLicencia.ACTIVA,
            secundarias = sol.secundarias,
            precioCobrado = PrecioTabla.total(sol.tipo, sol.secundarias),
            codigoCorto = null,
            nonce = CryptoEngine.b64(CryptoEngine.randomBytes(16)),
            txNumber = cleanTx,
        )
        val devicePub33 = try {
            Spvi23.b64urlD(sol.devicePub)
        } catch (_: Exception) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        return when (val c = puerta.emitirCodigo(lic, devicePub33)) {
            is Outcome.Err -> c
            is Outcome.Ok -> {
                val conCodigo = lic.copy(codigoCorto = c.value)
                    val saved = licencias.issue(conCodigo, sol.nonce, sol.recupera?.trim())
                    if (saved is Outcome.Err) return saved
                val mensaje = SpviResponseMessage.build(
                    sol.tipo, sol.secundarias, emitidaSec, venceSec, id, c.value,
                )
                Outcome.Ok(SpviEmitida(conCodigo, mensaje, sol.renueva != null && !aplicaRenueva))
            }
        }
    }
}
