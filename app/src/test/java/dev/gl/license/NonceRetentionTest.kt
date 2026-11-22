package dev.gl.license

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.data.local.GlDatabase
import dev.gl.license.data.local.LicenseRepositoryImpl
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.domain.usecase.RequestFreshness
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Retención real en Room/SQLite: no existe ninguna limpieza de
 * `consumed_nonces` ni de `tx_reservations` por antigüedad, así que ampliar la
 * ventana no reabre una solicitud ya emitida. Solo el operador puede liberar
 * una reserva de transacción (`releaseReservation`, por licencia, nunca por fecha).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NonceRetentionTest {
    private lateinit var db: GlDatabase
    private lateinit var repo: LicenseRepositoryImpl

    private val emitida = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, GlDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = LicenseRepositoryImpl(db.licenseDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun lic(id: String, issuedAt: Instant = emitida, nonce: String = "AAAAAAAAAAAAAAAAAAAAAA==") =
        Licencia(
            version = CONTRACT_VERSION,
            id = id,
            firstName = "Ana",
            lastName = "Perez",
            nationalId = "85010112345",
            channel = ViaSolicitud.WHATSAPP,
            phone = "+5355512345",
            appName = "SPVI",
            deviceId = "SPVI:abc-12345",
            type = TipoLicencia.MENSUAL,
            requestedAtIso = emitida.toString(),
            issuedAtIso = issuedAt.toString(),
            expiresAtIso = issuedAt.plus(Duration.ofDays(30)).toString(),
            status = EstadoLicencia.ACTIVA,
            secundarias = 0,
            nonce = nonce,
            txNumber = "TX-1",
        )

    @Test
    fun ventanaUnicaDeSieteDiasYSinLimpiezaDeclarada() {
        assertThat(RequestFreshness.maxAge).isEqualTo(Duration.ofDays(7))
        assertThat(RequestFreshness.maxFutureSkew).isEqualTo(Duration.ofHours(2))
        assertThat(RequestFreshness.maxAge.toHours()).isEqualTo(168L)
    }

    @Test
    fun nonceConsumidoSigueRechazandoseASieteDiasMenosUnSegundo() = runTest {
        val casiSieteDias = emitida.plus(RequestFreshness.maxAge).minusSeconds(1)
        assertThat(RequestFreshness.accepts(emitida, casiSieteDias)).isTrue()

        assertThat(repo.issue(lic("primera"), "nonce-1234567890abcd")).isInstanceOf(Outcome.Ok::class.java)

        val reintento = repo.issue(
            lic("segunda", issuedAt = casiSieteDias, nonce = "otro-nonce-1234567890ab"),
            "nonce-1234567890abcd",
        )

        assertThat((reintento as Outcome.Err).error).isEqualTo(AppError.SolicitudYaProcesada)
        assertThat(repo.getById("segunda")).isNull()
    }

    @Test
    fun nonceYReservaDeTransaccionSobrevivenCatorceDias() = runTest {
        repo.issue(lic("primera"), "nonce-abcdefghijklmn")

        val pasadosCatorceDias = emitida.plus(Duration.ofDays(14))
        assertThat(RequestFreshness.accepts(emitida, pasadosCatorceDias)).isFalse()

        // Sin limpieza por antigüedad: el nonce y la reserva siguen en disco.
        assertThat(db.licenseDao().countByTxNumber("tx-1")).isEqualTo(1)
        val reintento = repo.issue(
            lic("segunda", issuedAt = pasadosCatorceDias, nonce = "otro-nonce-1234567890ab"),
            "nonce-abcdefghijklmn",
        )
        assertThat((reintento as Outcome.Err).error).isEqualTo(AppError.SolicitudYaProcesada)
        assertThat(repo.isTransactionUsed("tx-1")).isTrue()
    }
}
