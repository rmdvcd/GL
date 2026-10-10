package dev.gl.license.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Claves en Android Keystore. StrongBox (API 28+) se intenta primero;
 * si el SoC no lo tiene, cae a TEE. Ningún material de clave sale en claro
 * del Keystore cuando el hardware lo permite.
 */
object KeystoreManager {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val ALIAS_MASTER_AES = "gl.master.aes.v1"
    const val ALIAS_SIGN_EC = "gl.sign.ec.p256.v1"
    const val ALIAS_ECDH = "gl.ecdh.p256.v1"
    const val ALIAS_BIOMETRIC = "gl.bio.aes.v1"

    private fun ks(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun getOrCreateAes(alias: String = ALIAS_MASTER_AES, requireAuth: Boolean = false): SecretKey {
        val store = ks()
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return generateAes(alias, requireAuth, tryStrongBox = true)
    }

    private fun generateAes(alias: String, requireAuth: Boolean, tryStrongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(requireAuth)
        applyCommon(builder, tryStrongBox)
        if (requireAuth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        return try {
            gen.init(builder.build())
            gen.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            generateAes(alias, requireAuth, tryStrongBox = false)
        } catch (_: Exception) {
            if (tryStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                generateAes(alias, requireAuth, tryStrongBox = false)
            } else {
                error("keystore-aes")
            }
        }
    }

    fun getOrCreateEc(alias: String, purposes: Int): KeyPair {
        val store = ks()
        val entry = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (entry != null) {
            return KeyPair(entry.certificate.publicKey, entry.privateKey)
        }
        return generateEc(alias, purposes, tryStrongBox = true)
    }

    private fun generateEc(alias: String, purposes: Int, tryStrongBox: Boolean): KeyPair {
        val builder = KeyGenParameterSpec.Builder(alias, purposes)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        applyCommon(builder, tryStrongBox)
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        return try {
            kpg.initialize(builder.build())
            kpg.generateKeyPair()
        } catch (_: StrongBoxUnavailableException) {
            generateEc(alias, purposes, tryStrongBox = false)
        } catch (_: Exception) {
            if (tryStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                generateEc(alias, purposes, tryStrongBox = false)
            } else {
                error("keystore-ec")
            }
        }
    }

    fun signingPair(): KeyPair = getOrCreateEc(
        ALIAS_SIGN_EC,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
    )

    fun ecdhPair(): KeyPair {
        val purposes = if (Build.VERSION.SDK_INT >= 31) {
            KeyProperties.PURPOSE_AGREE_KEY
        } else {
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        }
        return getOrCreateEc(ALIAS_ECDH, purposes)
    }

    fun biometricAes(): SecretKey = getOrCreateAes(ALIAS_BIOMETRIC, requireAuth = true)

    private fun applyCommon(builder: KeyGenParameterSpec.Builder, tryStrongBox: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
            if (tryStrongBox) builder.setIsStrongBoxBacked(true)
        }
    }
}
