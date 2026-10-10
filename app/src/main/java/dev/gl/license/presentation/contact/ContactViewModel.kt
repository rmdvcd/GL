package dev.gl.license.presentation.contact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gl.license.domain.model.ContactKind
import dev.gl.license.domain.model.ContactoMetodo
import dev.gl.license.domain.repository.ContactMethodRepository
import dev.gl.license.domain.repository.SecureClock
import dev.gl.license.presentation.contact.ContactClipboard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Confirmación tras guardar. Literal en el VM como "Importación correcta" en RegistryViewModel. */
private const val ADDED_MESSAGE = "Añadido a la lista."

data class ContactUi(
    val cards: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    val selectedCard: String? = null,
    val selectedPhone: String? = null,
    val selectedApp: String? = null,
    val message: String? = null,
    /** Valor en edición, si lo hay. Mientras existe, Añadir actúa como Guardar. */
    val editingCard: String? = null,
    val editingPhone: String? = null,
    val editingApp: String? = null,
)

@HiltViewModel
class ContactViewModel @Inject constructor(
    private val repo: ContactMethodRepository,
    private val clock: SecureClock,
) : ViewModel() {

    private val _ui = MutableStateFlow(ContactUi())
    val ui: StateFlow<ContactUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observe(ContactKind.CARD).collect { list ->
                _ui.update { it.copy(cards = list) }
            }
        }
        viewModelScope.launch {
            repo.observe(ContactKind.PHONE).collect { list ->
                _ui.update { it.copy(phones = list) }
            }
        }
        viewModelScope.launch {
            repo.observe(ContactKind.APP).collect { list ->
                _ui.update { it.copy(apps = list) }
            }
        }
    }

    /** Normaliza a forma canónica: sin espacios, porque es lo que se pega. */
    fun addCard(raw: String) {
        val value = raw.filterNot { it.isWhitespace() }
        if (value.isBlank()) return
        viewModelScope.launch {
            // Editar es sustituir: el id es "$kind:$value", así que cambiar el
            // valor es borrar la fila vieja y crear la nueva.
            _ui.value.editingCard?.let { repo.remove(ContactKind.CARD, it) }
            repo.add(newItem(ContactKind.CARD, value))
            _ui.update { it.copy(selectedCard = value, editingCard = null, message = ADDED_MESSAGE) }
        }
    }

    fun addPhone(raw: String) {
        val value = raw.trim()
        if (value.isBlank()) return
        viewModelScope.launch {
            _ui.value.editingPhone?.let { repo.remove(ContactKind.PHONE, it) }
            repo.add(newItem(ContactKind.PHONE, value))
            _ui.update { it.copy(selectedPhone = value, editingPhone = null, message = ADDED_MESSAGE) }
        }
    }

    /** El nombre conserva los espacios interiores: las apps se llaman así. */
    fun addApp(raw: String) {
        val value = raw.trim()
        if (value.isBlank()) return
        viewModelScope.launch {
            _ui.value.editingApp?.let { repo.remove(ContactKind.APP, it) }
            repo.add(newItem(ContactKind.APP, value))
            _ui.update { it.copy(selectedApp = value, editingApp = null, message = ADDED_MESSAGE) }
        }
    }

    /**
     * Marca el valor en edición y lo devuelve para precargarlo en el campo.
     * Devolverlo (en vez de que la pantalla lea el estado) deja claro en el
     * call-site que el campo se rellena con ese valor.
     */
    fun beginEditCard(value: String): String {
        _ui.update { it.copy(editingCard = value, message = null) }
        return value
    }

    fun beginEditPhone(value: String): String {
        _ui.update { it.copy(editingPhone = value, message = null) }
        return value
    }

    fun beginEditApp(value: String): String {
        _ui.update { it.copy(editingApp = value, message = null) }
        return value
    }

    /**
     * Borra y, si era el seleccionado, lo deselecciona: si no, Copiar pegaría
     * un número que ya no existe.
     */
    fun removeCard(value: String) {
        viewModelScope.launch {
            repo.remove(ContactKind.CARD, value)
            _ui.update {
                it.copy(
                    selectedCard = if (it.selectedCard == value) null else it.selectedCard,
                    editingCard = if (it.editingCard == value) null else it.editingCard,
                    message = null,
                )
            }
        }
    }

    fun removePhone(value: String) {
        viewModelScope.launch {
            repo.remove(ContactKind.PHONE, value)
            _ui.update {
                it.copy(
                    selectedPhone = if (it.selectedPhone == value) null else it.selectedPhone,
                    editingPhone = if (it.editingPhone == value) null else it.editingPhone,
                    message = null,
                )
            }
        }
    }

    fun removeApp(value: String) {
        viewModelScope.launch {
            repo.remove(ContactKind.APP, value)
            _ui.update {
                it.copy(
                    selectedApp = if (it.selectedApp == value) null else it.selectedApp,
                    editingApp = if (it.editingApp == value) null else it.editingApp,
                    message = null,
                )
            }
        }
    }

    fun selectCard(value: String) {
        _ui.update { it.copy(selectedCard = value, message = null) }
    }

    fun selectPhone(value: String) {
        _ui.update { it.copy(selectedPhone = value, message = null) }
    }

    fun selectApp(value: String) {
        _ui.update { it.copy(selectedApp = value, message = null) }
    }

    /**
     * El texto a copiar, o null si falta alguna de las tres partes. El botón
     * queda deshabilitado en ese caso, así que null es una carrera, no un
     * camino previsto.
     */
    fun copyPayload(): String? {
        val state = _ui.value
        val card = state.selectedCard ?: return null
        val phone = state.selectedPhone ?: return null
        val app = state.selectedApp ?: return null
        return ContactClipboard.build(card, phone, app)
    }

    private fun newItem(kind: String, value: String) = ContactoMetodo(
        id = "$kind:$value",
        kind = kind,
        value = value,
        createdAtIso = clock.nowIso(),
    )
}
