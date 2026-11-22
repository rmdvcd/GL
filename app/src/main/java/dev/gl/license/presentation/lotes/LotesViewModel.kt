package dev.gl.license.presentation.lotes

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gl.license.core.Outcome
import dev.gl.license.data.local.*
import dev.gl.license.domain.model.*
import dev.gl.license.domain.usecase.CrearLoteCanjeUseCase
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject

@HiltViewModel
class LotesViewModel @Inject constructor(db: GlDatabase, private val mint: CrearLoteCanjeUseCase, private val json: Json) : ViewModel() {
    private val dao = db.batchDao()
    val batches = dao.headers()
    fun codes(id: String) = dao.codes(id)
    var selected by mutableStateOf("")
        private set
    var preview by mutableStateOf(emptyList<String>())
        private set
    var message by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    fun select(id: String) { preview = emptyList(); selected = id }
    /** Apps ofrecidas por el selector; el lote queda atado a la elegida. */
    val appOptions: List<String> get() = RedeemApps.allowed
    fun appNotice(app: String): String = RedeemApps.notice(app)
    fun create(app: String, count: Int, type: TipoLicencia, deadline: String?) {
        if (busy) return
        if (!RedeemApps.isAllowed(app)) { message = "Elige una app de la lista."; return }
        if (count !in 1..500) { message = "Indica entre 1 y 500 códigos."; return }
        busy = true
        viewModelScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) {
                    val r = mint(app, count, type, redeemBeforeIso=deadline)
                    val b = when (r) { is Outcome.Err -> throw r.error; is Outcome.Ok -> r.value }
                    val sha = MessageDigest.getInstance("SHA-256")
                    dao.save(SignedBatch(b.batchId, app, b.createdAt, json.encodeToString(RedeemBatch.serializer(), b)),
                        b.entries.map { BatchCode(Base64.getUrlEncoder().withoutPadding().encodeToString(sha.digest(it.code.toByteArray())), b.batchId, it.code, "LIBRE", "") })
                    b
                }
                selected = batch.batchId; preview = batch.entries.map { it.code }; message = "Lote guardado. Exporta el archivo por un canal seguro."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "No se pudo crear el lote. Revisa la cantidad y la fecha UTC." }
            finally { busy = false }
        }
    }
    suspend fun customerMessage(batchId: String, code: String): String? = withContext(Dispatchers.IO) {
        try {
            val batch = json.decodeFromString(RedeemBatch.serializer(), dao.payload(batchId))
            batch.entries.singleOrNull { it.code == code }?.let(RedeemCodeMessage::build)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    suspend fun export(id: String, write: (String)->Unit) {
        message = try { withContext(Dispatchers.IO) { write(dao.payload(id)) }; "Lote exportado." }
            catch (e: CancellationException) { throw e } catch (_: Exception) { "No se pudo guardar el lote." }
    }
    fun deliver(hash: String, note: String) { viewModelScope.launch {
        message = try { if (note.isNotBlank() && dao.deliver(hash,note.trim().take(160)) == 1) "Entrega registrada." else "Revisa la nota y el estado." }
        catch (_: Exception) { "No se pudo registrar la entrega." }
    } }
}
