package dev.gl.license.domain.usecase

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.PrecioTabla
import dev.gl.license.domain.model.SolicitudLicencia
import dev.gl.license.domain.model.SolicitudSpviV2
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

    /** La evaluación no puede continuar sin un historial y reloj fiables. */
    data class ErrorEvaluacion(val error: String) : EvaluacionHistorial
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
        val todas = try {
            licencias.all()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return EvaluacionHistorial.ErrorEvaluacion(
                "No se pudo consultar el historial; no se puede continuar con la emisión.",
            )
        }
        val previas = todas.filter { lic ->
            lic.deviceId == req.deviceId &&
                (lic.nationalId == req.effectiveNationalId || lic.phone == req.effectivePhone) &&
                lic.appName.equals(req.appName, ignoreCase = true)
        }.sortedByDescending { it.issuedAtIso }

        if (previas.isEmpty()) {
            return EvaluacionHistorial.NuevaSolicitud
        }

        val ahoraSec = try {
            clock.nowEpochMillis() / 1000L
        } catch (_: Exception) {
            return EvaluacionHistorial.ErrorEvaluacion(
                "No se pudo obtener una hora fiable para evaluar el historial.",
            )
        }

        // Buscar si tiene alguna licencia activa actualmente. Un vencimiento inválido
        // no se interpreta como una licencia inexistente: se bloquea la emisión.
        var activa: Licencia? = null
        for (lic in previas) {
            if (lic.status == EstadoLicencia.REVOCADA) continue
            if (lic.type == TipoLicencia.PERPETUA || lic.status == EstadoLicencia.PERPETUA) {
                activa = lic
                break
            }
            if (lic.status == EstadoLicencia.ACTIVA) {
                val vencimiento = lic.expiresAtIso
                    ?: return EvaluacionHistorial.ErrorEvaluacion(
                        "El historial contiene una licencia activa sin vencimiento válido.",
                    )
                val expSec = try {
                    Instant.parse(vencimiento).epochSecond
                } catch (_: Exception) {
                    return EvaluacionHistorial.ErrorEvaluacion(
                        "El historial contiene una fecha de vencimiento inválida.",
                    )
                }
                if (expSec > ahoraSec) {
                    activa = lic
                    break
                }
            }
        }

        val licenciaActiva = activa
        if (licenciaActiva != null) {
            if (licenciaActiva.type == TipoLicencia.PERPETUA ||
                licenciaActiva.status == EstadoLicencia.PERPETUA
            ) {
                return EvaluacionHistorial.RechazadaYaPerpetua(
                    perpetua = licenciaActiva,
                    error = "El usuario ya cuenta con una licencia perpetua activa.",
                )
            }

            // Si tiene activa (sea del mismo tipo o diferente): sumarle el periodo restante
            // excepto que se solicite una perpetua; el periodo de prueba no cuenta (precioCobrado == 0)
            val esPrueba = licenciaActiva.precioCobrado == 0
            val segundosRestantes = if (esPrueba || req.type == TipoLicencia.PERPETUA) {
                0L
            } else {
                val vencimiento = checkNotNull(licenciaActiva.expiresAtIso)
                maxOf(0L, Instant.parse(vencimiento).epochSecond - ahoraSec)
            }
            // Días restantes redondeados hacia arriba para no perder fracción de día
            val diasRestantes = if (segundosRestantes > 0) (segundosRestantes + 86399L) / 86400L else 0L

            if (req.type == licenciaActiva.type) {
                val aviso = when {
                    esPrueba ->
                        "Licencia previa activa (${licenciaActiva.type}) de prueba: el periodo de prueba no se acumula."
                    diasRestantes > 0 ->
                        "Renovación anticipada (${licenciaActiva.type}): se sumarán $diasRestantes días restantes a la nueva licencia."
                    else ->
                        "Renovación de licencia activa (${licenciaActiva.type})."
                }
                return EvaluacionHistorial.ActivaMismoTipo(
                    activa = licenciaActiva,
                    segundosRestantes = segundosRestantes,
                    diasRestantes = diasRestantes,
                    aviso = aviso,
                )
            } else {
                val aviso = when {
                    req.type == TipoLicencia.PERPETUA ->
                        "Licencia previa activa (${licenciaActiva.type}): cambio a Perpetua (licencia permanente sin vencimiento)."
                    esPrueba ->
                        "Licencia previa activa es de prueba: el periodo de prueba no se acumula."
                    diasRestantes > 0 ->
                        "Cambio de plan (${licenciaActiva.type} → ${req.type}): se sumarán $diasRestantes días restantes a la nueva licencia."
                    else ->
                        "Licencia previa activa (${licenciaActiva.type}) actualizada a ${req.type}."
                }
                return EvaluacionHistorial.ActivaDiferente(
                    activa = licenciaActiva,
                    segundosRestantes = segundosRestantes,
                    diasRestantes = diasRestantes,
                    aviso = aviso,
                )
            }
        }

        // Si no tiene activa, revisar si tiene alguna vencida y no revocada.
        val vencida = previas.filter { lic ->
            if (lic.status == EstadoLicencia.REVOCADA ||
                lic.status == EstadoLicencia.PERPETUA ||
                lic.type == TipoLicencia.PERPETUA
            ) {
                false
            } else if (lic.status == EstadoLicencia.VENCIDA) {
                true
            } else if (lic.status == EstadoLicencia.ACTIVA) {
                Instant.parse(checkNotNull(lic.expiresAtIso)).epochSecond <= ahoraSec
            } else {
                false
            }
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

    /**
     * Valida en seco una solicitud SPVI2 de ampliación de apps secundarias (`amplia != null`)
     * y devuelve el desglose exacto a cobrar, o `null` si no es ampliación.
     */
    suspend fun evaluarAmpliacionSpvi(sol: SolicitudSpviV2): Outcome<String?> {
        val idAmplia = sol.amplia ?: return Outcome.Ok(null)
        val ahoraSec = try {
            Instant.parse(clock.nowIso()).epochSecond
        } catch (_: Exception) {
            return Outcome.Err(AppError.ClockFailure)
        }
        val prev = try {
            licencias.getById(idAmplia.trim())
                ?: licencias.all().firstOrNull { it.id.equals(idAmplia.trim(), ignoreCase = true) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Outcome.Err(AppError.RegistryFailure)
        } ?: return Outcome.Err(AppError.AmpliacionInvalida)

        val prevSec = (prev.secundarias ?: 0).coerceAtLeast(0)
        val prevVenceSec = try {
            prev.expiresAtIso?.let { Instant.parse(it).epochSecond }
        } catch (_: Exception) {
            return Outcome.Err(AppError.AmpliacionInvalida)
        }
        val esPerpetua = prev.type == TipoLicencia.PERPETUA || prev.status == EstadoLicencia.PERPETUA
        val vigente = prev.status != EstadoLicencia.REVOCADA &&
            prev.status != EstadoLicencia.VENCIDA &&
            (esPerpetua || (prevVenceSec != null && prevVenceSec > ahoraSec))
        if (!vigente ||
            prev.deviceId != sol.deviceId ||
            prev.type != sol.tipo ||
            sol.secundarias !in (prevSec + 1)..10
        ) {
            return Outcome.Err(AppError.AmpliacionInvalida)
        }
        return Outcome.Ok(PrecioTabla.desgloseAmpliacion(prev.type, prevSec, sol.secundarias))
    }

    /**
     * Valida en seco una solicitud SPVI2 de recuperación de licencia (`recupera != null`)
     * y devuelve el mensaje descriptivo, o `null` si no es recuperación.
     * Verifica que la licencia previa exista, no esté revocada ni vencida, y pertenezca al mismo carné de identidad.
     */
    suspend fun evaluarRecuperacionSpvi(sol: SolicitudSpviV2): Outcome<String?> {
        val idRecupera = sol.recupera ?: return Outcome.Ok(null)
        val ahoraSec = try {
            Instant.parse(clock.nowIso()).epochSecond
        } catch (_: Exception) {
            return Outcome.Err(AppError.ClockFailure)
        }
        val prev = try {
            licencias.getById(idRecupera.trim())
                ?: licencias.all().firstOrNull { it.id.equals(idRecupera.trim(), ignoreCase = true) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Outcome.Err(AppError.RegistryFailure)
        } ?: return Outcome.Err(AppError.RecuperacionInvalida)

        if (prev.status == EstadoLicencia.REVOCADA || prev.status == EstadoLicencia.VENCIDA) {
            return Outcome.Err(AppError.RecuperacionInvalida)
        }
        val esPerpetua = prev.type == TipoLicencia.PERPETUA || prev.status == EstadoLicencia.PERPETUA
        val prevVenceSec = try {
            prev.expiresAtIso?.let { Instant.parse(it).epochSecond }
        } catch (_: Exception) {
            null
        }
        if (!esPerpetua && (prevVenceSec == null || prevVenceSec <= ahoraSec)) {
            return Outcome.Err(AppError.RecuperacionInvalida)
        }
        if (!prev.nationalId.trim().equals(sol.ci.trim(), ignoreCase = true)) {
            return Outcome.Err(AppError.RecuperacionInvalida)
        }

        val tipoNombre = prev.type.name.lowercase().replaceFirstChar { it.uppercase() }
        val aviso = "Recuperación de licencia ($tipoNombre): transfiere a este dispositivo y revoca la licencia previa (${prev.id}). Gratuita."
        return Outcome.Ok(aviso)
    }
}
