package dev.gl.license.domain.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

const val CONTRACT_VERSION = 1

enum class ViaSolicitud { WHATSAPP, SMS }

enum class TipoLicencia {
    MENSUAL, SEMESTRAL, ANUAL, PERPETUA;

    fun days(): Long? = when (this) {
        MENSUAL -> 30
        SEMESTRAL -> 180
        ANUAL -> 365
        PERPETUA -> null
    }
}

enum class EstadoLicencia { ACTIVA, VENCIDA, REVOCADA, PERPETUA }

typealias RequestChannel = ViaSolicitud
typealias LicenseType = TipoLicencia
typealias LicenseStatus = EstadoLicencia

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SolicitudLicencia(
    @SerialName("v") val version: Int,
    @SerialName("nombre") val firstName: String,
    @SerialName("apellidos") val lastName: String,
    @SerialName("ci") val nationalId: String,
    @SerialName("via") val channel: ViaSolicitud,
    @SerialName("telefono") val phone: String,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("appName") val appName: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("secundarias") val secundarias: Int? = null,
    @SerialName("tipo") val type: TipoLicencia,
    @SerialName("solicitadaEn") val requestedAtIso: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("devicePub") val devicePublicKeyPem: String? = null,
)

typealias LicenseRequest = SolicitudLicencia

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Licencia(
    @SerialName("v") val version: Int,
    @SerialName("id") val id: String,
    @SerialName("nombre") val firstName: String,
    @SerialName("apellidos") val lastName: String,
    @SerialName("ci") val nationalId: String,
    @SerialName("via") val channel: ViaSolicitud,
    @SerialName("telefono") val phone: String,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("appName") val appName: String,
    @SerialName("tipo") val type: TipoLicencia,
    @SerialName("solicitadaEn") val requestedAtIso: String,
    @SerialName("emitidaEn") val issuedAtIso: String,
    @SerialName("venceEn") val expiresAtIso: String?,
    @SerialName("estado") val status: EstadoLicencia,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("secundarias") val secundarias: Int? = null,
    @Transient val precioCobrado: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("codigoCorto") val codigoCorto: String? = null,
    @SerialName("nonce") val nonce: String,
)

typealias IssuedLicense = Licencia

data class RegistroLicencia(
    val licencia: Licencia,
    val registradaEnIso: String,
)

@Serializable
data class Envelope(
    @SerialName("v") val version: Int,
    @SerialName("alg") val alg: String,
    @SerialName("epk") val ephemeralPublicKey: String,
    @SerialName("iv") val iv: String,
    @SerialName("ct") val ciphertext: String,
    @SerialName("tag") val tag: String,
    @SerialName("sig") val signature: String,
    @SerialName("kid") val keyId: String,
)

data class SolicitudValidada(
    val solicitud: SolicitudLicencia,
    val sobreCrudo: String,
)

typealias ValidatedRequest = SolicitudValidada
