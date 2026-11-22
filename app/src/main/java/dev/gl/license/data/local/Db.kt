package dev.gl.license.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "licenses")
data class LicenseEntity(
    @PrimaryKey val id: String,
    val firstName: String,
    val lastName: String,
    val nationalId: String,
    val channel: String,
    val phone: String,
    val deviceId: String,
    val appName: String,
    val type: String,
    val requestedAtIso: String,
    val issuedAtIso: String,
    val expiresAtIso: String?,
    val status: String,
    val secundarias: Int? = null,
    val precioCobrado: Int? = null,
    val codigoCorto: String? = null,
    val nonce: String,
    val version: Int,
    val txNumber: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val verificacionManual: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "'EMISION'") val origen: String = "EMISION",
)

@Entity(tableName = "consumed_nonces")
data class ConsumedNonce(@PrimaryKey val nonce: String, val consumedAtIso: String)

@Entity(tableName = "tx_reservations")
data class TxReservation(@PrimaryKey val txKey: String, val licenseId: String, val createdAtIso: String)

internal fun txKey(value: String) = value.trim().lowercase().filterNot { it.isWhitespace() }

@Dao
interface LicenseDao {
    @Query("SELECT * FROM licenses ORDER BY issuedAtIso DESC")
    fun observeAll(): Flow<List<LicenseEntity>>

    @Query("SELECT * FROM licenses ORDER BY issuedAtIso DESC")
    suspend fun all(): List<LicenseEntity>

    @Query("SELECT * FROM licenses WHERE id = :id COLLATE NOCASE LIMIT 1")
    suspend fun byId(id: String): LicenseEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun consume(nonce: ConsumedNonce): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun reserve(tx: TxReservation): Long

    @Query("DELETE FROM tx_reservations WHERE licenseId = :id")
    suspend fun release(id: String): Int

    @Query("UPDATE licenses SET status='REVOCADA' WHERE id=:id COLLATE NOCASE AND status NOT IN ('VENCIDA','REVOCADA')")
    suspend fun revokePrevious(id: String): Int

    @Insert
    suspend fun insertIssued(entity: LicenseEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM licenses WHERE appName=:app AND deviceId=:device AND issuedAtIso >= :issued)")
    suspend fun hasLater(app: String, device: String, issued: String): Boolean

    @androidx.room.Transaction
    suspend fun issue(entity: LicenseEntity, requestNonce: String, previous: String?) {
        if (consume(ConsumedNonce(requestNonce, entity.issuedAtIso)) == -1L)
            throw dev.gl.license.core.AppError.SolicitudYaProcesada
        entity.txNumber?.let {
            if (reserve(TxReservation(txKey(it), entity.id, entity.issuedAtIso)) == -1L)
                throw dev.gl.license.core.AppError.TransactionAlreadyUsed(it)
        }
        if (entity.version == 2 && hasLater(entity.appName, entity.deviceId, entity.issuedAtIso))
            throw dev.gl.license.core.AppError.EmisorDesajustado
        if (previous != null && revokePrevious(previous) != 1) throw dev.gl.license.core.AppError.RecuperacionInvalida
        insertIssued(entity)
    }

    @Query("SELECT COUNT(*) FROM tx_reservations WHERE txKey=:txNumber")
    suspend fun countByTxNumber(txNumber: String): Int

    @Query("SELECT licenseId FROM tx_reservations WHERE txKey=:txNumber LIMIT 1")
    suspend fun ownerByTxNumber(txNumber: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LicenseEntity)

    /** El importador antiguo también debe conservar las reservas y las revocaciones locales. */
    @androidx.room.Transaction
    suspend fun importRows(items: List<LicenseEntity>) {
        for (incoming in items) {
            val old = byId(incoming.id)
            if (old != null && old.issuedAtIso > incoming.issuedAtIso) continue
            val entity = if (old == null) incoming else incoming.copy(
                status = if (old.status == "REVOCADA") old.status else incoming.status,
                precioCobrado = incoming.precioCobrado ?: old.precioCobrado,
                origen = if (incoming.origen == "EMISION") old.origen else incoming.origen,
                verificacionManual = incoming.verificacionManual || old.verificacionManual,
            )
            if (old == null || old.txNumber != entity.txNumber) entity.txNumber?.let {
                reserve(TxReservation(txKey(it), entity.id, entity.issuedAtIso))
            }
            upsert(entity)
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LicenseEntity>)
}

@Entity(tableName = "contact_methods")
data class ContactMethodEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val value: String,
    val createdAtIso: String,
)

@Dao
interface ContactMethodDao {
    /**
     * Solo el valor: es lo único que el combobox muestra. createdAtIso es ISO
     * (orden lexicográfico = cronológico) y el value desempata para que dos
     * altas en el mismo segundo no queden en orden arbitrario.
     */
    @Query("SELECT value FROM contact_methods WHERE kind = :kind ORDER BY createdAtIso DESC, value ASC")
    fun observeValues(kind: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ContactMethodEntity)

    /**
     * Por id ("$kind:$value"), que es determinista: el que borra no necesita
     * haber leído la fila antes. No toca la tabla, así que no hay migración.
     */
    @Query("DELETE FROM contact_methods WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(
    entities = [LicenseEntity::class, ContactMethodEntity::class, ConsumedNonce::class, TxReservation::class, BatchCode::class, SignedBatch::class],
    version = 7,
    exportSchema = true,
)
abstract class GlDatabase : RoomDatabase() {
    abstract fun batchDao(): BatchDao
    abstract fun licenseDao(): LicenseDao
    abstract fun contactMethodDao(): ContactMethodDao
}
