package dev.gl.license.data.local

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.data.mapper.toDomain
import dev.gl.license.data.mapper.toEntity
import dev.gl.license.domain.model.IssuedLicense
import dev.gl.license.domain.repository.LicenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LicenseRepositoryImpl @Inject constructor(
    private val dao: LicenseDao,
) : LicenseRepository {
    override fun observeAll(): Flow<List<IssuedLicense>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun upsert(license: IssuedLicense): Outcome<Unit> = try {
        dao.upsert(license.toEntity())
        Outcome.Ok(Unit)
    } catch (_: Exception) {
        Outcome.Err(AppError.RegistryFailure)
    }

    override suspend fun getById(id: String): IssuedLicense? = dao.byId(id)?.toDomain()

    override suspend fun merge(incoming: List<IssuedLicense>): Outcome<Int> = try {
        val existing = dao.all().associateBy { it.id }
        val toWrite = incoming.filter { lic ->
            val old = existing[lic.id]
            old == null || lic.issuedAtIso >= old.issuedAtIso
        }
        dao.upsertAll(toWrite.map { it.toEntity() })
        Outcome.Ok(toWrite.size)
    } catch (_: Exception) {
        Outcome.Err(AppError.ImportFailure)
    }

    override suspend fun all(): List<IssuedLicense> = dao.all().map { it.toDomain() }
}
