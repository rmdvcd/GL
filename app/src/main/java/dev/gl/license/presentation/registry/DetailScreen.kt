package dev.gl.license.presentation.registry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.gl.license.presentation.components.LiveCountdown
import dev.gl.license.presentation.components.GlCard
import dev.gl.license.presentation.components.MetaRow
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

    init {
        viewModelScope.launch { item = repo.getById(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    vm: DetailViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val s = vm.item
    val secundariasLabel = stringResource(R.string.secundarias_title)
    val precioLabel = stringResource(R.string.license_price_charged)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .semantics { contentDescription = "Detalle de licencia" },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (s == null) {
                Text(stringResource(R.string.error_generic), modifier = Modifier.padding(16.dp))
            } else {
                GlCard {
                    MetaRow("ID", s.id)
                    MetaRow("Nombre", "${s.firstName} ${s.lastName}")
                    MetaRow("CI", s.nationalId)
                    MetaRow("Tel", s.phone)
                    MetaRow("Vía", s.channel.name)
                    MetaRow("Dispositivo", s.deviceId)
                    MetaRow("App", s.appName)
                    MetaRow("Tipo", s.type.name)
                    MetaRow("Estado", s.status.name)
                    s.secundarias?.let { MetaRow(secundariasLabel, it.toString()) }
                    s.precioCobrado?.let { MetaRow(precioLabel, PrecioTabla.formatoPrecio(it)) }
                    MetaRow("Solicitada", s.requestedAtIso)
                    MetaRow("Emitida", s.issuedAtIso)
                    MetaRow("Vence", s.expiresAtIso ?: stringResource(R.string.perpetual))
                    MetaRow("Nonce", s.nonce)
                    LiveCountdown(s, running = true)
                }
            }
        }
    }
}
