package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.RedeemApps
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.repository.SecureClock
import dev.gl.license.domain.usecase.CrearLoteCanjeUseCase
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import org.junit.Test

/** El selector de Lotes ofrece solo las apps de Shalev y el lote queda atado a la elegida. */
class RedeemAppsTest {
    private val clock = object : SecureClock {
        override fun nowEpochMillis(): Long = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
        override fun nowIso(): String = "2026-11-10T12:00:00Z"
    }

    private val keystore = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun mint(appId: String) = CrearLoteCanjeUseCase(clock)(
        appId = appId,
        cantidad = 1,
        tipo = TipoLicencia.MENSUAL,
        batchId = "11111111-1111-4111-8111-111111111111",
        signer = { bytes ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keystore.private); update(bytes); sign()
            }
        },
    )

    @Test
    fun elSelectorOfreceExactamenteLasTresAppsDeShalev() {
        assertThat(RedeemApps.allowed).containsExactly("DSHALEV", "SHALEV-ANDROID", "SHALEV-IOS").inOrder()
        assertThat(RedeemApps.isAllowed("SPVI")).isFalse()
        assertThat(RedeemApps.isAllowed("shalev-android")).isFalse()
    }

    @Test
    fun cadaAppAceptaSuLoteYElAvisoNombraLaApp() {
        for (appId in RedeemApps.allowed) {
            val lote = mint(appId) as Outcome.Ok
            assertThat(lote.value.appId).isEqualTo(appId)
            assertThat(lote.value.entries).hasSize(1)
            assertThat(RedeemApps.notice(appId)).contains(appId)
            assertThat(RedeemApps.notice(appId)).contains("solo sirve")
        }
    }

    @Test
    fun unAppIdFueraDeLaListaNoEsSeleccionable() {
        // El acuñador solo valida el formato del appId; quien limita a las tres
        // apps de Shalev es la pantalla (ver LotesViewModelTest).
        for (appId in listOf("SPVI", "shalev-ios", "OTRA", "")) {
            assertThat(RedeemApps.isAllowed(appId)).isFalse()
        }
    }
}
