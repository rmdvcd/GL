package dev.gl.license.data.local

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.data.mapper.toDomain
import dev.gl.license.data.mapper.toEntity
import dev.gl.license.domain.model.RegistroLicencia
import dev.gl.license.domain.repository.RegistroRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegistroRepositoryImpl @Inject constructor(
    private val dao: LicenseDao,
) : RegistroRepository {
    override fun observe(): Flow<List<RegistroLicencia>> =
        dao.observeAll().map { list -> list.map { RegistroLicencia(it.toDomain(), it.issuedAtIso) } }

    override suspend fun registrar(registro: RegistroLicencia): Outcome<Unit> = try {
        dao.upsert(registro.licencia.toEntity())
        Outcome.Ok(Unit)
    } catch (_: Exception) {
        Outcome.Err(AppError.RegistryFailure)
    }

    override suspend fun mergePorId(incoming: List<RegistroLicencia>): Outcome<Int> = try {
        val existing = dao.all().associateBy { it.id }
        val toWrite = incoming.map { it.licencia }.filter { lic ->
            val old = existing[lic.id]
            old == null || lic.issuedAtIso >= old.issuedAtIso
        }
        dao.upsertAll(toWrite.map { it.toEntity() })
        Outcome.Ok(toWrite.size)
    } catch (_: Exception) {
        Outcome.Err(AppError.ImportFailure)
    }

    override suspend fun todos(): List<RegistroLicencia> =
        dao.all().map { RegistroLicencia(it.toDomain(), it.issuedAtIso) }
}
