package dev.gl.license

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.gl.license.presentation.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El módulo de autenticación fue eliminado: ya no hay pantalla de PIN ni gate de
 * sesión, así que la app entra directa al contenido.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun generatorVisibleOnLaunch() {
        compose.onNodeWithText("Generar licencia").assertExists()
    }

    @Test
    fun noLockScreenOnLaunch() {
        compose.onNodeWithText("Crea un PIN de 8 dígitos").assertDoesNotExist()
        compose.onNodeWithText("GL bloqueado").assertDoesNotExist()
    }
}
