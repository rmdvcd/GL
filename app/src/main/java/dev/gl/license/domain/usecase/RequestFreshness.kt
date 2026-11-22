package dev.gl.license.domain.usecase

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant

/**
 * Ventana única de frescura para todas las solicitudes: envelope v1 y SPVIR1 (v=2).
 *
 * Ampliarla es seguro porque el nonce se consume de forma atómica al emitir
 * (`consumed_nonces`) y la licencia queda ligada a la `devicePub` de la
 * solicitud: reenviar más tarde una solicitud no utilizada no reabre una
 * emisión ya hecha.
 */
object RequestFreshness {
    val maxAge: Duration = Duration.ofDays(7)
    val maxFutureSkew: Duration = Duration.ofHours(2)

    /** Ambos extremos se aceptan. Un instante donde la ventana no es representable se rechaza. */
    fun accepts(requestedAt: Instant, now: Instant): Boolean = try {
        !requestedAt.isBefore(now.minus(maxAge)) && !requestedAt.isAfter(now.plus(maxFutureSkew))
    } catch (_: DateTimeException) {
        false
    } catch (_: ArithmeticException) {
        false
    }
}
