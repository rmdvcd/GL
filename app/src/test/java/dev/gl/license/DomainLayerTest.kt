package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.RegistroLicencia
import dev.gl.license.domain.model.SolicitudLicencia
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.domain.repository.LicenciaRepository
import dev.gl.license.domain.repository.RegistroRepository
import dev.gl.license.domain.repository.SecureClock
import dev.gl.license.domain.repository.SeguridadRepository
import dev.gl.license.domain.usecase.ExportarRegistroUseCase
import dev.gl.license.domain.usecase.FieldValidator
import dev.gl.license.domain.usecase.GenerarLicenciaUseCase
import dev.gl.license.domain.usecase.ImportarRegistroUseCase
import dev.gl.license.domain.usecase.RegistrarLicenciaUseCase
import dev.gl.license.domain.usecase.ValidarSolicitudUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

private const val TEST_PUB_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEq+BkCJzxNvUEV6J0XQyY0g71EvOfZ87syGdhURoMlOdgWFlEVs2aDxsGlia4Df+ZS6LPE6dmfCpCGZhSoC6J1Q=="

internal fun req() = SolicitudLicencia(
    version = CONTRACT_VERSION,
    firstName = "Ana",
    lastName = "Perez",
    nationalId = "85010112345",
    channel = ViaSolicitud.WHATSAPP,
    phone = "+5355512345",
    appName = "MiApp",
    deviceId = "device-abc-12345",
    type = TipoLicencia.MENSUAL,
    requestedAtIso = "2026-01-01T00:00:00Z",
    nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
    devicePublicKeyPem = TEST_PUB_KEY,
)

internal class MemLic : LicenciaRepository {
    private val map = LinkedHashMap<String, Licencia>()
    private val flow = MutableStateFlow<List<Licencia>>(emptyList())
    private fun pub() { flow.value = map.values.sortedByDescending { it.issuedAtIso } }
    override fun observeAll(): Flow<List<Licencia>> = flow
    override suspend fun upsert(license: Licencia): Outcome<Unit> {
        map[license.id] = license; pub(); return Outcome.Ok(Unit)
    }
    override suspend fun getById(id: String) = map[id]
    override suspend fun merge(incoming: List<Licencia>): Outcome<Int> {
        var n = 0
        incoming.forEach {
            val old = map[it.id]
            if (old == null || it.issuedAtIso >= old.issuedAtIso) {
                map[it.id] = it; n++
            }
        }
        pub(); return Outcome.Ok(n)
    }
    override suspend fun all() = map.values.toList()
    override suspend fun isTransactionUsed(txNumber: String): Boolean =
        map.values.any { it.txNumber != null && it.txNumber.equals(txNumber.trim(), ignoreCase = true) }
}

internal class MemReg : RegistroRepository {
    private val map = LinkedHashMap<String, RegistroLicencia>()
    private val flow = MutableStateFlow<List<RegistroLicencia>>(emptyList())
    override fun observe(): Flow<List<RegistroLicencia>> = flow
    override suspend fun registrar(registro: RegistroLicencia): Outcome<Unit> {
        map[registro.licencia.id] = registro
        flow.value = map.values.toList()
        return Outcome.Ok(Unit)
    }
    override suspend fun mergePorId(incoming: List<RegistroLicencia>): Outcome<Int> {
        incoming.forEach { map[it.licencia.id] = it }
        flow.value = map.values.toList()
        return Outcome.Ok(incoming.size)
    }
    override suspend fun todos() = map.values.toList()
}

internal class MemSeg : SeguridadRepository {
    var lastPlain: Licencia? = null
    var blob: ByteArray = byteArrayOf(1, 2, 3)
    override fun descifrarSolicitud(raw: String) =
        if (raw == "ok") Outcome.Ok(req()) else Outcome.Err(dev.gl.license.core.AppError.InvalidPayload)
    override fun cifrarLicencia(license: Licencia, devicePubPem: String): Outcome<String> {
        lastPlain = license
        return Outcome.Ok("ENVELOPE")
    }
    override fun exportar(licencias: List<Licencia>) = Outcome.Ok(blob)
    override fun importar(bytes: ByteArray): Outcome<List<Licencia>> {
        return if (bytes.contentEquals(blob) && lastPlain != null) Outcome.Ok(listOf(lastPlain!!))
        else Outcome.Err(dev.gl.license.core.AppError.ImportFailure)
    }
}

internal object FixedClock : SecureClock {
    override fun nowEpochMillis() = 1_767_225_600_000L
    override fun nowIso() = "2026-01-01T00:00:00Z"
}

class DomainLayerTest {

    @Test
    fun validadores() {
        assertThat(FieldValidator.validateRequest(req())).isInstanceOf(Outcome.Ok::class.java)
        assertThat(FieldValidator.validateRequest(req().copy(phone = "1")))
            .isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun relojInvalidoBloqueaValidacionYEmision() = runTest {
        val invalidClock = object : SecureClock {
            override fun nowEpochMillis(): Long = error("reloj no disponible")
            override fun nowIso(): String = "fecha-invalida"
        }
        val seg = MemSeg()

        val validation = ValidarSolicitudUseCase(seg, invalidClock)("ok")
        assertThat(validation).isInstanceOf(Outcome.Err::class.java)
        assertThat((validation as Outcome.Err).error)
            .isEqualTo(dev.gl.license.core.AppError.ClockFailure)

        val generation = GenerarLicenciaUseCase(seg, invalidClock, MemLic())(req(), true)
        assertThat(generation).isInstanceOf(Outcome.Err::class.java)
        assertThat((generation as Outcome.Err).error)
            .isEqualTo(dev.gl.license.core.AppError.ClockFailure)

        val validLicense = (GenerarLicenciaUseCase(seg, FixedClock, MemLic())(req(), true) as Outcome.Ok)
            .value.first
        val registrationRepository = MemLic()
        val registration = RegistrarLicenciaUseCase(registrationRepository, MemReg(), invalidClock)(validLicense)
        assertThat(registration).isInstanceOf(Outcome.Err::class.java)
        assertThat((registration as Outcome.Err).error)
            .isEqualTo(dev.gl.license.core.AppError.ClockFailure)
        assertThat(registrationRepository.all()).isEmpty()

        val archiveCrypto = MemSeg().apply { lastPlain = validLicense }
        val importRepository = MemLic()
        val imported = ImportarRegistroUseCase(archiveCrypto, importRepository, MemReg(), invalidClock)(archiveCrypto.blob)
        assertThat(imported).isInstanceOf(Outcome.Err::class.java)
        assertThat((imported as Outcome.Err).error)
            .isEqualTo(dev.gl.license.core.AppError.ClockFailure)
        assertThat(importRepository.all()).isEmpty()
    }

    @Test
    fun generarRequierePagoYCalculaVencimiento() = runTest {
        val seg = MemSeg()
        val uc = GenerarLicenciaUseCase(seg, FixedClock)
        assertThat(uc(req(), false)).isInstanceOf(Outcome.Err::class.java)
        val ok = uc(req(), true, idFactory = { "fixed-id" }) as Outcome.Ok
        assertThat(ok.value.first.expiresAtIso).isEqualTo("2026-01-31T00:00:00Z")
        assertThat(ok.value.first.status).isEqualTo(EstadoLicencia.ACTIVA)
        assertThat(ok.value.second).isEqualTo("ENVELOPE")
        val perp = uc(req().copy(type = TipoLicencia.PERPETUA), true) as Outcome.Ok
        assertThat(perp.value.first.expiresAtIso).isNull()
        assertThat(perp.value.first.status).isEqualTo(EstadoLicencia.PERPETUA)
    }

    @Test
    fun generarRechazaSolicitudV1DeSpviSinCifrar() = runTest {
        val seg = MemSeg()
        val uc = GenerarLicenciaUseCase(seg, FixedClock)
        val solicitud = req().copy(
            appName = "SPVI",
            deviceId = "SPVI:abc-12345",
            secundarias = 2,
        )

        val result = uc(solicitud, true, idFactory = { "no-debe-usarse" })

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error)
            .isEqualTo(dev.gl.license.core.AppError.SpviDesactualizada)
        assertThat(seg.lastPlain).isNull()
    }

    @Test
    fun registrarEImportarExportar() = runTest {
        val lic = MemLic()
        val reg = MemReg()
        val seg = MemSeg()
        val gen = GenerarLicenciaUseCase(seg, FixedClock)
        val issued = (gen(req(), true, idFactory = { "id-1" }) as Outcome.Ok).value.first
        val registrar = RegistrarLicenciaUseCase(lic, reg, FixedClock)
        assertThat(registrar(issued)).isInstanceOf(Outcome.Ok::class.java)
        assertThat(lic.getById("id-1")).isNotNull()
        val exp = ExportarRegistroUseCase(seg, lic)
        val bytes = (exp() as Outcome.Ok).value
        val lic2 = MemLic()
        val imp = ImportarRegistroUseCase(seg, lic2, MemReg(), FixedClock)
        val n = (imp(bytes) as Outcome.Ok).value
        assertThat(n).isEqualTo(1)
        assertThat(lic2.getById("id-1")).isNotNull()
    }

    @Test
    fun generarRechazaTransaccionDuplicada() = runTest {
        val lic = MemLic()
        val seg = MemSeg()
        val uc = GenerarLicenciaUseCase(seg, FixedClock, lic)
        val r1 = uc(req(), true, txNumber = "TX999")
        assertThat(r1).isInstanceOf(Outcome.Ok::class.java)
        val l1 = (r1 as Outcome.Ok).value.first
        lic.upsert(l1)

        val r2 = uc(req(), true, txNumber = "TX999")
        assertThat(r2).isInstanceOf(Outcome.Err::class.java)
        assertThat((r2 as Outcome.Err).error)
            .isEqualTo(dev.gl.license.core.AppError.TransactionAlreadyUsed("TX999"))
    }
}