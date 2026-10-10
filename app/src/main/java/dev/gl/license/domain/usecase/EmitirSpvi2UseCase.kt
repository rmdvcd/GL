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
 * Emite una licencia corta SPVI2. `renueva` solo se aplica si la licencia
 * previa existe, es del mismo deviceId y no está revocada ni es perpetua.
 */
@Singleton
class EmitirSpvi2UseCase @Inject constructor(
    private val puerta: SpviPuerta,
    private val licencias: LicenciaRepository,
    private val clock: SecureClock,
) {
    suspend operator fun invoke(
        sol: SolicitudSpviV2,
        pagoConfirmado: Boolean,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): Outcome<SpviEmitida> {
        if (!pagoConfirmado) return Outcome.Err(AppError.InvalidPayload)
        val ahoraSec = clock.nowEpochMillis() / 1_000L
        val dias = sol.tipo.days()
        val prev = sol.renueva?.let { licencias.getById(it) }
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
                val mensaje = SpviResponseMessage.build(
                    sol.tipo, sol.secundarias, emitidaSec, venceSec, id, c.value,
                )
                Outcome.Ok(SpviEmitida(conCodigo, mensaje, sol.renueva != null && !aplicaRenueva))
            }
        }
    }
}
