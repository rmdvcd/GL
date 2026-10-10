package dev.gl.license.presentation.registry

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gl.license.R
import dev.gl.license.domain.model.PrecioTabla
import dev.gl.license.presentation.components.CountdownText
import dev.gl.license.presentation.components.EmptyState
import dev.gl.license.presentation.components.GlCard
import dev.gl.license.presentation.components.GlPrimaryButton
import dev.gl.license.presentation.components.GlSecondaryButton
import dev.gl.license.presentation.components.ScreenTitle
import dev.gl.license.presentation.components.StatusBanner
import dev.gl.license.presentation.theme.GlDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RegistryScreen(
    vm: RegistryViewModel = hiltViewModel(),
    screenVisible: Boolean = true,
    onOpenDetail: (String) -> Unit = {},
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(screenVisible) {
        if (!screenVisible) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes != null) vm.importBytes(bytes)
        }
    }
    val create = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val data = vm.exportBytes() ?: return@launch
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) vm.markExportWritten()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(GlDimens.screen),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ScreenTitle(stringResource(R.string.tab_registry))
        if (ui.items.isNotEmpty()) {
            Text(
                "${ui.items.size} en este teléfono",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            GlSecondaryButton(
                stringResource(R.string.import_registry),
                onClick = { open.launch(arrayOf("application/octet-stream", "*/*")) },
                modifier = Modifier.weight(1f)
            )
            GlPrimaryButton(
                stringResource(R.string.export_registry),
                onClick = { create.launch("registro.glreg") },
                modifier = Modifier.weight(1f),
                loading = ui.loading
            )
        }
        Spacer(Modifier.height(8.dp))
        StatusBanner(error = ui.error, success = ui.message)
        Spacer(Modifier.height(8.dp))
        if (ui.items.isEmpty() && !ui.loading) {
            EmptyState(
                stringResource(R.string.registry_empty_title),
                stringResource(R.string.registry_empty_body)
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(ui.items, key = { it.id }) { item ->
                    GlCard(
                        Modifier
                            .clickable { onOpenDetail(item.id) }
                            .semantics {
                                contentDescription =
                                    "Licencia ${item.firstName} ${item.lastName}, ${item.type}"
                            }
                    ) {
                        Text(
                            "${item.firstName} ${item.lastName}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "${item.type} · ${item.channel}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            item.issuedAtIso,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        val cobro = listOfNotNull(
                            item.secundarias?.let { "$it secundarias" },
                            item.precioCobrado?.let { PrecioTabla.formatoPrecio(it) },
                        ).joinToString(" · ")
                        if (cobro.isNotEmpty()) {
                            Text(
                                cobro,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }
                        CountdownText(item, nowMillis = now)
                    }
                }
            }
        }
    }
}
