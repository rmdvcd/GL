package dev.gl.license.domain.model

import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome

object Secundarias {
    const val MINIMO = 0
    const val MAXIMO = 10
    private const val APP = "SPVI"
    private const val DEVICE_PREFIX = "SPVI:"

    fun aplicaA(appName: String?, deviceId: String?): Boolean =
        appName == APP || (deviceId?.startsWith(DEVICE_PREFIX) == true)

    fun efectivas(appName: String?, deviceId: String?, valor: Int?): Int? =
        if (aplicaA(appName, deviceId)) valor else null

    fun validar(appName: String?, deviceId: String?, valor: Int?): Outcome<Unit> {
        val n = efectivas(appName, deviceId, valor) ?: return Outcome.Ok(Unit)
        if (n in MINIMO..MAXIMO) return Outcome.Ok(Unit)
        return Outcome.Err(AppError.InvalidSecundarias)
    }
}
