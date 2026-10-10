package dev.gl.license.presentation.trust

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gl.license.R
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.presentation.components.GlCard
import dev.gl.license.presentation.components.GlPrimaryButton
import dev.gl.license.presentation.components.GlSecondaryButton
import dev.gl.license.security.KeystoreManager
import dev.gl.license.security.PublicKeyBundle

/**
 * Exporta las claves públicas de este dispositivo para que una app cliente
 * las fije como ancla de confianza.
 *
 * Solo material público: no sale nada que no esté en el certificado de la
 * clave. Lo peligroso no es exponerlas, es que alguien confunda una clave
 * vieja con la actual, por eso la pantalla enseña la huella y avisa de que
 * el par se regenera si se borran los datos de la app.
 */
@Composable
fun TrustScreen() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    // Las claves son EC P-256; .encoded es el SPKI DER, que es lo que
    // consume el cliente. getOrCreate* devuelve las existentes si ya existen.
    val encryptionSpki = remember { KeystoreManager.ecdhPair().public.encoded }
    val signingSpki = remember { KeystoreManager.signingPair().public.encoded }

    val encryptionKey = remember(encryptionSpki) { PublicKeyBundle.toBase64(encryptionSpki) }
    val signingKey = remember(signingSpki) { PublicKeyBundle.toBase64(signingSpki) }
    val encryptionFp = remember(encryptionSpki) { PublicKeyBundle.fingerprint(encryptionSpki) }
    val signingFp = remember(signingSpki) { PublicKeyBundle.fingerprint(signingSpki) }

    val payload = remember(encryptionKey, signingKey, encryptionFp, signingFp) {
        PublicKeyBundle.clipboardPayload(
            encryptionKey = encryptionKey,
            signingKey = signingKey,
            encryptionFingerprint = encryptionFp,
            signingFingerprint = signingFp,
            contractVersion = CONTRACT_VERSION,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .semantics { contentDescription = "Claves públicas de GL" },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.trust_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.trust_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        GlCard {
            KeyBlock(
                title = stringResource(R.string.trust_encryption_title),
                body = stringResource(R.string.trust_encryption_body),
                key = encryptionKey,
                fingerprint = encryptionFp,
            )
        }
        Spacer(Modifier.height(12.dp))
        GlCard {
            KeyBlock(
                title = stringResource(R.string.trust_signing_title),
                body = stringResource(R.string.trust_signing_body),
                key = signingKey,
                fingerprint = signingFp,
            )
        }

        Spacer(Modifier.height(16.dp))
        GlPrimaryButton(
            text = stringResource(R.string.trust_copy_all),
            onClick = {
                copyToClipboard(context, payload)
                copied = true
            },
            modifier = Modifier.semantics { contentDescription = "Copiar claves públicas" },
        )
        if (copied) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.trust_copied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Claves copiadas" },
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun KeyBlock(title: String, body: String, key: String, fingerprint: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "SHA-256",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Monoespaciada: la huella se compara a ojo contra LicenseTrust.kt.
        Text(
            fingerprint,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Base64 (SPKI)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        val context = LocalContext.current
        val copyLabel = stringResource(R.string.trust_copy_one)
        GlSecondaryButton(
            text = copyLabel,
            onClick = {
                copyToClipboard(
                    context,
                    PublicKeyBundle.clipboardPayload(
                        encryptionKey = key,
                        signingKey = "",
                        encryptionFingerprint = fingerprint,
                        signingFingerprint = "",
                        contractVersion = CONTRACT_VERSION,
                    ),
                )
            },
            modifier = Modifier.semantics { contentDescription = "Copiar $title" },
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("GL public keys", text))
}
