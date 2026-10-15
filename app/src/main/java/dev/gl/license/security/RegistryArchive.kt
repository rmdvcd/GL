package dev.gl.license.security

import dev.gl.license.domain.model.Licencia
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Payload JSON privado del backup .glreg. El contrato de envelopes v1 no cambia.
 * La forma antigua (lista directa de Licencia) sigue siendo legible.
 */
@Serializable
internal data class RegistryArchiveEntry(
    @SerialName("license") val license: Licencia,
    @SerialName("precioCobrado") val precioCobrado: Int? = null,
)

internal object RegistryArchive {
    const val MAX_ENTRIES = 10_000

    fun encode(licenses: List<Licencia>, json: Json): ByteArray {
        require(licenses.size <= MAX_ENTRIES) { "glreg-count" }
        val entries = licenses.map { RegistryArchiveEntry(it, it.precioCobrado) }
        return json.encodeToString(ListSerializer(RegistryArchiveEntry.serializer()), entries)
            .toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray, json: Json): List<Licencia> {
        val text = bytes.decodeToString()
        val trimmed = text.trimStart()
        require(trimmed.startsWith("[")) { "glreg-list" }
        require(countTopLevelEntries(trimmed) <= MAX_ENTRIES) { "glreg-count" }
        val firstEntry = trimmed.substring(1).trimStart()
        val isArchiveEntryList = firstEntry.startsWith("{") &&
            firstEntry.drop(1).trimStart().startsWith("\"license\"")
        return if (isArchiveEntryList) {
            json.decodeFromString(ListSerializer(RegistryArchiveEntry.serializer()), text)
                .map { it.license.copy(precioCobrado = it.precioCobrado) }
        } else {
            json.decodeFromString(ListSerializer(Licencia.serializer()), text)
        }
    }

    /** Cuenta objetos de primer nivel sin construir una segunda copia del JSON en memoria. */
    private fun countTopLevelEntries(json: String): Int {
        var depth = 0
        var inString = false
        var escaped = false
        var count = 0
        for (char in json) {
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char.code == 92) {
                    escaped = true
                } else if (char.code == 34) {
                    inString = false
                }
                continue
            }
            when (char.code) {
                34 -> inString = true
                91 -> depth++
                93 -> depth--
                123 -> {
                    if (depth == 1) {
                        count++
                        if (count > MAX_ENTRIES) return count
                    }
                    depth++
                }
                125 -> depth--
            }
        }
        return count
    }
}
