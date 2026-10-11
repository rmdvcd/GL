package dev.gl.license.presentation.generator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.LicenseMessage
import dev.gl.license.domain.model.PrecioTabla
import dev.gl.license.domain.model.SolicitudLicencia
import dev.gl.license.domain.model.SolicitudSpviV2
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.domain.repository.SpviPuerta
import dev.gl.license.domain.usecase.EmitirSpvi2UseCase
import dev.gl.license.domain.usecase.GenerarLicenciaUseCase
import dev.gl.license.domain.usecase.RegistrarLicenciaUseCase
import dev.gl.license.domain.usecase.ValidarSolicitudUseCase
import dev.gl.license.security.Spvi23
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GeneratorUi(
    val raw: String = "",
    val solicitud: SolicitudLicencia? = null,
    val paid: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val success: String? = null,
    val encryptedLicense: String? = null,
    val shareBody: String? = null,
    val spvi: SolicitudSpviV2? = null,
    val renuevaAviso: String? = null,
    val pending: Licencia? = null,
    val shareLaunched: Boolean = false,
    val canRegister: Boolean = false,
    val clipboardCaptured: Boolean = false,
)

sealed interface ShareEvent {
    data class WhatsApp(val phone: String, val body: String) : ShareEvent
    data class Sms(val phone: String, val body: String) : ShareEvent
}

@HiltViewModel
class GeneratorViewModel @Inject constructor(
    private val validate: ValidarSolicitudUseCase,
    private val generate: GenerarLicenciaUseCase,
    private val register: RegistrarLicenciaUseCase,
    private val abrirSpvi: SpviPuerta,
    private val emitirSpvi: EmitirSpvi2UseCase,
) : ViewModel() {

    private val _ui = MutableStateFlow(GeneratorUi())
    val ui: StateFlow<GeneratorUi> = _ui
    private val _share = MutableSharedFlow<ShareEvent>(extraBufferCapacity = 1)
    val share: SharedFlow<ShareEvent> = _share

    fun onRawChange(text: String) {
        _ui.update {
            it.copy(
                raw = text,
                error = null,
                success = null,
                paid = false,
                solicitud = null,
                spvi = null,
                renuevaAviso = null,
                canRegister = false,
                shareLaunched = false,
                encryptedLicense = null,
                shareBody = null,
                pending = null,
                clipboardCaptured = false,
            )
        }
        if (text.isBlank()) return
        if (Spvi23.contieneCodigo(text)) {
            viewModelScope.launch {
                when (val r = abrirSpvi.abrirSolicitud(text)) {
                    is Outcome.Ok -> _ui.update { it.copy(spvi = r.value, error = null) }
                    is Outcome.Err -> _ui.update { it.copy(spvi = null, error = r.error.userMessage) }
                }
            }
            return
        }
        viewModelScope.launch {
            when (val r = validate(text)) {
                is Outcome.Ok -> {
                    val solicitud = r.value.solicitud
                    if (esSpviV1(solicitud)) {
                        _ui.update {
                            it.copy(
                                solicitud = null,
                                error = AppError.SpviDesactualizada.userMessage,
                            )
                        }
                    } else {
                        _ui.update { it.copy(solicitud = solicitud, error = null) }
                    }
                }
                is Outcome.Err -> _ui.update {
                    it.copy(
                        solicitud = null,
                        error = if (mencionaSpvi(text)) {
                            AppError.SpviNoEs023.userMessage
                        } else {
                            r.error.userMessage
                        },
                    )
                }
            }
        }
    }

    private fun esSpviV1(solicitud: SolicitudLicencia): Boolean =
        solicitud.appName == "SPVI" || solicitud.deviceId.startsWith("SPVI:")

    /** Texto que se presenta como SPVI pero no contiene un código R1 extraíble. */
    private fun mencionaSpvi(raw: String): Boolean = raw.contains("SPVI", ignoreCase = true)

    fun onForegroundClipboard(text: String?, windowHasFocus: Boolean, resumed: Boolean) {
        if (!windowHasFocus || !resumed) return
        val t = text?.trim().orEmpty()
        if (t.isBlank() || t == _ui.value.raw) return
        if (!t.contains("\"alg\"") && !Spvi23.contieneCodigo(t)) return
        onRawChange(t)
        _ui.update { it.copy(clipboardCaptured = true) }
    }

    fun setPaid(v: Boolean) {
        if (_ui.value.solicitud != null || _ui.value.spvi != null) {
            _ui.update { it.copy(paid = v) }
        }
    }

    fun generateLicense() {
        if (!_ui.value.paid) return
        val spvi = _ui.value.spvi
        if (spvi != null) {
            generarSpvi(spvi)
            return
        }
        val req = _ui.value.solicitud ?: return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, success = null) }
            when (val r = generate(req, true)) {
                is Outcome.Err -> _ui.update { it.copy(loading = false, error = r.error.userMessage) }
                is Outcome.Ok -> {
                    val (lic, enc) = r.value
                    val total = lic.secundarias?.let { PrecioTabla.total(lic.type, it) }
                        ?: PrecioTabla.total(lic.type, 0)
                    val body = LicenseMessage.build(
                        lic.appName,
                        lic.type,
                        lic.secundarias,
                        total,
                        lic.id,
                        enc,
                    )
                    _ui.update {
                        it.copy(
                            loading = false,
                            encryptedLicense = enc,
                            shareBody = body,
                            pending = lic,
                            success = "Licencia generada.",
                            shareLaunched = true,
                            canRegister = false,
                        )
                    }
                    _share.emit(
                        when (req.channel) {
                            ViaSolicitud.WHATSAPP -> ShareEvent.WhatsApp(req.phone, body)
                            ViaSolicitud.SMS -> ShareEvent.Sms(req.phone, body)
                        }
                    )
                }
            }
        }
    }

    private fun generarSpvi(sol: SolicitudSpviV2) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, success = null) }
            when (val r = emitirSpvi(sol, true)) {
                is Outcome.Err -> _ui.update { it.copy(loading = false, error = r.error.userMessage) }
                is Outcome.Ok -> {
                    val emitida = r.value
                    val lic = emitida.licencia
                    _ui.update {
                        it.copy(
                            loading = false,
                            encryptedLicense = lic.codigoCorto,
                            shareBody = emitida.mensaje,
                            pending = lic,
                            renuevaAviso = if (emitida.renuevaIgnorada) {
                                "Renovación no aplicada: se emitió como licencia nueva."
                            } else {
                                null
                            },
                            success = "Licencia generada.",
                            shareLaunched = true,
                            canRegister = false,
                        )
                    }
                    _share.emit(
                        when (sol.via) {
                            ViaSolicitud.WHATSAPP -> ShareEvent.WhatsApp(sol.telefono, emitida.mensaje)
                            ViaSolicitud.SMS -> ShareEvent.Sms(sol.telefono, emitida.mensaje)
                        }
                    )
                }
            }
        }
    }

    fun onReturnedFromShare() {
        if (_ui.value.shareLaunched && _ui.value.pending != null) {
            _ui.update { it.copy(canRegister = true) }
        }
    }

    fun registerNow() {
        val lic = _ui.value.pending ?: return
        if (!_ui.value.canRegister) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            when (val r = register(lic)) {
                is Outcome.Ok -> _ui.update {
                    it.copy(
                        loading = false,
                        success = "Registrada.",
                        canRegister = false,
                        pending = null,
                        shareLaunched = false,
                    )
                }
                is Outcome.Err -> _ui.update { it.copy(loading = false, error = r.error.userMessage) }
            }
        }
    }
}
