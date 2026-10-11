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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
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
import dev.gl.license.presentation.components.ScreenHeader
import dev.gl.license.presentation.components.SectionLabel
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
    val clipboardDetectedDescription = stringResource(R.string.cd_clipboard_detected)
    val encryptedRequestDescription = stringResource(R.string.cd_encrypted_request)

    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
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
                // Si no hay app de destino, la licencia sigue disponible para registrar.
                vm.onReturnedFromShare()
            }
        }
    }

    DisposableEffect(screenVisible, owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && screenVisible && view.hasWindowFocus()) {
                vm.onForegroundClipboard(
                    clip.getText()?.toString(),
                    windowHasFocus = true,
                    resumed = true,
                )
            }
            if (event == Lifecycle.Event.ON_RESUME) vm.onReturnedFromShare()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(GlDimens.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeader(
            title = stringResource(R.string.tab_generator),
            supportingText = stringResource(R.string.generator_help),
        )
        Spacer(Modifier.height(GlDimens.gap))
        SectionLabel(stringResource(R.string.generator_request_step))
        Spacer(Modifier.height(8.dp))

        AnimatedVisibility(
            visible = ui.clipboardCaptured,
            enter = fadeIn(tween(GlMotion.normal)),
            exit = fadeOut(tween(GlMotion.fast)),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                SuggestionChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.clipboard_detected)) },
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = clipboardDetectedDescription
                    },
                )
            }
        }

        OutlinedTextField(
            value = ui.raw,
            onValueChange = vm::onRawChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GlDimens.field)
                .semantics { contentDescription = encryptedRequestDescription },
            label = { Text(stringResource(R.string.encrypted_request)) },
            placeholder = { Text(stringResource(R.string.paste_hint)) },
            minLines = 4,
        )
        Spacer(Modifier.height(8.dp))
        GlSecondaryButton(
            text = stringResource(R.string.pegar),
            onClick = { vm.onRawChange(clip.getText()?.toString().orEmpty()) },
        )
        Spacer(Modifier.height(8.dp))
        StatusBanner(error = ui.error, success = ui.success)

        if (ui.raw.isBlank() && ui.solicitud == null && ui.spvi == null) {
            EmptyState(
                title = stringResource(R.string.generator_empty_title),
                body = stringResource(R.string.generator_empty_body),
            )
        }

        AnimatedVisibility(
            visible = ui.solicitud != null,
            enter = fadeIn(tween(GlMotion.normal)),
            exit = fadeOut(tween(GlMotion.fast)),
        ) {
            ui.solicitud?.let { request ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(GlDimens.gap))
                    V1RequestCard(
                        firstName = request.firstName,
                        lastName = request.lastName,
                        nationalId = request.nationalId,
                        channel = request.channel.name,
                        phone = request.phone,
                        deviceId = request.deviceId,
                        appName = request.appName,
                        type = request.type.name,
                        requestedAt = request.requestedAtIso,
                    )
                    Spacer(Modifier.height(GlDimens.gap))
                    PaymentAndActions(
                        paid = ui.paid,
                        loading = ui.loading,
                        hasEncryptedLicense = ui.encryptedLicense != null,
                        canRegister = ui.canRegister,
                        onPaidChange = vm::setPaid,
                        onGenerate = vm::generateLicense,
                        onRegister = vm::registerNow,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = ui.spvi != null,
            enter = fadeIn(tween(GlMotion.normal)),
            exit = fadeOut(tween(GlMotion.fast)),
        ) {
            ui.spvi?.let { request ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(GlDimens.gap))
                    SpviRequestCard(
                        firstName = request.nombre,
                        lastName = request.apellidos,
                        nationalId = request.ci,
                        channel = request.via.name,
                        phone = request.telefono,
                        deviceId = request.deviceId,
                        type = request.tipo.name,
                        requestedAt = request.solicitadaEn,
                        secundarias = request.secundarias,
                        priceBreakdown = PrecioTabla.desglose(request.tipo, request.secundarias),
                        renewalNotice = ui.renuevaAviso,
                    )
                    Spacer(Modifier.height(GlDimens.gap))
                    PaymentAndActions(
                        paid = ui.paid,
                        loading = ui.loading,
                        hasEncryptedLicense = ui.encryptedLicense != null,
                        canRegister = ui.canRegister,
                        onPaidChange = vm::setPaid,
                        onGenerate = vm::generateLicense,
                        onRegister = vm::registerNow,
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
                GlCard {
                    SectionLabel(stringResource(R.string.generator_response_step))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ui.shareBody.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.generated_message)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    GlSecondaryButton(
                        text = stringResource(R.string.copiar_mensaje),
                        onClick = { ui.shareBody?.let { clip.setText(AnnotatedString(it)) } },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun V1RequestCard(
    firstName: String,
    lastName: String,
    nationalId: String,
    channel: String,
    phone: String,
    deviceId: String,
    appName: String,
    type: String,
    requestedAt: String,
) {
    GlCard {
        RequestCardTitle()
        MetaRow(stringResource(R.string.field_name), "$firstName $lastName")
        MetaRow(stringResource(R.string.field_national_id), nationalId)
        MetaRow(stringResource(R.string.field_channel), channel)
        MetaRow(stringResource(R.string.field_phone), phone)
        MetaRow(stringResource(R.string.field_device), deviceId)
        MetaRow(stringResource(R.string.field_app), appName)
        MetaRow(stringResource(R.string.field_type), type)
        MetaRow(stringResource(R.string.field_requested_at), requestedAt)
    }
}

@Composable
private fun SpviRequestCard(
    firstName: String,
    lastName: String,
    nationalId: String,
    channel: String,
    phone: String,
    deviceId: String,
    type: String,
    requestedAt: String,
    secundarias: Int,
    priceBreakdown: String,
    renewalNotice: String?,
) {
    GlCard {
        RequestCardTitle()
        MetaRow(stringResource(R.string.field_name), "$firstName $lastName")
        MetaRow(stringResource(R.string.field_national_id), nationalId)
        MetaRow(stringResource(R.string.field_channel), channel)
        MetaRow(stringResource(R.string.field_phone), phone)
        MetaRow(stringResource(R.string.field_device), deviceId)
        MetaRow(stringResource(R.string.field_app), "SPVI")
        MetaRow(stringResource(R.string.field_type), type)
        MetaRow(stringResource(R.string.field_requested_at), requestedAt)
        MetaRow(stringResource(R.string.field_secondary_apps), secundarias.toString())
        Spacer(Modifier.height(4.dp))
        Text(
            text = priceBreakdown,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        if (renewalNotice != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = renewalNotice,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RequestCardTitle() {
    Text(
        text = stringResource(R.string.request_valid),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun PaymentAndActions(
    paid: Boolean,
    loading: Boolean,
    hasEncryptedLicense: Boolean,
    canRegister: Boolean,
    onPaidChange: (Boolean) -> Unit,
    onGenerate: () -> Unit,
    onRegister: () -> Unit,
) {
    GlCard(contentAlignment = Alignment.CenterHorizontally) {
        SectionLabel(stringResource(R.string.generator_payment_step))
        Text(
            text = stringResource(R.string.generator_payment_supporting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GlDimens.touch)
                .toggleable(
                    value = paid,
                    role = Role.Checkbox,
                    onValueChange = onPaidChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Checkbox(checked = paid, onCheckedChange = null)
            Text(stringResource(R.string.payment_done))
        }
    }
    Spacer(Modifier.height(8.dp))
    GlPrimaryButton(
        text = stringResource(R.string.generate_license),
        onClick = onGenerate,
        enabled = paid && !loading,
        loading = loading && !hasEncryptedLicense,
    )
    Spacer(Modifier.height(8.dp))
    GlSecondaryButton(
        text = stringResource(if (canRegister) R.string.register_license else R.string.register_wait),
        onClick = onRegister,
        enabled = canRegister && !loading,
    )
}
