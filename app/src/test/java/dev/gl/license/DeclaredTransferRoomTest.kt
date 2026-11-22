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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** La precarga declarada usa la misma reserva atómica que la emisión manual (clave normalizada). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeclaredTransferRoomTest {
    private lateinit var db: GlDatabase
    private lateinit var repo: LicenseRepositoryImpl

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, GlDatabase::class.java).allowMainThreadQueries().build()
        repo = LicenseRepositoryImpl(db.licenseDao())
    }

    @After
    fun tearDown() = db.close()

    private fun lic(id: String, tx: String?) = Licencia(
        version = CONTRACT_VERSION, id = id, firstName = "Ana", lastName = "Perez",
        nationalId = "85010112345", channel = ViaSolicitud.SMS, phone = "+5355512345",
        appName = "SPVI", deviceId = "SPVI:abc-12345", type = TipoLicencia.MENSUAL,
        requestedAtIso = "2026-01-01T00:00:00Z", issuedAtIso = "2026-01-01T00:00:00Z",
        expiresAtIso = "2026-01-31T00:00:00Z", status = EstadoLicencia.ACTIVA,
        secundarias = 0, nonce = "AAAAAAAAAAAAAAAAAAAAAA==", txNumber = tx,
    )

    @Test
    fun transferenciaLibreNoTieneDuena() = runTest {
        assertThat(repo.transactionOwner("TX-100")).isNull()
        assertThat(repo.isTransactionUsed("TX-100")).isFalse()
    }

    @Test
    fun transferenciaDeclaradaYRepetidaQuedaBloqueadaPorLaReserva() = runTest {
        // El operador acepta la precarga tal cual.
        assertThat(repo.issue(lic("a", " TX-100 "), "nonce-aaaaaaaaaaaaaaaa")).isInstanceOf(Outcome.Ok::class.java)

        assertThat(repo.transactionOwner("tx-100")).isEqualTo("a")
        // La normalización documentada recorta, pasa a minúsculas y quita espacios internos.
        assertThat(repo.transactionOwner(" TX - 100 ")).isEqualTo("a")
        // El guion sí forma parte del número: «TX100» es otra transferencia distinta.
        assertThat(repo.transactionOwner("TX100")).isNull()

        // Otra solicitud (aunque venga del cliente con el mismo tx declarado) no puede reservarla.
        val segunda = repo.issue(lic("b", "tx-100"), "nonce-bbbbbbbbbbbbbbbb")
        assertThat((segunda as Outcome.Err).error).isEqualTo(AppError.TransactionAlreadyUsed("tx-100"))
        assertThat(repo.getById("b")).isNull()
    }

    @Test
    fun elOperadorPuedeEmitirConOtraTransferencia() = runTest {
        repo.issue(lic("a", "TX-100"), "nonce-aaaaaaaaaaaaaaaa")

        val segunda = repo.issue(lic("b", "TX-200"), "nonce-bbbbbbbbbbbbbbbb")

        assertThat(segunda).isInstanceOf(Outcome.Ok::class.java)
        assertThat(repo.transactionOwner("tx-200")).isEqualTo("b")
    }
}
