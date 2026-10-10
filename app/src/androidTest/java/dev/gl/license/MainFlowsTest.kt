package dev.gl.license

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.gl.license.presentation.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flujos en dispositivo/emulador. Sin red.
 *
 * Ya no hay pantalla de PIN: se verifica que la app entra directa al contenido y
 * que la navegación entre pestañas funciona.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainFlowsTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun appOpensOnGeneratorWithoutAuth() {
        compose.onNodeWithText("Generar licencia").assertIsDisplayed()
        compose.onNodeWithText("Crea un PIN de 8 dígitos").assertDoesNotExist()
    }

    @Test
    fun bottomBarSwitchesBetweenGeneratorAndRegistry() {
        compose.onNodeWithContentDescription("Registro").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sin licencias").assertIsDisplayed()

        compose.onNodeWithContentDescription("Generador").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Generar licencia").assertIsDisplayed()
    }
}
