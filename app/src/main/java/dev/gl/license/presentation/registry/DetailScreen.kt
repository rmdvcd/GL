package dev.gl.license.presentation.registry

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gl.license.R
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.PrecioTabla
import dev.gl.license.domain.repository.LicenciaRepository
import dev.gl.license.presentation.components.EmptyState
import dev.gl.license.presentation.components.GlCard
import dev.gl.license.presentation.components.LiveCountdown
import dev.gl.license.presentation.components.MetaRow
import dev.gl.license.presentation.components.SectionLabel
import dev.gl.license.presentation.theme.GlDimens
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repo: LicenciaRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val id: String = savedStateHandle.get<String>("id").orEmpty()
    var item by mutableStateOf<Licencia?>(null)
        private set
    var loading by mutableStateOf(true)
        private set

    init {
        viewModelScope.launch {
            try {
                item = repo.getById(id)
            } catch (_: Exception) {
                item = null
            } finally {
                loading = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    vm: DetailViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val item = vm.item
    val detailDescription = stringResource(R.string.cd_detail)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(GlDimens.screen)
                .semantics { contentDescription = detailDescription },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                vm.loading -> DetailLoading()
                item == null -> EmptyState(
                    title = stringResource(R.string.detail_missing_title),
                    body = stringResource(R.string.detail_missing_body),
                )
                else -> LicenseDetail(item)
            }
        }
    }
}

@Composable
private fun DetailLoading() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.detail_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LicenseDetail(item: Licencia) {
    GlCard(contentAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "${item.firstName} ${item.lastName}",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "${item.type} · ${item.status}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        LiveCountdown(item, running = true)
    }
    Spacer(Modifier.height(GlDimens.gap))

    SectionLabel(stringResource(R.string.detail_identity_section))
    Spacer(Modifier.height(8.dp))
    GlCard {
        MetaRow(stringResource(R.string.field_license_id), item.id)
        MetaRow(stringResource(R.string.field_name), "${item.firstName} ${item.lastName}")
        MetaRow(stringResource(R.string.field_national_id), item.nationalId)
        MetaRow(stringResource(R.string.field_phone), item.phone)
        MetaRow(stringResource(R.string.field_channel), item.channel.name)
        MetaRow(stringResource(R.string.field_device), item.deviceId)
        MetaRow(stringResource(R.string.field_app), item.appName)
    }
    Spacer(Modifier.height(GlDimens.gap))

    SectionLabel(stringResource(R.string.detail_license_section))
    Spacer(Modifier.height(8.dp))
    GlCard {
        MetaRow(stringResource(R.string.field_type), item.type.name)
        MetaRow(stringResource(R.string.field_status), item.status.name)
        item.secundarias?.let {
            MetaRow(stringResource(R.string.secundarias_title), it.toString())
        }
        item.precioCobrado?.let {
            MetaRow(stringResource(R.string.license_price_charged), PrecioTabla.formatoPrecio(it))
        }
        MetaRow(
            stringResource(R.string.field_requested_at),
            RegistryCountdown.dateLabel(item.requestedAtIso),
        )
        MetaRow(
            stringResource(R.string.field_issued_at),
            RegistryCountdown.dateLabel(item.issuedAtIso),
        )
        MetaRow(
            stringResource(R.string.field_expires_at),
            item.expiresAtIso?.let(RegistryCountdown::dateLabel)
                ?: stringResource(R.string.perpetual),
        )
        MetaRow(stringResource(R.string.field_nonce), item.nonce)
    }
    Spacer(Modifier.height(24.dp))
}
