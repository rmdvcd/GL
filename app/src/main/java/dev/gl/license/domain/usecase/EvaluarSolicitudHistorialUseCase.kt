package dev.gl.license.domain.usecase

import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.SolicitudLicencia
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.repository.LicenciaRepository
import dev.gl.license.domain.repository.SecureClock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resultado de la evaluación del historial previo para una solicitud:
 */
sealed interface EvaluacionHistorial {
    /**
     * Usuario con ese dispositivo no ha realizado peticiones anteriores en esta app.
     */
    data object NuevaSolicitud : EvaluacionHistorial

    /**
     * Tiene una licencia activa previa y solicita el MISMO tipo (renovación anticipada):
     * se calcula el tiempo restante de la activa y se suma a la nueva licencia (salvo si era de prueba).
     */
    data class ActivaMismoTipo(
        val activa: Licencia,
        val segundosRestantes: Long,
        val diasRestantes: Long,
        val aviso: String,
    ) : EvaluacionHistorial

    /**
     * Tiene una licencia activa previa y la solicitada es de tipo diferente (cambio de plan):
     * se le suma el periodo restante de la activa a la nueva (salvo si se solicita perpetua o si era de prueba).
     */
    data class ActivaDiferente(
        val activa: Licencia,
        val segundosRestantes: Long,
        val diasRestantes: Long,
        val aviso: String,
    ) : EvaluacionHistorial

    /**
     * Tiene una licencia vencida y la solicitud es del mismo tipo -> es una revalidación.
     */
    data class Revalidacion(
        val vencida: Licencia,
        val aviso: String,
    ) : EvaluacionHistorial

    /**
     * Tiene una licencia previa vencida y la nueva solicitud es de tipo distinto -> renovación con cambio de plan.
     */
    data class RenovacionCambio(
        val vencida: Licencia,
        val aviso: String,
    ) : EvaluacionHistorial

    /**
     * Rechazo: el usuario ya tiene una licencia perpetua activa.
     */
    data class RechazadaYaPerpetua(
        val perpetua: Licencia,
        val error: String,
    ) : EvaluacionHistorial
}

/**
 * Comprueba en el registro si el usuario con ese dispositivo ha realizado peticiones anteriores.
 * - Si tiene licencia activa: se calcula el tiempo restante de la activa y se suma a la nueva licencia
 *   (tanto si solicita el mismo tipo como si cambia de plan; excepto si solicita perpetua o si era de prueba).
 * - Si no tiene activa pero tiene alguna vencida: si la solicitud es igual, es una revalidación.
 */
@Singleton
class EvaluarSolicitudHistorialUseCase @Inject constructor(
    private val licencias: LicenciaRepository,
    private val clock: SecureClock,
) {
    suspend operator fun invoke(req: SolicitudLicencia): EvaluacionHistorial {
        val todas = try { licencias.all() } catch (_: Exception) { emptyList() }
        val previas = todas.filter { lic ->
            lic.deviceId == req.deviceId &&
                (lic.nationalId == req.effectiveNationalId || lic.phone == req.effectivePhone) &&
                lic.appName.equals(req.appName, ignoreCase = true)
        }.sortedByDescending { it.issuedAtIso }

        if (previas.isEmpty()) {
            return EvaluacionHistorial.NuevaSolicitud
        }

        val ahoraSec = clock.nowEpochMillis() / 1000L

        // Buscar si tiene alguna licencia activa actualmente
        val activa = previas.firstOrNull { lic ->
            if (lic.status == EstadoLicencia.REVOCADA) return@firstOrNull false
            if (lic.type == TipoLicencia.PERPETUA || lic.status == EstadoLicencia.PERPETUA) {
                return@firstOrNull true
            }
            if (lic.status == EstadoLicencia.ACTIVA && lic.expiresAtIso != null) {
                try {
                    val expSec = Instant.parse(lic.expiresAtIso).epochSecond
                    expSec > ahoraSec
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
        }

        if (activa != null) {
            if (activa.type == TipoLicencia.PERPETUA || activa.status == EstadoLicencia.PERPETUA) {
                return EvaluacionHistorial.RechazadaYaPerpetua(
                    perpetua = activa,
                    error = "El usuario ya cuenta con una licencia perpetua activa.",
                )
            }

            // Si tiene activa (sea del mismo tipo o diferente): sumarle el periodo restante
            // excepto que se solicite una perpetua; el periodo de prueba no cuenta (precioCobrado == 0)
            val esPrueba = (activa.precioCobrado != null && activa.precioCobrado == 0)
            val segundosRestantes = if (esPrueba || req.type == TipoLicencia.PERPETUA) {
                0L
            } else {
                try {
                    val expSec = Instant.parse(activa.expiresAtIso).epochSecond
                    maxOf(0L, expSec - ahoraSec)
                } catch (_: Exception) {
                    0L
                }
            }
            // Días restantes redondeados hacia arriba para no perder fracción de día
            val diasRestantes = if (segundosRestantes > 0) (segundosRestantes + 86399L) / 86400L else 0L

            if (req.type == activa.type) {
                val aviso = when {
                    esPrueba ->
                        "Licencia previa activa (${activa.type}) de prueba: el periodo de prueba no se acumula."
                    diasRestantes > 0 ->
                        "Renovación anticipada (${activa.type}): se sumarán $diasRestantes días restantes a la nueva licencia."
                    else ->
                        "Renovación de licencia activa (${activa.type})."
                }
                return EvaluacionHistorial.ActivaMismoTipo(
                    activa = activa,
                    segundosRestantes = segundosRestantes,
                    diasRestantes = diasRestantes,
                    aviso = aviso,
                )
            } else {
                val aviso = when {
                    req.type == TipoLicencia.PERPETUA ->
                        "Licencia previa activa (${activa.type}): cambio a Perpetua (licencia permanente sin vencimiento)."
                    esPrueba ->
                        "Licencia previa activa es de prueba: el periodo de prueba no se acumula."
                    diasRestantes > 0 ->
                        "Cambio de plan (${activa.type} → ${req.type}): se sumarán $diasRestantes días restantes a la nueva licencia."
                    else ->
                        "Licencia previa activa (${activa.type}) actualizada a ${req.type}."
                }
                return EvaluacionHistorial.ActivaDiferente(
                    activa = activa,
                    segundosRestantes = segundosRestantes,
                    diasRestantes = diasRestantes,
                    aviso = aviso,
                )
            }
        }

        // Si no tiene activa, revisar si tiene alguna vencida
        val vencida = previas.filter {
            it.status == EstadoLicencia.VENCIDA ||
                (it.expiresAtIso != null && Instant.parse(it.expiresAtIso).epochSecond <= ahoraSec)
        }.maxByOrNull { it.issuedAtIso }

        if (vencida != null) {
            return if (req.type == vencida.type) {
                EvaluacionHistorial.Revalidacion(
                    vencida = vencida,
                    aviso = "Revalidación: renovación de licencia vencida (${vencida.type}).",
                )
            } else {
                EvaluacionHistorial.RenovacionCambio(
                    vencida = vencida,
                    aviso = "Renovación con cambio: de ${vencida.type} vencida a ${req.type}.",
                )
            }
        }

        return EvaluacionHistorial.NuevaSolicitud
    }
}
