package dev.gl.license

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.common.truth.Truth.assertThat
import dev.gl.license.data.local.GlMigrations
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Ejecuta GlMigrations.M_1_2 sobre una BD con el esquema v1 real.
 *
 * Un ALTER TABLE mal escrito solo falla en dispositivos que ya tuvieran la BD
 * de una versión anterior, así que sin esto el error sale en producción, no en
 * CI. El DDL de abajo está copiado de app/schemas/.../1.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {

    private fun openV1(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(
                RuntimeEnvironment.getApplication(),
            )
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(V1_DDL)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build()
        )

    @Test
    fun v1_to_2_addsAppNameAndKeepsExistingRows() {
        val helper = openV1()
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO licenses (id, firstName, lastName, nationalId, channel, phone, " +
                    "deviceId, type, requestedAtIso, issuedAtIso, expiresAtIso, status, nonce, version) " +
                    "VALUES ('x','Ana','Perez','85010112345','SMS','+5355512345','device-abc-12345'," +
                    "'MENSUAL','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','2026-01-31T00:00:00Z'," +
                    "'ACTIVA','AAAAAAAAAAAAAAAAAAAAAA==',1)"
            )

            GlMigrations.M_1_2.migrate(db)

            assertThat(columnNames(db)).contains("appName")

            // La fila previa sobrevive y appName queda vacía, nunca nula:
            // la entidad es String, no String?, y un null aquí crashearía el mapper.
            db.query("SELECT id, appName FROM licenses WHERE id = 'x'").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.isNull(1)).isFalse()
                assertThat(c.getString(1)).isEmpty()
            }
        }
        helper.close()
    }

    @Test
    fun v1_to_2_then_rowsAcceptAnAppName() {
        val helper = openV1()
        helper.writableDatabase.use { db ->
            GlMigrations.M_1_2.migrate(db)
            db.execSQL(
                "INSERT INTO licenses (id, firstName, lastName, nationalId, channel, phone, " +
                    "deviceId, appName, type, requestedAtIso, issuedAtIso, expiresAtIso, status, " +
                    "nonce, version) VALUES ('y','Ana','Perez','85010112345','SMS','+5355512345'," +
                    "'device-abc-12345','MiApp','MENSUAL','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'," +
                    "'2026-01-31T00:00:00Z','ACTIVA','AAAAAAAAAAAAAAAAAAAAAA==',2)"
            )
            db.query("SELECT appName FROM licenses WHERE id = 'y'").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("MiApp")
            }
        }
        helper.close()
    }

    private fun openV2(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(
                RuntimeEnvironment.getApplication(),
            )
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(V2_DDL)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build()
        )

    /**
     * Room valida el esquema real contra el de la entidad al abrir, y revisa
     * nombre de columna, afinidad, NOT NULL y clave primaria. Un CREATE TABLE
     * que se parezca pero no coincida lanza IllegalStateException en el
     * dispositivo del cliente, no en CI. Por eso se asertan las cuatro cosas.
     */
    @Test
    fun v2_to_3_createsContactMethodsMatchingTheEntity() {
        val helper = openV2()
        helper.writableDatabase.use { db ->
            GlMigrations.M_2_3.migrate(db)

            assertThat(tableNames(db)).contains("contact_methods")
            assertThat(columnNames(db, "contact_methods"))
                .containsExactly("id", "kind", "value", "createdAtIso").inOrder()
            assertThat(notNullColumns(db, "contact_methods"))
                .containsExactly("id", "kind", "value", "createdAtIso")
        }
        helper.close()
    }

    @Test
    fun v2_to_3_keepsExistingLicenses() {
        val helper = openV2()
        helper.writableDatabase.use { db ->
            db.execSQL(INSERT_LICENSE_V2)
            GlMigrations.M_2_3.migrate(db)

            // Una migración no puede perder ni una licencia ya emitida.
            db.query("SELECT id, appName FROM licenses WHERE id = 'z'").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(1)).isEqualTo("MiApp")
            }
        }
        helper.close()
    }

    /**
     * El id determinista ("card:4242") solo deduplica si la clave primaria es
     * `id`. Si la migración creara la tabla sin PK, el REPLACE de Room
     * insertaría duplicados en silencio.
     */
    @Test
    fun v2_to_3_contactMethodsKeysOnId() {
        val helper = openV2()
        helper.writableDatabase.use { db ->
            GlMigrations.M_2_3.migrate(db)
            db.execSQL(
                "INSERT INTO contact_methods (id, kind, value, createdAtIso) VALUES " +
                    "('card:4242','card','4242','2026-01-01T00:00:00Z')"
            )
            db.execSQL(
                "INSERT OR REPLACE INTO contact_methods (id, kind, value, createdAtIso) VALUES " +
                    "('card:4242','card','9999','2026-01-02T00:00:00Z')"
            )

            db.query("SELECT value FROM contact_methods").use { c ->
                assertThat(c.count).isEqualTo(1)
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("9999")
            }
        }
        helper.close()
    }

    private fun openV3(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(
                RuntimeEnvironment.getApplication(),
            )
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(V3_LICENSES_DDL)
                        db.execSQL(V3_CONTACT_METHODS_DDL)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build()
        )

    @Test
    fun v3_to_4_addsNullableSecundariasAndPrecioCobrado() {        val helper = openV3()
        helper.writableDatabase.use { db ->
            GlMigrations.M_3_4.migrate(db)

            assertThat(columnNames(db)).containsAtLeast("secundarias", "precioCobrado")
            assertThat(notNullColumns(db, "licenses")).doesNotContain("secundarias")
            assertThat(notNullColumns(db, "licenses")).doesNotContain("precioCobrado")
        }
        helper.close()
    }

    @Test
    fun v4_to_5_addsNullableCodigoCortoAndKeepsRows() {
        val helper = openV4()
        helper.writableDatabase.use { db ->
            db.execSQL(INSERT_LICENSE_V4)
            GlMigrations.M_4_5.migrate(db)

            assertThat(columnNames(db)).contains("codigoCorto")
            assertThat(notNullColumns(db, "licenses")).doesNotContain("codigoCorto")

            // La fila previa sobrevive con sus secundarias y sin código corto.
            db.query("SELECT id, secundarias, codigoCorto FROM licenses WHERE id = 'w'").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(1)).isEqualTo(2)
                assertThat(c.isNull(2)).isTrue()
            }

            // La columna nueva acepta el código SPVI2 de 210 caracteres.
            db.execSQL("UPDATE licenses SET codigoCorto = 'SPVI2:x' WHERE id = 'w'")
            db.query("SELECT codigoCorto FROM licenses WHERE id = 'w'").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("SPVI2:x")
            }
        }
        helper.close()
    }

    private fun openV4(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(
                RuntimeEnvironment.getApplication(),
            )
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(V4_LICENSES_DDL)
                        db.execSQL(V3_CONTACT_METHODS_DDL)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build()
        )

    private fun tableNames(db: SupportSQLiteDatabase): List<String> =
        mutableListOf<String>().also { out ->
            db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
                while (c.moveToNext()) out.add(c.getString(0))
            }
        }

    private fun columnNames(db: SupportSQLiteDatabase, table: String = "licenses"): List<String> =
        mutableListOf<String>().also { out ->
            db.query("PRAGMA table_info($table)").use { c ->
                val idx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) out.add(c.getString(idx))
            }
        }

    private fun notNullColumns(db: SupportSQLiteDatabase, table: String): List<String> =
        mutableListOf<String>().also { out ->
            db.query("PRAGMA table_info($table)").use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                val nnIdx = c.getColumnIndexOrThrow("notnull")
                while (c.moveToNext()) {
                    if (c.getInt(nnIdx) == 1) out.add(c.getString(nameIdx))
                }
            }
        }

    private companion object {
        const val V1_DDL =
            "CREATE TABLE IF NOT EXISTS `licenses` (`id` TEXT NOT NULL, `firstName` TEXT NOT NULL, " +
                "`lastName` TEXT NOT NULL, `nationalId` TEXT NOT NULL, `channel` TEXT NOT NULL, " +
                "`phone` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`requestedAtIso` TEXT NOT NULL, `issuedAtIso` TEXT NOT NULL, `expiresAtIso` TEXT, " +
                "`status` TEXT NOT NULL, `nonce` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"

        /** v1 más la columna appName que añade M_1_2. Copiado de 2.json. */
        const val V2_DDL =
            "CREATE TABLE IF NOT EXISTS `licenses` (`id` TEXT NOT NULL, `firstName` TEXT NOT NULL, " +
                "`lastName` TEXT NOT NULL, `nationalId` TEXT NOT NULL, `channel` TEXT NOT NULL, " +
                "`phone` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `appName` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, `requestedAtIso` TEXT NOT NULL, `issuedAtIso` TEXT NOT NULL, " +
                "`expiresAtIso` TEXT, `status` TEXT NOT NULL, `nonce` TEXT NOT NULL, " +
                "`version` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        const val INSERT_LICENSE_V2 =
            "INSERT INTO licenses (id, firstName, lastName, nationalId, channel, phone, " +
                "deviceId, appName, type, requestedAtIso, issuedAtIso, expiresAtIso, status, " +
                "nonce, version) VALUES ('z','Ana','Perez','85010112345','SMS','+5355512345'," +
                "'device-abc-12345','MiApp','MENSUAL','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'," +
                "'2026-01-31T00:00:00Z','ACTIVA','AAAAAAAAAAAAAAAAAAAAAA==',2)"

        const val V3_LICENSES_DDL = V2_DDL
        const val V3_CONTACT_METHODS_DDL =
            "CREATE TABLE IF NOT EXISTS `contact_methods` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`value` TEXT NOT NULL, `createdAtIso` TEXT NOT NULL, PRIMARY KEY(`id`))"

        /** v3 más las columnas que añade M_3_4. Copiado de 4.json. */
        const val V4_LICENSES_DDL =
            "CREATE TABLE IF NOT EXISTS `licenses` (`id` TEXT NOT NULL, `firstName` TEXT NOT NULL, " +
                "`lastName` TEXT NOT NULL, `nationalId` TEXT NOT NULL, `channel` TEXT NOT NULL, " +
                "`phone` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `appName` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, `requestedAtIso` TEXT NOT NULL, `issuedAtIso` TEXT NOT NULL, " +
                "`expiresAtIso` TEXT, `status` TEXT NOT NULL, `secundarias` INTEGER, " +
                "`precioCobrado` INTEGER, `nonce` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"

        const val INSERT_LICENSE_V4 =
            "INSERT INTO licenses (id, firstName, lastName, nationalId, channel, phone, " +
                "deviceId, appName, type, requestedAtIso, issuedAtIso, expiresAtIso, status, " +
                "secundarias, precioCobrado, nonce, version) VALUES ('w','Maria','Perez Gonzalez'," +
                "'85010112345','WHATSAPP','+5352345678','SPVI:golden0000000001','SPVI','MENSUAL'," +
                "'2026-10-03T19:00:00Z','2026-10-03T20:00:00Z','2026-11-02T20:00:00Z','ACTIVA'," +
                "2,8000,'AAAAAAAAAAAAAAAAAAAAAA==',2)"
    }
}
