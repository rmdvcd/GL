package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.Outcome
import dev.gl.license.data.mapper.toDomain
import dev.gl.license.data.mapper.toEntity
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.EnvelopeValidator
import dev.gl.license.security.RegistryPack
import dev.gl.license.security.Spvi23
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Test

class SpviGlregTest {

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }

    private val firmaKp = CryptoEngine.generateEphemeralP256()
    private val devKp = CryptoEngine.generateEphemeralP256()

    private fun licenciaV2(codigo: String? = null): Licencia {
        val id = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        val deviceId = "SPVI:golden0000000001"
        val devPub33 = Spvi23.comprimirPunto(devKp.public)
        val emitida = 1_791_057_600L
        val vence = 1_793_649_600L
        val body = Spvi23.cuerpo(
            UUID.fromString(id),
            Spvi23.huella(deviceId, devPub33),
            TipoLicencia.MENSUAL,
            EstadoLicencia.ACTIVA,
            2,
            emitida,
            vence,
        )
        val cod = codigo
            ?: Spvi23.sellarLicencia(body, devKp.public, firmaKp.private).codigo()
        return Licencia(
            version = 2,
            id = id,
            firstName = "Maria",
            lastName = "Perez Gonzalez",
            nationalId = "85010112345",
            channel = ViaSolicitud.WHATSAPP,
            phone = "+5352345678",
            deviceId = deviceId,
            appName = "SPVI",
            type = TipoLicencia.MENSUAL,
            requestedAtIso = "2026-10-03T19:00:00Z",
            issuedAtIso = Instant.ofEpochSecond(emitida).toString(),
            expiresAtIso = Instant.ofEpochSecond(vence).toString(),
            status = EstadoLicencia.ACTIVA,
            secundarias = 2,
            precioCobrado = 8_000,
            codigoCorto = cod,
            nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
        )
    }

    @Test
    fun v2SerializaCodigoCortoYV1LoOmite() {
        val v2 = licenciaV2()
        val v2Json = json.encodeToString(
            ListSerializer(Licencia.serializer()),
            listOf(v2),
        )
        assertThat(v2Json).contains("\"codigoCorto\":\"SPVI2:")

        val v1 = v2.copy(
            version = 1,
            appName = "MiApp",
            deviceId = "device-abc-12345",
            secundarias = null,
            codigoCorto = null,
        )
        val v1Json = json.encodeToString(
            ListSerializer(Licencia.serializer()),
            listOf(v1),
        )
        assertThat(v1Json).doesNotContain("codigoCorto")
    }

    @Test
    fun licenciaCortaPasaConFirmaValida() {
        assertThat(EnvelopeValidator.licenciaCorta(licenciaV2(), firmaKp.public))
            .isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun codigoMutadoFallaEntero() {
        val bueno = licenciaV2().codigoCorto!!
        val i = 60
        val otro = if (bueno[i] == 'A') 'B' else 'A'
        val mutado = bueno.substring(0, i) + otro + bueno.substring(i + 1)
        assertThat(EnvelopeValidator.licenciaCorta(licenciaV2(mutado), firmaKp.public))
            .isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun codigoTruncadoFallaEntero() {
        val bueno = licenciaV2().codigoCorto!!
        assertThat(EnvelopeValidator.licenciaCorta(licenciaV2(bueno.dropLast(10)), firmaKp.public))
            .isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun glregRoundtripConFilaV2() {
        val lic = licenciaV2()
        val payload = json.encodeToString(
            ListSerializer(Licencia.serializer()),
            listOf(lic),
        ).toByteArray()
        val aes = SecretKeySpec(CryptoEngine.randomBytes(32), "AES")
        val packed = RegistryPack.pack(payload, aes, firmaKp.private)
        val out = RegistryPack.unpack(packed, aes, firmaKp.public)
        val texto = (out as Outcome.Ok).value.decodeToString()
        val filas = json.decodeFromString(ListSerializer(Licencia.serializer()), texto)
        assertThat(filas).hasSize(1)
        assertThat(EnvelopeValidator.licenciaCorta(filas[0], firmaKp.public))
            .isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun mapperConservaCodigoCortoIdaYVuelta() {
        val lic = licenciaV2()
        val entidad = lic.toEntity()
        assertThat(entidad.codigoCorto).isEqualTo(lic.codigoCorto)
        assertThat(entidad.toDomain().codigoCorto).isEqualTo(lic.codigoCorto)
    }
}
