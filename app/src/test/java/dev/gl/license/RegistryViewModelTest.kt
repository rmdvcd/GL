package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.domain.usecase.ExportarRegistroUseCase
import dev.gl.license.domain.usecase.ImportarRegistroUseCase
import dev.gl.license.presentation.registry.RegistryCountdown
import dev.gl.license.presentation.registry.RegistryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegistryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun sample(id: String, type: TipoLicencia = TipoLicencia.MENSUAL) = Licencia(
        version = CONTRACT_VERSION,
        id = id,
        firstName = "Ana",
        lastName = "Perez",
        nationalId = "85010112345",
        channel = ViaSolicitud.SMS,
        phone = "+5355512345",
        appName = "MiApp",
        deviceId = "device-abc-12345",
        type = type,
        requestedAtIso = "2026-01-01T00:00:00Z",
        issuedAtIso = "2026-01-01T00:00:00Z",
        expiresAtIso = if (type == TipoLicencia.PERPETUA) null else "2026-01-31T00:00:00Z",
        status = if (type == TipoLicencia.PERPETUA) EstadoLicencia.PERPETUA else EstadoLicencia.ACTIVA,
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
    )

    @Test
    fun countdownPerpetuaAndExpired() {
        val p = sample("p", TipoLicencia.PERPETUA)
        assertThat(RegistryCountdown.label(p, 0L)).isEqualTo("Perpetua")
        val m = sample("m")
        val after = java.time.Instant.parse("2026-02-01T00:00:00Z").toEpochMilli()
        assertThat(RegistryCountdown.label(m, after)).isEqualTo("Vencida")
        val before = java.time.Instant.parse("2026-01-30T00:00:00Z").toEpochMilli()
        assertThat(RegistryCountdown.label(m, before)).startsWith("1d ")
    }

    @Test
    fun importExportFlow() = runTest {
        val lic = MemLic()
        lic.upsert(sample("id-1"))
        val seg = MemSeg()
        seg.lastPlain = sample("id-1")
        val vm = RegistryViewModel(
            lic,
            ImportarRegistroUseCase(seg, MemLic(), MemReg(), FixedClock),
            ExportarRegistroUseCase(seg, lic),
        )
        val bytes = vm.exportBytes()
        assertThat(bytes).isNotNull()
        assertThat(vm.ui.value.message).contains("Exportación")
        vm.importBytes(byteArrayOf(9))
        assertThat(vm.ui.value.error).isNotNull()
        vm.importBytes(seg.blob)
        assertThat(vm.ui.value.message).contains("Importación")
    }

    @Test
    fun mergeKeepsNewest() = runTest {
        val lic = MemLic()
        lic.upsert(sample("id-1"))
        val newer = sample("id-1").copy(issuedAtIso = "2026-02-01T00:00:00Z")
        val n = lic.merge(listOf(newer)) as Outcome.Ok
        assertThat(n.value).isEqualTo(1)
        assertThat(lic.getById("id-1")?.issuedAtIso).isEqualTo("2026-02-01T00:00:00Z")
    }
}
