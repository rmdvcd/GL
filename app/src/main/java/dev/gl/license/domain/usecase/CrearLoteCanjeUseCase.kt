package dev.gl.license.domain.usecase

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.LicenseCodeGenerator
import dev.gl.license.domain.model.REDEEM_BATCH_MAX_ENTRIES
import dev.gl.license.domain.model.RedeemBatch
import dev.gl.license.domain.model.RedeemBatchCanonical
import dev.gl.license.domain.model.RedeemBatchEntry
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.repository.SecureClock
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.KeystoreManager
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Acuña un lote de codigos de 16 caracteres firmado con la clave ECDSA de GL.
 *
 * El servicio de canje verifica la firma con la publica `gl-sign-v1` que el
 * operador fije en su configuracion. La clave privada nunca sale del Keystore.
 *
 * No hay UI todavia: la exportacion del JSON se conecta en una pantalla de
 * operador cuando se autorice el despliegue. Ver `services/redeem/README.md`.
 */
class CrearLoteCanjeUseCase @Inject constructor(
    private val clock: SecureClock,
) {
    /**
     * `signer` permite probar el caso de uso en JVM sin Android Keystore.
     * En produccion se usa la firma del Keystore de GL.
     */
    operator fun invoke(
        appId: String,
        cantidad: Int,
        tipo: TipoLicencia,
        secundarias: Int = 0,
        redeemBeforeIso: String? = null,
        batchId: String = UUID.randomUUID().toString(),
        codigoFactory: () -> String = { LicenseCodeGenerator.generate() },
        signer: (ByteArray) -> ByteArray = { bytes ->
            CryptoEngine.sign(KeystoreManager.signingPair().private, bytes)
        },
    ): Outcome<RedeemBatch> {
        if (!RedeemBatchCanonical.isValidAppId(appId)) return Outcome.Err(AppError.InvalidPayload)
        if (cantidad < 1 || cantidad > REDEEM_BATCH_MAX_ENTRIES) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        if (secundarias !in 0..10) return Outcome.Err(AppError.InvalidPayload)
        if (!RedeemBatchCanonical.isValidBatchId(batchId)) return Outcome.Err(AppError.InvalidPayload)

        val now = try {
            Instant.parse(clock.nowIso())
        } catch (_: Exception) {
            return Outcome.Err(AppError.ClockFailure)
        }
        if (redeemBeforeIso != null) {
            val deadline = try {
                Instant.parse(redeemBeforeIso)
            } catch (_: Exception) {
                return Outcome.Err(AppError.InvalidPayload)
            }
            if (!deadline.isAfter(now)) return Outcome.Err(AppError.InvalidPayload)
        }

        val codigos = LinkedHashSet<String>()
        val maxIntentos = cantidad * 10 + 100
        var intentos = 0
        while (codigos.size < cantidad && intentos < maxIntentos) {
            intentos += 1
            val normalizado = RedeemBatchCanonical.normalize(codigoFactory()) ?: continue
            codigos += normalizado
        }
        if (codigos.size != cantidad) return Outcome.Err(AppError.CryptoFailure)

        val entries = codigos.map { codigo ->
            RedeemBatchEntry(
                code = RedeemBatchCanonical.format(codigo),
                type = tipo,
                secundarias = secundarias,
                redeemBefore = redeemBeforeIso,
            )
        }
        val unsigned = RedeemBatch(
            batchId = batchId,
            createdAt = now.toString(),
            appId = appId,
            entries = entries,
            signature = "",
        )
        val bytes = try {
            RedeemBatchCanonical.signingBytes(unsigned)
        } catch (_: Exception) {
            return Outcome.Err(AppError.InvalidPayload)
        }
        val firma = try {
            signer(bytes)
        } catch (_: Exception) {
            return Outcome.Err(AppError.CryptoFailure)
        }
        if (firma.isEmpty()) return Outcome.Err(AppError.CryptoFailure)
        return Outcome.Ok(unsigned.copy(signature = CryptoEngine.b64(firma)))
    }
}
