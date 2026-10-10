package dev.gl.license.core

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gl.license.data.local.ContactMethodDao
import dev.gl.license.data.local.ContactMethodRepositoryImpl
import dev.gl.license.data.local.GlDatabase
import dev.gl.license.data.local.GlMigrations
import dev.gl.license.data.local.LicenseDao
import dev.gl.license.data.local.LicenseRepositoryImpl
import dev.gl.license.data.local.RegistroRepositoryImpl
import dev.gl.license.domain.repository.ContactMethodRepository
import dev.gl.license.domain.repository.LicenciaRepository
import dev.gl.license.domain.repository.SeguridadRepository
import dev.gl.license.domain.repository.SpviPuerta
import dev.gl.license.domain.repository.RegistroRepository
import dev.gl.license.domain.repository.SecureClock
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.CryptoGatewayImpl
import dev.gl.license.security.KeystoreManager
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.crypto.SecretKey
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BindModule {
    @Binds @Singleton abstract fun licenseRepo(i: LicenseRepositoryImpl): LicenciaRepository
    @Binds @Singleton abstract fun registroRepo(i: RegistroRepositoryImpl): RegistroRepository
    @Binds @Singleton abstract fun crypto(i: CryptoGatewayImpl): SeguridadRepository
    @Binds @Singleton abstract fun spvi(i: CryptoGatewayImpl): SpviPuerta
    @Binds @Singleton abstract fun clock(i: SecureClockImpl): SecureClock
    @Binds @Singleton abstract fun contactRepo(i: ContactMethodRepositoryImpl): ContactMethodRepository
}

@Module
@InstallIn(SingletonComponent::class)
object ProvideModule {
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }

    @Provides
    @Singleton
    fun db(@ApplicationContext ctx: Context): GlDatabase {
        // Garantiza libsqlcipher.so cargada antes de tocar SQLCipher. Necesario porque en
        // los instrumentados la Application real no corre (ver AppRuntime).
        AppRuntime.ensureInitialized()
        val master: SecretKey = KeystoreManager.getOrCreateAes()
        val prefs = ctx.getSharedPreferences("gl_db_meta", Context.MODE_PRIVATE)
        val passphrase: ByteArray = if (prefs.contains("w_iv")) {
            val iv = android.util.Base64.decode(prefs.getString("w_iv", "")!!, android.util.Base64.NO_WRAP)
            val ct = android.util.Base64.decode(prefs.getString("w_ct", "")!!, android.util.Base64.NO_WRAP)
            val tag = android.util.Base64.decode(prefs.getString("w_tag", "")!!, android.util.Base64.NO_WRAP)
            CryptoEngine.aesGcmDecrypt(master, iv, ct, tag, aad = "gl-db-v1".toByteArray())
        } else {
            val raw = CryptoEngine.randomBytes(32)
            val (iv, ct, tag) = CryptoEngine.aesGcmEncrypt(master, raw, aad = "gl-db-v1".toByteArray())
            prefs.edit()
                .putString("w_iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
                .putString("w_ct", android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP))
                .putString("w_tag", android.util.Base64.encodeToString(tag, android.util.Base64.NO_WRAP))
                .apply()
            raw
        }
        val factory = SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(ctx, GlDatabase::class.java, "gl_licenses.db")
            .openHelperFactory(factory)
            .addMigrations(*GlMigrations.ALL)
            .build()
    }

    @Provides
    fun dao(db: GlDatabase): LicenseDao = db.licenseDao()

    @Provides
    fun contactDao(db: GlDatabase): ContactMethodDao = db.contactMethodDao()
}
