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

@Serializable
data class DatosUsuario(
    @SerialName("nombre") val nombre: String = "",
    @SerialName("apellidos") val apellidos: String = "",
    @SerialName("ci") val ci: String = "",
    @SerialName("telefono") val telefono: String = "",
    @SerialName("via") val via: ViaSolicitud = ViaSolicitud.WHATSAPP,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SolicitudLicencia(
    @SerialName("v") val version: Int = CONTRACT_VERSION,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("appName") val appName: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("dispositivo") val dispositivo: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("nombre") val firstName: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("apellidos") val lastName: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("ci") val nationalId: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("via") val channel: ViaSolicitud = ViaSolicitud.WHATSAPP,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("telefono") val phone: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("usuario") val usuario: DatosUsuario? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("secundarias") val secundarias: Int? = null,
    @SerialName("tipo") val type: TipoLicencia,
    @SerialName("solicitadaEn") val requestedAtIso: String,
    @SerialName("nonce") val nonce: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("devicePub") val devicePublicKeyPem: String? = null,
) {
    val effectiveFirstName: String
        get() = usuario?.nombre?.takeIf { it.isNotBlank() } ?: firstName

    val effectiveLastName: String
        get() = usuario?.apellidos?.takeIf { it.isNotBlank() } ?: lastName

    val effectiveNationalId: String
        get() = usuario?.ci?.takeIf { it.isNotBlank() } ?: nationalId

    val effectivePhone: String
        get() = usuario?.telefono?.takeIf { it.isNotBlank() } ?: phone

    val effectiveChannel: ViaSolicitud
        get() = usuario?.via ?: channel
}

typealias LicenseRequest = SolicitudLicencia

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Licencia(
    @SerialName("v") val version: Int = CONTRACT_VERSION,
    @SerialName("id") val id: String,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("appName") val appName: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("dispositivo") val dispositivo: String = "",
    @SerialName("nombre") val firstName: String,
    @SerialName("apellidos") val lastName: String,
    @SerialName("ci") val nationalId: String,
    @SerialName("via") val channel: ViaSolicitud,
    @SerialName("telefono") val phone: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("usuario") val usuario: DatosUsuario? = null,
    @SerialName("tipo") val type: TipoLicencia,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("licenciaObtenida") val licenciaObtenida: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("secundarias") val secundarias: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("periodoRestante") val periodoRestante: Long? = null,
    @SerialName("solicitadaEn") val requestedAtIso: String,
    @SerialName("emitidaEn") val issuedAtIso: String,
    @SerialName("venceEn") val expiresAtIso: String?,
    @SerialName("estado") val status: EstadoLicencia,
    @Transient val precioCobrado: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("codigoCorto") val codigoCorto: String? = null,
    @SerialName("nonce") val nonce: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("txNumber") val txNumber: String? = null,
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
