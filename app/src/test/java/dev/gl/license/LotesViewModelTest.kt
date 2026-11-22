package dev.gl.license

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.gl.license.data.local.GlDatabase
import dev.gl.license.domain.model.RedeemApps
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.repository.SecureClock
import dev.gl.license.domain.usecase.CrearLoteCanjeUseCase
import dev.gl.license.presentation.lotes.LotesViewModel
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La pantalla Lotes solo puede acuñar lotes para las apps de Shalev. Acuñar de
 * verdad requiere el Keystore de Android (no existe en JVM); por eso aquí se
 * comprueba el selector, el aviso y el rechazo previo a la firma, y el lote con
 * firma inyectada se cubre en [RedeemAppsTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LotesViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: GlDatabase

    private val clock = object : SecureClock {
        override fun nowEpochMillis(): Long = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
        override fun nowIso(): String = "2026-11-10T12:00:00Z"
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, GlDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun vm() = LotesViewModel(
        db,
        CrearLoteCanjeUseCase(clock),
        Json { ignoreUnknownKeys = false; encodeDefaults = true },
    )

    @Test
    fun elSelectorOfreceLasTresAppsYElAvisoNombraLaElegida() {
        val model = vm()

        assertThat(model.appOptions).containsExactly("DSHALEV", "SHALEV-ANDROID", "SHALEV-IOS").inOrder()
        for (appId in model.appOptions) {
            assertThat(model.appNotice(appId)).contains("solo sirve")
            assertThat(model.appNotice(appId)).contains(appId)
        }
        assertThat(model.appOptions).doesNotContain("SPVI")
    }

    @Test
    fun unaAppFueraDeLaListaSeRechazaAntesDeAcunar() = runTest {
        val model = vm()

        model.create("SPVI", 1, TipoLicencia.MENSUAL, null)

        assertThat(model.message).isEqualTo("Elige una app de la lista.")
        assertThat(model.busy).isFalse()
        assertThat(RedeemApps.isAllowed("SPVI")).isFalse()
    }
}
