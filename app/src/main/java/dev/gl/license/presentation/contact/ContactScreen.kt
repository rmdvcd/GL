package dev.gl.license.presentation.contact

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gl.license.R
import dev.gl.license.domain.model.ContactKind
import dev.gl.license.presentation.components.GlCard
import dev.gl.license.presentation.components.GlPrimaryButton
import dev.gl.license.presentation.components.GlSecondaryButton
import dev.gl.license.presentation.components.ScreenHeader
import dev.gl.license.presentation.components.StatusBanner
import dev.gl.license.presentation.theme.GlDimens

/**
 * Recoge el dato de pago del cliente y lo deja en el portapapeles con el
 * formato que se pega donde lo piden.
 *
 * No guarda el CVC en ningún sitio, ni siquiera un momento: no se pide, no se
 * almacena y no sale en el texto copiado. El registro solo guarda tarjeta o
 * cuenta, teléfono y apps, en la misma base cifrada que las licencias.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactScreen(vm: ContactViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var cardDraft by remember { mutableStateOf("") }
    var phoneDraft by remember { mutableStateOf("") }
    var appDraft by remember { mutableStateOf("") }

    val ready = ui.selectedCard != null && ui.selectedPhone != null && ui.selectedApp != null
    val addCardLabel = stringResource(R.string.contact_add)
    val addPhoneLabel = stringResource(R.string.contact_add)
    val addAppLabel = stringResource(R.string.contact_add)
    val saveCardLabel = stringResource(R.string.contact_save)
    val savePhoneLabel = stringResource(R.string.contact_save)
    val saveAppLabel = stringResource(R.string.contact_save)
    val copyLabel = stringResource(R.string.contact_copy)
    val copiedMessage = stringResource(R.string.contact_copied)
    val copyContactDescription = stringResource(R.string.cd_copy_contact)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(GlDimens.screen)
            .semantics { contentDescription = "Contacto" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeader(
            title = stringResource(R.string.contact_title),
            supportingText = stringResource(R.string.contact_intro),
        )
        Spacer(Modifier.height(GlDimens.gap))

        GlCard {
            PickerBlock(
                label = stringResource(R.string.contact_card_label),
                options = ui.cards,
                draft = cardDraft,
                onDraftChange = {
                    cardDraft = it
                    copied = false
                    vm.clearFeedback()
                },
                addLabel = addCardLabel,
                saveLabel = saveCardLabel,
                editing = ui.editingCard != null,
                onAdd = {
                    vm.addCard(cardDraft)
                    cardDraft = ""
                },
                onEdit = { cardDraft = vm.beginEditCard(it); copied = false },
                onDelete = { value ->
                    vm.removeCard(value)
                    copied = false
                },
                onSelect = { value ->
                    vm.selectCard(value)
                    copied = false
                },
                selected = ui.selectedCard,
                savedMessage = if (ui.feedbackKind == ContactKind.CARD) ui.message else null,
                contentDescription = "Tarjeta o cuenta",
            )
        }
        Spacer(Modifier.height(12.dp))
        GlCard {
            PickerBlock(
                label = stringResource(R.string.contact_phone_label),
                options = ui.phones,
                draft = phoneDraft,
                onDraftChange = {
                    phoneDraft = it
                    copied = false
                    vm.clearFeedback()
                },
                addLabel = addPhoneLabel,
                saveLabel = savePhoneLabel,
                editing = ui.editingPhone != null,
                onAdd = {
                    vm.addPhone(phoneDraft)
                    phoneDraft = ""
                },
                onEdit = { phoneDraft = vm.beginEditPhone(it); copied = false },
                onDelete = { value ->
                    vm.removePhone(value)
                    copied = false
                },
                onSelect = { value ->
                    vm.selectPhone(value)
                    copied = false
                },
                selected = ui.selectedPhone,
                savedMessage = if (ui.feedbackKind == ContactKind.PHONE) ui.message else null,
                contentDescription = "Teléfono",
            )
        }
        Spacer(Modifier.height(12.dp))
        GlCard {
            PickerBlock(
                label = stringResource(R.string.contact_app_label),
                options = ui.apps,
                draft = appDraft,
                onDraftChange = {
                    appDraft = it
                    copied = false
                    vm.clearFeedback()
                },
                addLabel = addAppLabel,
                saveLabel = saveAppLabel,
                editing = ui.editingApp != null,
                onAdd = {
                    vm.addApp(appDraft)
                    appDraft = ""
                },
                onEdit = { appDraft = vm.beginEditApp(it); copied = false },
                onDelete = { value ->
                    vm.removeApp(value)
                    copied = false
                },
                onSelect = { value ->
                    vm.selectApp(value)
                    copied = false
                },
                selected = ui.selectedApp,
                savedMessage = if (ui.feedbackKind == ContactKind.APP) ui.message else null,
                contentDescription = "App",
            )
        }

        Spacer(Modifier.height(16.dp))
        if (!ready) {
            Text(
                stringResource(R.string.contact_need_both),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }
        GlPrimaryButton(
            text = copyLabel,
            onClick = {
                // null solo si el botón quedó pulsado sin selección, que el
                // enabled ya impide.
                vm.copyPayload()?.let {
                    copyToClipboard(context, it)
                    copied = true
                }
            },
            enabled = ready,
            modifier = Modifier.semantics { contentDescription = copyContactDescription },
        )
        if (copied) {
            Spacer(Modifier.height(8.dp))
            StatusBanner(error = null, success = copiedMessage)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Un combobox que además sirve para crear: se teclea en el mismo campo y el
 * botón «Añadir» lo guarda. Separar el desplegable del campo de entrada
 * duplicaría controles sin ganar nada, porque el valor tecleado siempre es el
 * que acaba guardándose.
 *
 * Cada fila lleva lápiz y papelera: el lápiz devuelve el valor al campo para
 * corregirlo (Añadir pasa a decir Guardar y sustituye al anterior), la
 * papelera pide confirmación antes de borrar porque no hay deshacer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerBlock(
    label: String,
    options: List<String>,
    draft: String,
    onDraftChange: (String) -> Unit,
    addLabel: String,
    saveLabel: String,
    editing: Boolean,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSelect: (String) -> Unit,
    selected: String?,
    savedMessage: String?,
    contentDescription: String,
) {
    var expanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    // El campo refleja lo elegido; lo tecleado vive en draft hasta que se añade.
    val shown = if (draft.isNotEmpty()) draft else selected ?: ""

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = shown,
                onValueChange = onDraftChange,
                singleLine = true,
                placeholder = {
                    Text(
                        if (options.isEmpty()) stringResource(R.string.contact_empty)
                        else options.first()
                    )
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
                    .semantics { this.contentDescription = contentDescription },
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (options.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.contact_empty)) },
                        onClick = { expanded = false },
                    )
                } else {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        option,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = {
                                        onEdit(option)
                                        expanded = false
                                    }) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "${stringResource(R.string.contact_edit)} $option",
                                        )
                                    }
                                    IconButton(onClick = {
                                        pendingDelete = option
                                        expanded = false
                                    }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "${stringResource(R.string.contact_delete)} $option",
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onSelect(option)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        GlSecondaryButton(
            text = if (editing) saveLabel else addLabel,
            onClick = onAdd,
            enabled = draft.isNotBlank(),
            // this. es obligatorio: el parámetro contentDescription tapa la
            // propiedad de semantics.
            modifier = Modifier.semantics { this.contentDescription = "Añadir a $label" },
        )
        if (savedMessage != null) {
            Spacer(Modifier.height(8.dp))
            StatusBanner(error = null, success = savedMessage)
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            text = { Text(stringResource(R.string.contact_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.contact_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.contact_cancel))
                }
            },
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("GL contacto", text))
}
