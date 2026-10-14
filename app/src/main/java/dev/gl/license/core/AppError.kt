package dev.gl.license.core

sealed class AppError(val userMessage: String) : Exception(userMessage) {
    data object AuthFailed : AppError("Autenticación fallida.")
    data object PinMismatch : AppError("El PIN no coincide.")
    data object WeakPin : AppError("El PIN debe tener 8 dígitos y no ser trivial.")
    data object InvalidPayload : AppError("La solicitud no es válida.")
    data object InvalidSecundarias : AppError("Secundarias debe ser un entero entre 0 y 10.")
    data object SpviNoEs023 : AppError("No es una solicitud de SPVI 0.23.")
    data object SpviDanada : AppError("solicitud dañada o no es para GL.")
    data object SpviDesactualizada : AppError("SPVI desactualizada: pide al cliente que instale SPVI 0.23.1 o posterior")
    data object CryptoFailure : AppError("No se pudo completar la operación.")
    data object RegistryFailure : AppError("No se pudo completar la operación.")
    data object ExportFailure : AppError("No se pudo exportar el registro.")
    data object ImportFailure : AppError("No se pudo importar el registro.")
    data object Locked : AppError("La aplicación está bloqueada.")
    data class TransactionAlreadyUsed(val tx: String) : AppError("El número de transacción $tx ya fue utilizado en una licencia previa.")
    data object DevicePubRequired : AppError("La solicitud debe incluir la clave pública del dispositivo (devicePub).")
    data object RequestExpired : AppError("La solicitud ha caducado (máximo 72 horas de antigüedad).")
    data object Generic : AppError("No se pudo completar la operación.")
}

sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Err(val error: AppError) : Outcome<Nothing>()
}
