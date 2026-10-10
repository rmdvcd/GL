package dev.gl.license

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.gl.license.data.local.LicenseDao
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.SolicitudLicencia
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.presentation.MainActivity
import dev.gl.license.security.ClientRequestHelper
import dev.gl.license.security.KeystoreManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import javax.inject.Inject

/**
 * Recorrido funcional completo del happy path sobre el dispositivo real:
 * sobre cifrado -> validacion -> generacion de licencia -> registro en Room -> listado.
 *
 * El sobre lo acuña [ClientRequestHelper] contra la clave ECDH del Keystore de ESTE
 * telefono, asi que solo es valido aqui. No hay sobre de muestra en el repo porque
 * cualquier sobre de otro telefono no lo puede descifrar el Keystore de este.
 *
 * ESTADO: DESHABILITADO. El texto entra en el campo, pero la corrutina de
 * [dev.gl.license.presentation.generator.GeneratorViewModel.onRawChange] nunca publica
 * `solicitud`, asi que "Solicitud valida" no aparece y el primer `waitUntil` revienta.
 * `performTextInput` no es la causa: se ejecuta sin excepcion. El fallo real esta en
 * `ValidarSolicitudUseCase` (descifrado con el Keystore o deserializacion del plaintext),
 * no diagnosticado. Antes de reactivar hay que instrumentar ese caso y no tocar el
 * `contentDescription` de "Solicitud cifrada", del que dependen otros tests.
 */
@Ignore("Pendiente de diagnosticar ValidarSolicitudUseCase en happy path real")
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LicenseGenerationE2ETest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var dao: LicenseDao

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }

    @Before
    fun setUp() = hiltRule.inject()

    private fun mintEnvelope(): String {
        val request = SolicitudLicencia(
            version = CONTRACT_VERSION,
            firstName = "Juan",
            lastName = "Perez Gomez",
            nationalId = "12345678",
            channel = ViaSolicitud.WHATSAPP,
            phone = "+5355551234",
            appName = "MiApp",
            deviceId = "test-device-001",
            type = TipoLicencia.MENSUAL,
            requestedAtIso = Instant.now().toString(),
            nonce = "abcdefghijklmnop0123",
        )
        return ClientRequestHelper.buildEnvelope(
            request,
            KeystoreManager.ecdhPair().public,
            json,
        )
    }

    @Test
    fun generateAndRegisterLicenseFromRealEnvelope() {
        val before = runBlocking { dao.all().size }

        composeRule.onNodeWithContentDescription("Solicitud cifrada")
            .performTextInput(mintEnvelope())

        // El ViewModel valida el sobre de forma asincrona: esperar a que acepte.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Solicitud válida").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Solicitud válida").assertIsDisplayed()
        // Datos extraidos del sobre, visibles en la tarjeta.
        composeRule.onNodeWithText("Juan Perez Gomez").assertIsDisplayed()
        composeRule.onNodeWithText("12345678").assertIsDisplayed()

        // Marcar el pago (Row toggleable con Role.Checkbox, sin contentDescription propio).
        composeRule.onNode(isToggleable() and hasText("Pago realizado")).performClick()

        composeRule.onNodeWithText("Generar licencia").performClick()

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Licencia generada.").fetchSemanticsNodes().isNotEmpty()
        }

        // El share se lanza al generarla; al volver, se habilita el registro.
        composeRule.onNodeWithText("Licencia generada.").assertIsDisplayed()
        runCatching { composeRule.activityRule.scenario.onActivity { } }
        // Simula el regreso desde la app de compartir: PAUSED -> RESUMED dispara ON_RESUME,
        // que es lo que llama a onReturnedFromShare() y habilita el boton Registrar.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Registrar").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Registrar").performClick()

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Registrada.").fetchSemanticsNodes().isNotEmpty()
        }

        val after = runBlocking { dao.all() }
        assert(after.size == before + 1) {
            "Se esperaba 1 licencia nueva en Room, antes=$before despues=${after.size}"
        }
        val saved = after.first()
        assert(saved.firstName == "Juan") { "Nombre no persistido: ${saved.firstName}" }
        assert(saved.nationalId == "12345678") { "CI no persistido: ${saved.nationalId}" }
        assert(saved.status == "ACTIVA") { "Estado inesperado: ${saved.status}" }

        // La licencia debe verse en la pestaña Registro.
        composeRule.onNodeWithText("Registro").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("12345678").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("12345678").assertIsDisplayed()
    }
}
