package dev.gl.license.data.local

import dev.gl.license.data.mapper.toEntity
import dev.gl.license.domain.model.ContactoMetodo
import dev.gl.license.domain.repository.ContactMethodRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Los valores viven en la BD cifrada (SQLCipher, clave en el Android Keystore),
 * no en Preferences ni en fichero plano: así no son legible ni con root, ni con
 * un backup del dispositivo, ni copiando el fichero.
 */
@Singleton
class ContactMethodRepositoryImpl @Inject constructor(
    private val dao: ContactMethodDao,
) : ContactMethodRepository {

    override fun observe(kind: String): Flow<List<String>> = dao.observeValues(kind)

    override suspend fun add(item: ContactoMetodo) {
        dao.upsert(item.toEntity())
    }

    override suspend fun remove(kind: String, value: String) {
        dao.deleteById("$kind:$value")
    }
}
