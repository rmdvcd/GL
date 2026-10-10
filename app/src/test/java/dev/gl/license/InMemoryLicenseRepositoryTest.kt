package dev.gl.license

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InMemoryLicenseRepositoryTest {
    private lateinit var db: GlDatabase
    private lateinit var repo: LicenseRepositoryImpl

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

    private fun lic(id: String, issued: String) = Licencia(
        version = CONTRACT_VERSION,
        id = id,
        firstName = "Ana",
        lastName = "Perez",
        nationalId = "85010112345",
        channel = ViaSolicitud.SMS,
        phone = "+5355512345",
        appName = "MiApp",
        deviceId = "device-abc-12345",
        type = TipoLicencia.ANUAL,
        requestedAtIso = issued,
        issuedAtIso = issued,
        expiresAtIso = "2027-01-01T00:00:00Z",
        status = EstadoLicencia.ACTIVA,
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
    )

    @Test
    fun upsertGetAndMergeKeepsNewest() = runTest {
        repo.upsert(lic("a", "2026-01-01T00:00:00Z"))
        assertThat(repo.getById("a")?.firstName).isEqualTo("Ana")
        val n = repo.merge(
            listOf(
                lic("a", "2026-02-01T00:00:00Z"),
                lic("a", "2025-01-01T00:00:00Z"),
                lic("b", "2026-03-01T00:00:00Z"),
            )
        ) as Outcome.Ok
        assertThat(n.value).isEqualTo(2)
        assertThat(repo.getById("a")?.issuedAtIso).isEqualTo("2026-02-01T00:00:00Z")
        assertThat(repo.all().map { it.id }).containsExactly("a", "b")
    }

    @Test
    fun upsertKeepsSecundariasAndPrecioCobrado() = runTest {
        val licencia = lic("s", "2026-01-01T00:00:00Z").copy(
            appName = "SPVI",
            deviceId = "SPVI:abc-12345",
            secundarias = 2,
            precioCobrado = 8_000,
        )

        repo.upsert(licencia)

        val guardada = repo.getById("s")
        assertThat(guardada?.secundarias).isEqualTo(2)
        assertThat(guardada?.precioCobrado).isEqualTo(8_000)
    }
}
