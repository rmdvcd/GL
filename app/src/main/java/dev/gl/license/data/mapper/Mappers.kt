package dev.gl.license.data.mapper

import dev.gl.license.data.local.ContactMethodEntity
import dev.gl.license.data.local.LicenseEntity
import dev.gl.license.domain.model.ContactoMetodo
import dev.gl.license.domain.model.IssuedLicense
import dev.gl.license.domain.model.LicenseStatus
import dev.gl.license.domain.model.LicenseType
import dev.gl.license.domain.model.RequestChannel

fun IssuedLicense.toEntity() = LicenseEntity(
    id = id,
    firstName = firstName,
    lastName = lastName,
    nationalId = nationalId,
    channel = channel.name,
    phone = phone,
    deviceId = deviceId,
    appName = appName,
    type = type.name,
    requestedAtIso = requestedAtIso,
    issuedAtIso = issuedAtIso,
    expiresAtIso = expiresAtIso,
    status = status.name,
    secundarias = secundarias,
    precioCobrado = precioCobrado,
    codigoCorto = codigoCorto,
    nonce = nonce,
    version = version,
)

/**
 * El id es determinista ("kind:value"), así que volver a añadir el mismo dato
 * reemplaza la fila en vez de duplicarla. Ver [ContactoMetodo].
 */
fun ContactoMetodo.toEntity() = ContactMethodEntity(
    id = id,
    kind = kind,
    value = value,
    createdAtIso = createdAtIso,
)

fun LicenseEntity.toDomain() = IssuedLicense(
    version = version,
    id = id,
    firstName = firstName,
    lastName = lastName,
    nationalId = nationalId,
    channel = RequestChannel.valueOf(channel),
    phone = phone,
    deviceId = deviceId,
    appName = appName,
    type = LicenseType.valueOf(type),
    requestedAtIso = requestedAtIso,
    issuedAtIso = issuedAtIso,
    expiresAtIso = expiresAtIso,
    status = LicenseStatus.valueOf(status),
    secundarias = secundarias,
    precioCobrado = precioCobrado,
    codigoCorto = codigoCorto,
    nonce = nonce,
)
