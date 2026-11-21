package dev.gl.license.presentation.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import dev.gl.license.domain.model.DeliveryAddress
import android.net.Uri
import android.os.*
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal const val clipboardNotice = "Al copiar se intentará borrar el portapapeles a los 60 s. Si la app está cerrada o en segundo plano, bórralo manualmente."

internal fun directIntent(phone: String, body: String, sms: Boolean): Intent? {
    return if (sms) DeliveryAddress.sms(phone)?.let {
        Intent(Intent.ACTION_SENDTO, Uri.parse(it)).putExtra("sms_body", body)
    } else DeliveryAddress.whatsapp(phone, body)?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
}

@Composable
internal fun MessageActions(body: String, phone: String? = null) {
    val context = LocalContext.current
    var unavailable by remember(body) { mutableStateOf(false) }
    fun open(intent: Intent) {
        unavailable = try { context.startActivity(intent); false }
            catch (_: ActivityNotFoundException) { true }
            catch (_: SecurityException) { true }
    }
    fun sendText() = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
    Column {
        Text(clipboardNotice)
        TextButton(onClick = { copySensitiveText(context, body) }) { Text("Copiar") }
        TextButton(onClick = { open(Intent.createChooser(sendText(), "Compartir mensaje")) }) { Text("Compartir") }
        if (phone == null) { // Lote sin destinatario: el operador lo elige en la aplicación externa.
            TextButton(onClick = { open(sendText().setPackage("com.whatsapp")) }) { Text("WhatsApp") }
            TextButton(onClick = { open(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", body)) }) { Text("SMS") }
        } else if (DeliveryAddress.normalize(phone) != null) {
            TextButton(onClick = { directIntent(phone, body, sms = false)?.let(::open) }) { Text("WhatsApp al solicitante") }
            TextButton(onClick = { directIntent(phone, body, sms = true)?.let(::open) }) { Text("SMS al solicitante") }
        } else Text("Número no reconocido para entrega directa. Usa Copiar o Compartir.")
        if (unavailable) Text("No se pudo abrir la aplicación. Puedes copiar o compartir el mensaje.")
    }
}

internal fun copySensitiveText(context:Context,code:String) {
    val cm=context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val label="GL-"+java.util.UUID.randomUUID()
    val clip=ClipData.newPlainText(label,code)
    clip.description.extras=PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE",true) }
    cm.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        try { if(cm.primaryClipDescription?.label==label) {
            if(Build.VERSION.SDK_INT>=28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("",""))
        } } catch (_: SecurityException) { /* Android puede impedir leer fuera de primer plano. */ }
    },60000L)
}
