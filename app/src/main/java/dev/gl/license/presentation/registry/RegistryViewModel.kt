package dev.gl.license.presentation.registry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.repository.LicenciaRepository
import dev.gl.license.domain.usecase.ExportarRegistroUseCase
import dev.gl.license.domain.usecase.ImportarRegistroUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegistryUi(
    val items: List<Licencia> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    val loading: Boolean = false,
)

@HiltViewModel
class RegistryViewModel @Inject constructor(
    private val repo: LicenciaRepository,
    private val importUc: ImportarRegistroUseCase,
    private val exportUc: ExportarRegistroUseCase,
) : ViewModel() {

    private val _ui = MutableStateFlow(RegistryUi())
    val ui: StateFlow<RegistryUi> = _ui

    init {
        viewModelScope.launch {
            repo.observeAll().collect { list ->
                _ui.update { it.copy(items = list) }
            }
        }
    }

    fun importBytes(bytes: ByteArray) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, message = null) }
            when (val r = importUc(bytes)) {
                is Outcome.Ok -> _ui.update {
                    it.copy(loading = false, message = "Importación correcta (${r.value}).")
                }
                is Outcome.Err -> _ui.update { it.copy(loading = false, error = r.error.userMessage) }
            }
        }
    }

    suspend fun exportBytes(): ByteArray? {
        _ui.update { it.copy(loading = true, error = null, message = null) }
        return when (val r = exportUc()) {
            is Outcome.Ok -> {
                _ui.update { it.copy(loading = false, message = "Exportación lista.") }
                r.value
            }
            is Outcome.Err -> {
                _ui.update { it.copy(loading = false, error = r.error.userMessage) }
                null
            }
        }
    }

    fun markExportWritten() {
        _ui.update { it.copy(message = "Archivo .glreg guardado.") }
    }
}
