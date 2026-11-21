package dev.gl.license.presentation.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal const val clipboardNotice = "Al copiar se intentará borrar el portapapeles a los 60 s. Si la app está cerrada o en segundo plano, bórralo manualmente."

@Composable
internal fun MessageActions(body: String) {
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
        TextButton(onClick = { open(sendText().setPackage("com.whatsapp")) }) { Text("WhatsApp") }
        TextButton(onClick = { open(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", body)) }) { Text("SMS") }
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
