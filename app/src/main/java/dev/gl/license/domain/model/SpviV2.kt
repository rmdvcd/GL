package dev.gl.license.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Solicitud SPVI 0.23.1 (cifrada en SPVIR1). Sin `appName`: la app es el prefijo de `deviceId`. */
@Serializable
data class SolicitudSpviV2(
    @SerialName("v") val v: Int,
    @SerialName("nombre") val nombre: String,
    @SerialName("apellidos") val apellidos: String,
    @SerialName("ci") val ci: String,
    @SerialName("via") val via: ViaSolicitud,
    @SerialName("telefono") val telefono: String,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("tipo") val tipo: TipoLicencia,
    @SerialName("solicitadaEn") val solicitadaEn: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("devicePub") val devicePub: String,
    @SerialName("secundarias") val secundarias: Int,
    @SerialName("renueva") val renueva: String? = null,
)
