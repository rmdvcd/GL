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
)

@Dao
interface LicenseDao {
    @Query("SELECT * FROM licenses ORDER BY issuedAtIso DESC")
    fun observeAll(): Flow<List<LicenseEntity>>

    @Query("SELECT * FROM licenses ORDER BY issuedAtIso DESC")
    suspend fun all(): List<LicenseEntity>

    @Query("SELECT * FROM licenses WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): LicenseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LicenseEntity)

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
    entities = [LicenseEntity::class, ContactMethodEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class GlDatabase : RoomDatabase() {
    abstract fun licenseDao(): LicenseDao
    abstract fun contactMethodDao(): ContactMethodDao
}
