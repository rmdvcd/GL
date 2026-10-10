package dev.gl.license

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.KeystoreManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.SecretKey

/**
 * Regresión: AES-256-GCM con clave real de AndroidKeyStore.
 *
 * Estas claves se crean con `setRandomizedEncryptionRequired(true)`, así que el
 * Keystore rechaza un IV aportado por el llamante con
 * `InvalidAlgorithmParameterException: Caller-provided IV not permitted`.
 * El bugmatizaba `ProvideModule.db` (Di.kt:63) al crear la base de datos por
 * primera vez, y ninguna suite lo veía: los tests JVM usan claves JCE/BouncyCastle
 * (que sí admiten IV del llamante) y los instrumentados nunca llegaban al
 * contenido autenticado.
 *
 * Solo corre en dispositivo/emulador: el Keystore no existe en la JVM.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCryptoTest {

    private companion object {
        // Alias de pruebas: no tocar ALIAS_MASTER_AES ni ninguna clave de producción.
        const val ALIAS = "gl.test.aes.v1"
        const val AAD = "gl-db-v1"
    }

    private lateinit var key: SecretKey

    private fun keystore(): KeyStore =
        KeyStore.getInstance(KeystoreManager.ANDROID_KEYSTORE).apply { load(null) }

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        key = KeystoreManager.getOrCreateAes(ALIAS)
        // No se puede comprobar por key.format (devuelve null en Android); se verifica
        // que la clave está realmente en el AndroidKeyStore.
        assertTrue(
            "la clave debe existir en el AndroidKeyStore para que el test signifique algo",
            keystore().containsAlias(ALIAS),
        )
    }

    @After
    fun tearDown() {
        runCatching { keystore().deleteEntry(ALIAS) }
    }

    @Test
    fun encryptsWithKeystoreGeneratedIv() {
        val pt = "frase-de-secreto".toByteArray()
        val (iv, ct, tag) = CryptoEngine.aesGcmEncrypt(key, pt, aad = AAD.toByteArray())

        assertEquals(CryptoEngine.GCM_IV_LEN, iv.size)
        assertEquals(CryptoEngine.GCM_TAG_LEN, tag.size)
        assertEquals(pt.size, ct.size)

        val out = CryptoEngine.aesGcmDecrypt(key, iv, ct, tag, aad = AAD.toByteArray())
        assertArrayEquals(pt, out)
    }

    /**
     * El corazón del fix: el IV del llamante debe ser ignorado para claves del
     * Keystore. Antes de arreglarlo esto lanzaba
     * `Caller-provided IV not permitted`.
     */
    @Test
    fun ignoresCallerProvidedIvForKeystoreKeys() {
        val pt = "otro-secreto".toByteArray()
        val callerIv = CryptoEngine.randomBytes(CryptoEngine.GCM_IV_LEN)

        val (iv, ct, tag) = CryptoEngine.aesGcmEncrypt(
            key = key,
            plaintext = pt,
            aad = AAD.toByteArray(),
            iv = callerIv,
        )

        assertFalse("el IV debe generarlo el Keystore, no la app", callerIv.contentEquals(iv))
        assertArrayEquals(pt, CryptoEngine.aesGcmDecrypt(key, iv, ct, tag, aad = AAD.toByteArray()))
    }

    /**
     * `setRandomizedEncryptionRequired(true)` implica IV aleatorio: dos cifrados
     * del mismo texto no pueden coincidir, aunque se pase el mismo IV.
     */
    @Test
    fun samePlaintextTwiceProducesDifferentCiphertext() {
        val pt = "mismo-plano".toByteArray()
        val aad = AAD.toByteArray()

        val (iv1, ct1, _) = CryptoEngine.aesGcmEncrypt(key, pt, aad = aad)
        val (iv2, ct2, _) = CryptoEngine.aesGcmEncrypt(key, pt, aad = aad)

        assertFalse(iv1.contentEquals(iv2))
        assertFalse(ct1.contentEquals(ct2))
    }

    /** El AAD sigue atando el descifrado: mismo ciphertext, AAD distinto, falla. */
    @Test
    fun aadStillBindsOnKeystoreCiphertext() {
        val (iv, ct, tag) = CryptoEngine.aesGcmEncrypt(key, "atado".toByteArray(), aad = AAD.toByteArray())
        val ex = runCatching {
            CryptoEngine.aesGcmDecrypt(key, iv, ct, tag, aad = "otro-aad".toByteArray())
        }.exceptionOrNull()
        assertNotEquals(null, ex)
    }
}
