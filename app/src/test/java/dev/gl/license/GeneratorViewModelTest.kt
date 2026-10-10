package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.AppError
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.LicenseMessage
import dev.gl.license.domain.model.SolicitudSpviV2
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.domain.repository.LicenciaRepository
import dev.gl.license.domain.repository.RegistroRepository
import dev.gl.license.domain.repository.SeguridadRepository
import dev.gl.license.domain.repository.SpviPuerta
import dev.gl.license.domain.usecase.EmitirSpvi2UseCase
import dev.gl.license.domain.usecase.GenerarLicenciaUseCase
import dev.gl.license.domain.usecase.RegistrarLicenciaUseCase
import dev.gl.license.domain.usecase.ValidarSolicitudUseCase
import dev.gl.license.presentation.generator.GeneratorViewModel
import dev.gl.license.presentation.generator.ShareEvent
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
class GeneratorViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class SegSpviV1 : SeguridadRepository by MemSeg() {
        override fun descifrarSolicitud(raw: String) =
            if (raw == "spvi-vieja") {
                Outcome.Ok(
                    req().copy(
                        appName = "SPVI",
                        deviceId = "SPVI:abc-12345",
                    ),
                )
            } else {
                Outcome.Err(AppError.InvalidPayload)
            }
    }

    private fun solSpvi(renueva: String? = null) = SolicitudSpviV2(
        v = 2,
        nombre = "Maria",
        apellidos = "Perez Gonzalez",
        ci = "85010112345",
        via = ViaSolicitud.WHATSAPP,
        telefono = "+5352345678",
        deviceId = "SPVI:abc-12345",
        tipo = TipoLicencia.MENSUAL,
        solicitadaEn = "2026-10-03T20:00:00Z",
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
        devicePub = "AiuZGHe7sUgbFLfqufeF1BMCvKuYZQuJKYSxnIoc0zMK",
        secundarias = 2,
        renueva = renueva,
    )

    private class PuertaFake(
        private val solicitud: SolicitudSpviV2? = null,
        private val codigo: String = "SPVI2:FAKE",
    ) : SpviPuerta {
        override fun abrirSolicitud(raw: String) =
            if (raw.contains("SPVIR1:") && solicitud != null) {
                Outcome.Ok(solicitud)
            } else {
                Outcome.Err(AppError.SpviDanada)
            }

        override fun emitirCodigo(lic: Licencia, devicePub33: ByteArray): Outcome<String> =
            Outcome.Ok(codigo)
    }

    private fun vm(
        validar: SeguridadRepository = MemSeg(),
        licencias: LicenciaRepository = MemLic(),
        registro: RegistroRepository = MemReg(),
        puerta: SpviPuerta = PuertaFake(),
    ): GeneratorViewModel {
        val seg = validar
        val lic = licencias
        val reg = registro
        return GeneratorViewModel(
            ValidarSolicitudUseCase(seg),
            GenerarLicenciaUseCase(seg, FixedClock),
            RegistrarLicenciaUseCase(lic, reg, FixedClock),
            puerta,
            EmitirSpvi2UseCase(puerta, lic, FixedClock),
        )
    }

    @Test
    fun clipboardIgnoredWithoutFocus() = runTest {
        val vm = vm()
        vm.onForegroundClipboard("""{"alg":"x"}""", windowHasFocus = false, resumed = true)
        assertThat(vm.ui.value.raw).isEmpty()
        assertThat(vm.ui.value.clipboardCaptured).isFalse()
    }

    @Test
    fun clipboardFlagOnlyWhenForeground() = runTest {
        val vm = vm()
        vm.onForegroundClipboard("""{"alg":"ok"}""", windowHasFocus = true, resumed = true)
        assertThat(vm.ui.value.clipboardCaptured).isTrue()
        vm.onRawChange("ok")
        assertThat(vm.ui.value.clipboardCaptured).isFalse()
    }

    @Test
    fun invalidDoesNotEnablePaidOrGenerate() = runTest {
        val vm = vm()
        vm.onRawChange("nope")
        assertThat(vm.ui.value.solicitud).isNull()
        vm.setPaid(true)
        assertThat(vm.ui.value.paid).isFalse()
        vm.generateLicense()
        assertThat(vm.ui.value.encryptedLicense).isNull()
    }

    @Test
    fun validThenPayGenerateRegister() = runTest {
        val vm = vm()
        vm.onRawChange("ok")
        assertThat(vm.ui.value.solicitud).isNotNull()
        vm.setPaid(true)
        assertThat(vm.ui.value.paid).isTrue()
        vm.generateLicense()
        assertThat(vm.ui.value.encryptedLicense).isEqualTo("ENVELOPE")
        assertThat(vm.ui.value.canRegister).isFalse()
        vm.onReturnedFromShare()
        assertThat(vm.ui.value.canRegister).isTrue()
        vm.registerNow()
        assertThat(vm.ui.value.success).isEqualTo("Registrada.")
        assertThat(vm.ui.value.canRegister).isFalse()
    }

    @Test
    fun shareUsesTheThreeLineLicenseMessage() = runTest {
        val vm = vm()
        vm.onRawChange("ok")
        vm.setPaid(true)
        vm.generateLicense()

        val pending = vm.ui.value.pending
        val expected = LicenseMessage.build(
            "MiApp",
            TipoLicencia.MENSUAL,
            pending?.secundarias,
            6_000,
            pending?.id.orEmpty(),
            "ENVELOPE",
        )

        assertThat(vm.ui.value.shareBody).isEqualTo(expected)
    }

    @Test
    fun spviMuestraDatosDescifrados() = runTest {
        val vm = vm(puerta = PuertaFake(solicitud = solSpvi()))
        vm.onRawChange("Solicitud de licencia SPVI\nNombre: X\n\nSPVIR1:AAAA")

        assertThat(vm.ui.value.spvi?.deviceId).isEqualTo("SPVI:abc-12345")
        assertThat(vm.ui.value.spvi?.secundarias).isEqualTo(2)
        assertThat(vm.ui.value.solicitud).isNull()
        assertThat(vm.ui.value.error).isNull()
    }

    @Test
    fun solicitudV1DeSpviAvisaActualizar() = runTest {
        val vm = vm(validar = SegSpviV1())
        vm.onRawChange("spvi-vieja")

        assertThat(vm.ui.value.solicitud).isNull()
        assertThat(vm.ui.value.error).isEqualTo(AppError.SpviDesactualizada.userMessage)
    }

    @Test
    fun generarSpviEmiteMensajeConCodigo() = runTest {
        val vm = vm(puerta = PuertaFake(solicitud = solSpvi(), codigo = "SPVI2:CODIGO"))
        vm.onRawChange("SPVIR1:AAAA")
        vm.setPaid(true)
        vm.generateLicense()

        assertThat(vm.ui.value.shareBody).startsWith("Licencia SPVI")
        assertThat(vm.ui.value.shareBody).contains("SPVI2:CODIGO")
        assertThat(vm.ui.value.pending?.codigoCorto).isEqualTo("SPVI2:CODIGO")
        assertThat(vm.ui.value.renuevaAviso).isNull()
    }

    @Test
    fun generarSpviConRenuevaIgnoradaAvisa() = runTest {
        val vm = vm(puerta = PuertaFake(solicitud = solSpvi(renueva = "id-ajeno")))
        vm.onRawChange("SPVIR1:AAAA")
        vm.setPaid(true)
        vm.generateLicense()

        assertThat(vm.ui.value.pending).isNotNull()
        assertThat(vm.ui.value.renuevaAviso).isNotNull()
    }
}
