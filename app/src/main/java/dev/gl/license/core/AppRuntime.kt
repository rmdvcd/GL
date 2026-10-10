package dev.gl.license.core

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Inicialización de runtime que depende del proceso, no de la clase Application.
 *
 * Motivo de existir: `GlApplication.onCreate()` no se ejecuta en los tests instrumentados,
 * porque `HiltTestRunner` sustituye la Application por `HiltTestApplication`. Por eso
 * `System.loadLibrary("sqlcipher")` llegaba a los instrumentados sin ejecutarse y cualquier
 * apertura de Room reventaba con
 * `UnsatisfiedLinkError: No implementation found for ...SQLiteConnection.nativeOpen`.
 *
 * El AAR `net.zetetic:sqlcipher-android` NO autocarga su `libsqlcipher.so`: la app es
 * responsable de cargarla. Cualquier punto que use SQLCipher debe garantizar que ya está
 * cargada, sin depender de quién sea la Application.
 *
 * `ensureInitialized()` es idempotente y segura desde varios hilos.
 */
object AppRuntime {
    private const val SQLCIPHER_LIB = "sqlcipher"

    @Volatile
    private var initialized = false

    /**
     * Carga la biblioteca nativa de SQLCipher e instala BouncyCastle como proveedor.
     * Llamar tantas veces como haga falta; solo la primera hace trabajo real.
     */
    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            System.loadLibrary(SQLCIPHER_LIB)
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
            initialized = true
        }
    }
}
