package dev.gl.license.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Ambas tablas viven únicamente en la base SQLCipher existente.
@Entity(tableName = "batch_codes")
data class BatchCode(@PrimaryKey val codeHash: String, val batchId: String, val code: String, val estado: String, val nota: String)
@Entity(tableName = "signed_batches")
data class SignedBatch(@PrimaryKey val batchId: String, val appId: String, val createdAt: String, val payload: String)

data class BatchHeader(val batchId: String, val appId: String, val createdAt: String)
@Dao
interface BatchDao {
    @Insert suspend fun insert(batch: SignedBatch)
    @Insert suspend fun insertCodes(codes: List<BatchCode>)
    @Transaction suspend fun save(batch: SignedBatch, codes: List<BatchCode>) { insert(batch); insertCodes(codes) }
    @Query("SELECT batchId,appId,createdAt FROM signed_batches ORDER BY createdAt DESC") fun headers(): Flow<List<BatchHeader>>
    @Query("SELECT * FROM batch_codes WHERE batchId=:id ORDER BY codeHash") fun codes(id: String): Flow<List<BatchCode>>
    @Query("SELECT payload FROM signed_batches WHERE batchId=:id") suspend fun payload(id: String): String
    @Query("UPDATE batch_codes SET estado='ENTREGADO', nota=:nota WHERE codeHash=:hash AND estado='LIBRE'")
    suspend fun deliver(hash: String, nota: String): Int
}
