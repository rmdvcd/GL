package dev.gl.license.domain.model

/**
 * Apps de Shalev que pueden recibir un lote de canje. El `appId` del lote es
 * parte del JSON firmado y el Worker compara contra `ALLOWED_APPS`: un código
 * acuñado para una app no se canjea en otra.
 */
object RedeemApps {
    val allowed: List<String> = listOf("DSHALEV", "SHALEV-ANDROID", "SHALEV-IOS")

    fun isAllowed(appId: String): Boolean = appId in allowed

    fun notice(appId: String): String = "Este código solo sirve en la app elegida: $appId."
}
