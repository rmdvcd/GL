package dev.gl.license.presentation.generator

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gl.license.R
import dev.gl.license.domain.model.PrecioTabla
import dev.gl.license.presentation.components.EmptyState
import dev.gl.license.presentation.components.GlCard
import dev.gl.license.presentation.components.GlPrimaryButton
import dev.gl.license.presentation.components.GlSecondaryButton
import dev.gl.license.presentation.components.MetaRow
import dev.gl.license.presentation.components.ScreenTitle
import dev.gl.license.presentation.components.StatusBanner
import dev.gl.license.presentation.theme.GlDimens
import dev.gl.license.presentation.theme.GlMotion
import kotlinx.coroutines.flow.collectLatest

@Composable
fun GeneratorScreen(
    vm: GeneratorViewModel = hiltViewModel(),
    screenVisible: Boolean = true,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val clip = LocalClipboardManager.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current

    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.onReturnedFromShare() }

    LaunchedEffect(Unit) {
        vm.share.collectLatest { ev ->
            val intent = when (ev) {
                is ShareEvent.WhatsApp -> {
                    val phone = ev.phone.filter { it.isDigit() }
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=${Uri.encode(ev.body)}"))
                }
                is ShareEvent.Sms -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${ev.phone}")).apply {
                    putExtra("sms_body", ev.body)
                }
            }
            try {
                shareLauncher.launch(intent)
            } catch (_: Exception) {
                vm.onReturnedFromShare()
            }
        }
    }

    DisposableEffect(screenVisible, owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && screenVisible && view.hasWindowFocus()) {
                vm.onForegroundClipboard(clip.getText()?.toString(), windowHasFocus = true, resumed = true)
            }
            if (e == Lifecycle.Event.ON_RESUME) vm.onReturnedFromShare()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(GlDimens.screen),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ScreenTitle(stringResource(R.string.tab_generator))
        Text(
            stringResource(R.string.generator_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(GlDimens.gap))
        AnimatedVisibility(
            visible = ui.clipboardCaptured,
            enter = fadeIn(tween(GlMotion.normal)),
            exit = fadeOut(tween(GlMotion.fast)),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                SuggestionChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.clipboard_detected)) },
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "Solicitud detectada del portapapeles"
                    }
                )
            }
        }
        OutlinedTextField(
            value = ui.raw,
            onValueChange = vm::onRawChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GlDimens.field)
                .semantics { contentDescription = "Solicitud cifrada" },
            label = { Text(stringResource(R.string.encrypted_request)) },
            placeholder = { Text(stringResource(R.string.paste_hint)) }
        )
        Spacer(Modifier.height(8.dp))
        GlSecondaryButton(
            text = stringResource(R.string.pegar),
            onClick = { vm.onRawChange(clip.getText()?.toString().orEmpty()) }
        )
        Spacer(Modifier.height(8.dp))
        StatusBanner(error = ui.error, success = ui.success)
        if (ui.raw.isBlank() && ui.solicitud == null && ui.spvi == null) {
            EmptyState(
                stringResource(R.string.generator_empty_title),
                stringResource(R.string.generator_empty_body)
            )
        }
        AnimatedVisibility(
            visible = ui.solicitud != null,
            enter = fadeIn(tween(GlMotion.normal)),
            exit = fadeOut(tween(GlMotion.fast)),
        ) {
            ui.solicitud?.let { r ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(GlDimens.gap))
                    GlCard {
                        Text(
                            stringResource(R.string.request_valid),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        MetaRow("Nombre", "${r.firstName} ${r.lastName}")
                        MetaRow("CI", r.nationalId)
                        MetaRow("Vía", r.channel.name)
                        MetaRow("Tel", r.phone)
                        MetaRow("Dispositivo", r.deviceId)
                        MetaRow("App", r.appName)
                        MetaRow("Tipo", r.type.name)
                        MetaRow("Fecha", r.requestedAtIso)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(GlDimens.touch)
                            .toggleable(value = ui.paid, role = Role.Checkbox, onValueChange = vm::setPaid),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Checkbox(checked = ui.paid, onCheckedChange = null)
                        Text(stringResource(R.string.payment_done))
                    }
                    GlPrimaryButton(
                        text = stringResource(R.string.generate_license),
                        onClick = vm::generateLicense,
                        enabled = ui.paid && !ui.loading,
                        loading = ui.loading && ui.encryptedLicense == null
                    )
                    Spacer(Modifier.height(8.dp))
                    GlSecondaryButton(
                        text = stringResource(
                            if (ui.canRegister) R.string.register_license else R.string.register_wait
                        ),
                        onClick = vm::registerNow,
                        enabled = ui.canRegister && !ui.loading
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = ui.spvi != null,
            enter = fadeIn(tween(GlMotion.normal)),
            exit = fadeOut(tween(GlMotion.fast)),
        ) {
            ui.spvi?.let { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(GlDimens.gap))
                    GlCard {
                        Text(
                            stringResource(R.string.request_valid),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        MetaRow("Nombre", "${s.nombre} ${s.apellidos}")
                        MetaRow("CI", s.ci)
                        MetaRow("Vía", s.via.name)
                        MetaRow("Tel", s.telefono)
                        MetaRow("Dispositivo", s.deviceId)
                        MetaRow("App", "SPVI")
                        MetaRow("Tipo", s.tipo.name)
                        MetaRow("Fecha", s.solicitadaEn)
                        MetaRow("Apps secundarias", s.secundarias.toString())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            PrecioTabla.desglose(s.tipo, s.secundarias),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        if (ui.renuevaAviso != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                ui.renuevaAviso.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(GlDimens.touch)
                            .toggleable(value = ui.paid, role = Role.Checkbox, onValueChange = vm::setPaid),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Checkbox(checked = ui.paid, onCheckedChange = null)
                        Text(stringResource(R.string.payment_done))
                    }
                    GlPrimaryButton(
                        text = stringResource(R.string.generate_license),
                        onClick = vm::generateLicense,
                        enabled = ui.paid && !ui.loading,
                        loading = ui.loading && ui.encryptedLicense == null
                    )
                    Spacer(Modifier.height(8.dp))
                    GlSecondaryButton(
                        text = stringResource(
                            if (ui.canRegister) R.string.register_license else R.string.register_wait
                        ),
                        onClick = vm::registerNow,
                        enabled = ui.canRegister && !ui.loading
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = ui.spvi != null && ui.shareBody != null,
            enter = fadeIn(tween(GlMotion.normal)),
            exit = fadeOut(tween(GlMotion.fast)),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(GlDimens.gap))
                OutlinedTextField(
                    value = ui.shareBody.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.generated_message)) }
                )
                Spacer(Modifier.height(8.dp))
                GlSecondaryButton(
                    text = stringResource(R.string.copiar_mensaje),
                    onClick = { ui.shareBody?.let { clip.setText(AnnotatedString(it)) } }
                )
            }
        }
    }
}
