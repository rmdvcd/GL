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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gl.license.R
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.PrecioTabla
import dev.gl.license.presentation.components.CountdownText
import dev.gl.license.presentation.components.EmptyState
import dev.gl.license.presentation.components.GlCard
import dev.gl.license.presentation.components.GlPrimaryButton
import dev.gl.license.presentation.components.GlSecondaryButton
import dev.gl.license.presentation.components.ScreenHeader
import dev.gl.license.presentation.components.StatusBanner
import dev.gl.license.presentation.theme.GlDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    // No hay ticker en segundo plano ni cuando la lista está vacía. Un solo reloj
    // compartido evita crear una corrutina por tarjeta.
    LaunchedEffect(screenVisible, ui.items.isNotEmpty()) {
        if (!screenVisible || ui.items.isEmpty()) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
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
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val data = vm.exportBytes() ?: return@launch
            val written = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (written) vm.markExportWritten()
        }
    }

    val countLabel = when (ui.items.size) {
        0 -> stringResource(R.string.registry_count_empty)
        1 -> stringResource(R.string.registry_count_one)
        else -> stringResource(R.string.registry_count_many, ui.items.size)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(GlDimens.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeader(
            title = stringResource(R.string.tab_registry),
            supportingText = countLabel,
        )
        Spacer(Modifier.height(GlDimens.gap))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            GlSecondaryButton(
                text = stringResource(R.string.import_registry),
                onClick = { open.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = !ui.loading,
                modifier = Modifier.weight(1f),
            )
            GlPrimaryButton(
                text = stringResource(R.string.export_registry),
                onClick = { create.launch("registro.glreg") },
                modifier = Modifier.weight(1f),
                loading = ui.loading,
            )
        }
        Spacer(Modifier.height(8.dp))
        StatusBanner(error = ui.error, success = ui.message)
        Spacer(Modifier.height(8.dp))

        if (ui.items.isEmpty() && !ui.loading) {
            EmptyState(
                title = stringResource(R.string.registry_empty_title),
                body = stringResource(R.string.registry_empty_body),
            )
        } else if (ui.items.isEmpty()) {
            Text(
                text = stringResource(R.string.registry_working),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = GlDimens.gap),
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(ui.items, key = { it.id }) { item ->
                    LicenseListItem(
                        item = item,
                        now = now,
                        onOpen = { onOpenDetail(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LicenseListItem(
    item: Licencia,
    now: Long,
    onOpen: () -> Unit,
) {
    val description = stringResource(
        R.string.registry_open_license,
        item.firstName,
        item.lastName,
        item.type.name,
    )
    val issued = stringResource(R.string.registry_issued, RegistryCountdown.dateLabel(item.issuedAtIso))
    val secondaryCount = item.secundarias
    val secondarySummary = if (secondaryCount != null) {
        stringResource(R.string.registry_secondary_count, secondaryCount)
    } else {
        null
    }
    val chargedPrice = item.precioCobrado
    val priceSummary = if (chargedPrice != null) {
        PrecioTabla.formatoPrecio(chargedPrice)
    } else {
        null
    }
    val paymentSummary = listOfNotNull(secondarySummary, priceSummary).joinToString(" · ")

    GlCard(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${item.firstName} ${item.lastName}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${item.type} · ${item.channel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = item.status.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = issued,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (paymentSummary.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = paymentSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        CountdownText(item, nowMillis = now)
    }
}
