package dev.gl.license.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Historial de migraciones Room. v1 es el esquema inicial (sin migración).
 * Incrementar [GlDatabase] version y añadir Migration aquí; no usar
 * fallbackToDestructiveMigration en release.
 */
object GlMigrations {
    /** v1→v2: añade la columna appName. DEFAULT '' para filas ya emitidas. */
    val M_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE licenses ADD COLUMN appName TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * v2→v3: tabla de datos de contacto. El DDL tiene que coincidir byte a byte
     * con lo que Room espera de [ContactMethodEntity]; si no, la validación de
     * esquema al abrir la BD lanza IllegalStateException. Sin datos previos que
     * migrar: es una tabla nueva, no una columna sobre `licenses`.
     */
    val M_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS contact_methods (" +
                    "id TEXT NOT NULL, " +
                    "kind TEXT NOT NULL, " +
                    "value TEXT NOT NULL, " +
                    "createdAtIso TEXT NOT NULL, " +
                    "PRIMARY KEY(id))"
            )
        }
    }

    /** v3→v4: secundarias y precio cobrado opcionales para SPVI. Sin valores previos que migrar. */
    val M_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE licenses ADD COLUMN secundarias INTEGER")
            db.execSQL("ALTER TABLE licenses ADD COLUMN precioCobrado INTEGER")
        }
    }

    /** v4→v5: código corto SPVI2 opcional. Sin valores previos que migrar. */
    val M_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE licenses ADD COLUMN codigoCorto TEXT")
        }
    }

    /** v5→v6: txNumber opcional para deduplicación de pagos. Sin valores previos que migrar. */
    val M_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE licenses ADD COLUMN txNumber TEXT")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE licenses ADD COLUMN verificacionManual INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE consumed_nonces (nonce TEXT NOT NULL PRIMARY KEY, consumedAtIso TEXT NOT NULL)")
            db.execSQL("CREATE TABLE tx_reservations (txKey TEXT NOT NULL PRIMARY KEY, licenseId TEXT NOT NULL, createdAtIso TEXT NOT NULL)")
            db.query("SELECT txNumber, id, issuedAtIso FROM licenses WHERE txNumber IS NOT NULL ORDER BY issuedAtIso, id").use { rows ->
                while (rows.moveToNext()) {
                    val key = txKey(rows.getString(0))
                    if (key.isNotEmpty()) db.execSQL("INSERT OR IGNORE INTO tx_reservations VALUES (?, ?, ?)",
                        arrayOf(key, rows.getString(1), rows.getString(2)))
                }
            }
        }
    }

    val ALL: Array<Migration> = arrayOf(M_1_2, M_2_3, M_3_4, M_4_5, M_5_6, MIGRATION_6_7)
}
