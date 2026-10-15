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
import dev.gl.license.domain.repository.LicenciaRepository
import dev.gl.license.domain.repository.SecureClock
import dev.gl.license.domain.repository.SpviPuerta
import dev.gl.license.domain.usecase.EmitirSpvi2UseCase
import dev.gl.license.domain.usecase.EvaluacionHistorial
import dev.gl.license.domain.usecase.EvaluarSolicitudHistorialUseCase
import dev.gl.license.domain.usecase.GenerarLicenciaUseCase
import dev.gl.license.domain.usecase.RegistrarLicenciaUseCase
import dev.gl.license.domain.usecase.ValidarSolicitudUseCase
import dev.gl.license.security.Spvi23
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val MAX_RAW_REQUEST_CHARS = Spvi23.MAX_REQUEST_TEXT_CHARS
private const val MAX_TRANSACTION_CHARS = 128

private fun usableTransaction(value: String): Boolean =
    value.isNotBlank() && value.length <= MAX_TRANSACTION_CHARS && value.none { it.isISOControl() }

data class GeneratorUi(
    val raw: String = "",
    val solicitud: SolicitudLicencia? = null,
    val txCliente: String = "",
    val txSms: String = "",
    val paid: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val success: String? = null,
    val infoAviso: String? = null,
    val diasRemanentes: Long = 0L,
    val encryptedLicense: String? = null,
    val shareBody: String? = null,
    val spvi: SolicitudSpviV2? = null,
    val renuevaAviso: String? = null,
    val pending: Licencia? = null,
    val shareLaunched: Boolean = false,
    val canRegister: Boolean = false,
    val clipboardCaptured: Boolean = false,
) {
    val txMatch: Boolean
        get() = usableTransaction(txCliente) && usableTransaction(txSms) && txCliente.trim() == txSms.trim()
}

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
    private val evaluarHistorial: EvaluarSolicitudHistorialUseCase,
) : ViewModel() {

    // Constructor secundario para compatibilidad con tests unitarios
    constructor(
        validate: ValidarSolicitudUseCase,
        generate: GenerarLicenciaUseCase,
        register: RegistrarLicenciaUseCase,
        abrirSpvi: SpviPuerta,
        emitirSpvi: EmitirSpvi2UseCase,
    ) : this(
        validate,
        generate,
        register,
        abrirSpvi,
        emitirSpvi,
        EvaluarSolicitudHistorialUseCase(
            object : LicenciaRepository {
                override fun observeAll() = kotlinx.coroutines.flow.emptyFlow<List<Licencia>>()
                override suspend fun upsert(license: Licencia) = Outcome.Ok(Unit)
                override suspend fun getById(id: String): Licencia? = null
                override suspend fun merge(incoming: List<Licencia>) = Outcome.Ok(0)
                override suspend fun all(): List<Licencia> = emptyList()
            },
            object : SecureClock {
                override fun nowEpochMillis() = System.currentTimeMillis()
                override fun nowIso() = java.time.Instant.now().toString()
            }
        ),
    )

    private val _ui = MutableStateFlow(GeneratorUi())
    val ui: StateFlow<GeneratorUi> = _ui
    private var rawValidationJob: Job? = null
    private var rawValidationGeneration = 0L
    private var generationGeneration = 0L
    private var generationJob: Job? = null
    private val _share = MutableSharedFlow<ShareEvent>(extraBufferCapacity = 1)
    val share: SharedFlow<ShareEvent> = _share

    fun onRawChange(text: String) {
        rawValidationJob?.cancel()
        val validationGeneration = ++rawValidationGeneration
        generationGeneration++
        generationJob?.cancel()
        val raw = text.take(MAX_RAW_REQUEST_CHARS)
        _ui.update {
            it.copy(
                raw = raw,
                loading = false,
                error = null,
                success = null,
                infoAviso = null,
                diasRemanentes = 0L,
                txCliente = "",
                txSms = "",
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
        if (text.length > MAX_RAW_REQUEST_CHARS) {
            _ui.update { it.copy(error = AppError.InvalidPayload.userMessage) }
            return
        }
        if (raw.isBlank()) return

        rawValidationJob = viewModelScope.launch {
            if (Spvi23.contieneCodigo(raw)) {
                when (val r = abrirSpvi.abrirSolicitud(raw)) {
                    is Outcome.Ok -> if (validationGeneration == rawValidationGeneration) {
                        _ui.update { it.copy(spvi = r.value, error = null) }
                    }
                    is Outcome.Err -> if (validationGeneration == rawValidationGeneration) {
                        _ui.update { it.copy(spvi = null, error = r.error.userMessage) }
                    }
                }
                return@launch
            }

            when (val r = validate(raw)) {
                is Outcome.Err -> if (validationGeneration == rawValidationGeneration) {
                    _ui.update {
                        it.copy(
                            solicitud = null,
                            error = if (mencionaSpvi(raw)) {
                                AppError.SpviNoEs023.userMessage
                            } else {
                                r.error.userMessage
                            },
                        )
                    }
                }
                is Outcome.Ok -> {
                    if (validationGeneration != rawValidationGeneration) return@launch
                    val solicitud = r.value.solicitud
                    if (esSpviV1(solicitud)) {
                        _ui.update {
                            it.copy(
                                solicitud = null,
                                error = AppError.SpviDesactualizada.userMessage,
                            )
                        }
                        return@launch
                    }
                    val eval = evaluarHistorial(solicitud)
                    if (validationGeneration != rawValidationGeneration) return@launch
                    when (eval) {
                        is EvaluacionHistorial.ErrorEvaluacion -> _ui.update {
                            it.copy(solicitud = null, error = eval.error)
                        }
                        is EvaluacionHistorial.RechazadaYaPerpetua -> _ui.update {
                            it.copy(solicitud = null, error = eval.error)
                        }
                        is EvaluacionHistorial.ActivaMismoTipo -> _ui.update {
                            it.copy(
                                solicitud = solicitud,
                                error = null,
                                infoAviso = eval.aviso,
                                diasRemanentes = eval.diasRestantes,
                            )
                        }
                        is EvaluacionHistorial.ActivaDiferente -> _ui.update {
                            it.copy(
                                solicitud = solicitud,
                                error = null,
                                infoAviso = eval.aviso,
                                diasRemanentes = eval.diasRestantes,
                            )
                        }
                        is EvaluacionHistorial.Revalidacion -> _ui.update {
                            it.copy(
                                solicitud = solicitud,
                                error = null,
                                infoAviso = eval.aviso,
                                diasRemanentes = 0L,
                            )
                        }
                        is EvaluacionHistorial.RenovacionCambio -> _ui.update {
                            it.copy(
                                solicitud = solicitud,
                                error = null,
                                infoAviso = eval.aviso,
                                diasRemanentes = 0L,
                            )
                        }
                        is EvaluacionHistorial.NuevaSolicitud -> _ui.update {
                            it.copy(
                                solicitud = solicitud,
                                error = null,
                                infoAviso = null,
                                diasRemanentes = 0L,
                            )
                        }
                    }
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

    fun onTxClienteChange(text: String) {
        generationGeneration++
        generationJob?.cancel()
        val safeText = text.take(MAX_TRANSACTION_CHARS + 1)
        _ui.update { current ->
            val match = usableTransaction(safeText) &&
                usableTransaction(current.txSms) &&
                safeText.trim() == current.txSms.trim()
            current.copy(
                txCliente = safeText,
                paid = if (!match) false else current.paid,
                loading = false,
            )
        }
    }

    fun onTxSmsChange(text: String) {
        generationGeneration++
        generationJob?.cancel()
        val safeText = text.take(MAX_TRANSACTION_CHARS + 1)
        _ui.update { current ->
            val match = usableTransaction(current.txCliente) &&
                usableTransaction(safeText) &&
                current.txCliente.trim() == safeText.trim()
            current.copy(
                txSms = safeText,
                paid = if (!match) false else current.paid,
                loading = false,
            )
        }
    }

    fun setPaid(v: Boolean) {
        val current = _ui.value
        if (!current.txMatch) return
        if (current.solicitud != null || current.spvi != null) {
            generationGeneration++
            generationJob?.cancel()
            _ui.update { it.copy(paid = v, loading = false) }
        }
    }

    fun generateLicense() {
        val current = _ui.value
        if (!current.paid || !current.txMatch || current.loading ||
            current.pending != null || current.encryptedLicense != null ||
            generationJob?.isCompleted == false
        ) return
        val generation = ++generationGeneration
        val tx = current.txCliente.trim()
        _ui.update { it.copy(loading = true, error = null, success = null) }
        generationJob = viewModelScope.launch {
            try {
                val spvi = current.spvi
                if (spvi != null) {
                    when (val r = emitirSpvi(spvi, pagoConfirmado = true, txNumber = tx)) {
                        is Outcome.Err -> if (isCurrentGeneration(generation, current.raw, tx)) {
                            _ui.update { it.copy(error = r.error.userMessage) }
                        }
                        is Outcome.Ok -> {
                            if (!isCurrentGeneration(generation, current.raw, tx)) return@launch
                            val emitida = r.value
                            val lic = emitida.licencia
                            _ui.update {
                                it.copy(
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
                            if (isCurrentGeneration(generation, current.raw, tx)) {
                                _share.emit(
                                    when (spvi.via) {
                                        ViaSolicitud.WHATSAPP -> ShareEvent.WhatsApp(spvi.telefono, emitida.mensaje)
                                        ViaSolicitud.SMS -> ShareEvent.Sms(spvi.telefono, emitida.mensaje)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    val req = current.solicitud ?: return@launch
                    when (val r = generate(
                        req,
                        paymentConfirmed = true,
                        diasAdicionales = current.diasRemanentes,
                        txNumber = tx,
                    )) {
                        is Outcome.Err -> if (isCurrentGeneration(generation, current.raw, tx)) {
                            _ui.update { it.copy(error = r.error.userMessage) }
                        }
                        is Outcome.Ok -> {
                            if (!isCurrentGeneration(generation, current.raw, tx)) return@launch
                            val (lic, enc) = r.value
                            val total = lic.secundarias?.let { PrecioTabla.total(lic.type, it) }
                                ?: PrecioTabla.total(lic.type, 0)
                            val body = LicenseMessage.build(
                                appName = lic.appName,
                                tipo = lic.type,
                                secundarias = lic.secundarias,
                                total = total,
                                licenseId = lic.id,
                                envelope = enc,
                                codigoLicencia = lic.codigoCorto,
                            )
                            _ui.update {
                                it.copy(
                                    encryptedLicense = enc,
                                    shareBody = body,
                                    pending = lic,
                                    success = "Licencia generada: ${lic.codigoCorto ?: lic.id}",
                                    shareLaunched = true,
                                    canRegister = false,
                                )
                            }
                            if (isCurrentGeneration(generation, current.raw, tx)) {
                                _share.emit(
                                    when (req.effectiveChannel) {
                                        ViaSolicitud.WHATSAPP -> ShareEvent.WhatsApp(req.effectivePhone, body)
                                        ViaSolicitud.SMS -> ShareEvent.Sms(req.effectivePhone, body)
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == generationGeneration) {
                    _ui.update { it.copy(error = AppError.Generic.userMessage) }
                }
            } finally {
                if (generation == generationGeneration) {
                    _ui.update { it.copy(loading = false) }
                    generationJob = null
                }
            }
        }
    }

    private fun isCurrentGeneration(generation: Long, raw: String, tx: String): Boolean {
        val state = _ui.value
        return generation == generationGeneration &&
            state.raw == raw &&
            state.txCliente.trim() == tx &&
            state.paid &&
            state.txMatch
    }

    fun onReturnedFromShare() {
        if (_ui.value.shareLaunched && _ui.value.pending != null) {
            _ui.update { it.copy(canRegister = true) }
        }
    }

    fun registerNow() {
        val current = _ui.value
        val lic = current.pending ?: return
        if (!current.canRegister || current.loading) return
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
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
