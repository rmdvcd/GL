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

    private fun solSpvi(
        renueva: String? = null,
        recupera: String? = null,
        amplia: String? = null,
    ) = SolicitudSpviV2(
        v = 2,
        nombre = "Maria",
        apellidos = "Perez Gonzalez",
        ci = "85010112345",
        via = ViaSolicitud.WHATSAPP,
        telefono = "+5352345678",
        deviceId = "SPVI:abc-12345",
        tipo = TipoLicencia.MENSUAL,
        solicitadaEn = "2026-01-01T00:00:00Z",
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
        devicePub = "AiuZGHe7sUgbFLfqufeF1BMCvKuYZQuJKYSxnIoc0zMK",
        secundarias = 2,
        renueva = renueva,
        recupera = recupera,
        amplia = amplia,
    )

    private fun previaLic(id: String, venceSec: Long = 1_767_225_600L + 5 * 86_400L) = Licencia(
        version = 2,
        id = id,
        firstName = "Maria",
        lastName = "Perez Gonzalez",
        nationalId = "85010112345",
        channel = ViaSolicitud.WHATSAPP,
        phone = "+5352345678",
        deviceId = "SPVI:old-device",
        appName = "SPVI",
        type = TipoLicencia.MENSUAL,
        requestedAtIso = "2025-12-01T00:00:00Z",
        issuedAtIso = java.time.Instant.ofEpochSecond(venceSec - 30 * 86_400L).toString(),
        expiresAtIso = java.time.Instant.ofEpochSecond(venceSec).toString(),
        status = dev.gl.license.domain.model.EstadoLicencia.ACTIVA,
        secundarias = 2,
        precioCobrado = 8_000,
        codigoCorto = "SPVI2:previa",
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
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
            ValidarSolicitudUseCase(seg, FixedClock),
            GenerarLicenciaUseCase(seg, FixedClock, lic),
            RegistrarLicenciaUseCase(lic, reg, FixedClock),
            puerta,
            EmitirSpvi2UseCase(puerta, lic, FixedClock),
            dev.gl.license.domain.usecase.EvaluarSolicitudHistorialUseCase(lic, FixedClock),
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
        vm.onTxClienteChange("TX-100")
        vm.onTxSmsChange("TX-100")
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
    fun repeatedGenerateTapDoesNotReplaceThePendingLicense() = runTest {
        val vm = vm()
        vm.onRawChange("ok")
        vm.onTxClienteChange("TX-100")
        vm.onTxSmsChange("TX-100")
        vm.setPaid(true)

        vm.generateLicense()
        val firstId = vm.ui.value.pending?.id
        vm.generateLicense()

        assertThat(firstId).isNotNull()
        assertThat(vm.ui.value.pending?.id).isEqualTo(firstId)
    }

    @Test
    fun shareUsesTheThreeLineLicenseMessage() = runTest {
        val vm = vm()
        vm.onRawChange("ok")
        vm.onTxClienteChange("TX-100")
        vm.onTxSmsChange("TX-100")
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
            pending?.codigoCorto,
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
    fun textoQueMencionaSpviSinCodigoMuestraAviso023() = runTest {
        val vm = vm()
        vm.onRawChange("Solicitud SPVI sin codigo")

        assertThat(vm.ui.value.solicitud).isNull()
        assertThat(vm.ui.value.spvi).isNull()
        assertThat(vm.ui.value.error).isEqualTo(AppError.SpviNoEs023.userMessage)
    }

    @Test
    fun generarSpviEmiteMensajeConCodigo() = runTest {
        val vm = vm(puerta = PuertaFake(solicitud = solSpvi(), codigo = "SPVI2:CODIGO"))
        vm.onRawChange("SPVIR1:AAAA")
        vm.onTxClienteChange("TX-SPVI")
        vm.onTxSmsChange("TX-SPVI")
        vm.setPaid(true)
        vm.generateLicense()

        assertThat(vm.ui.value.shareBody).startsWith("Licencia Shalev")
        assertThat(vm.ui.value.shareBody).contains("SPVI2:CODIGO")
        assertThat(vm.ui.value.pending?.codigoCorto).isEqualTo("SPVI2:CODIGO")
        assertThat(vm.ui.value.renuevaAviso).isNull()
    }

    @Test
    fun generarSpviConRenuevaIgnoradaAvisa() = runTest {
        val vm = vm(puerta = PuertaFake(solicitud = solSpvi(renueva = "id-ajeno")))
        vm.onRawChange("SPVIR1:AAAA")
        vm.onTxClienteChange("TX-SPVI")
        vm.onTxSmsChange("TX-SPVI")
        vm.setPaid(true)
        vm.generateLicense()

        assertThat(vm.ui.value.pending).isNotNull()
        assertThat(vm.ui.value.renuevaAviso).isNotNull()
    }

    @Test
    fun spviRecuperacionPermiteGenerarSinTransaccionNiPago() = runTest {
        val licencias = MemLic()
        licencias.upsert(previaLic("previa-uuid"))

        val vm = vm(
            licencias = licencias,
            puerta = PuertaFake(solicitud = solSpvi(recupera = "previa-uuid"), codigo = "SPVI2:RECUPERADA"),
        )
        vm.onRawChange("SPVIR1:AAAA")

        assertThat(vm.ui.value.spvi?.recupera).isEqualTo("previa-uuid")
        assertThat(vm.ui.value.paid).isTrue()
        assertThat(vm.ui.value.spviPrecioDesglose).contains("Recuperación")

        // Genera directamente sin ingresar transacción ni marcar pago
        vm.generateLicense()

        assertThat(vm.ui.value.shareBody).contains("SPVI2:RECUPERADA")
        assertThat(vm.ui.value.pending?.codigoCorto).isEqualTo("SPVI2:RECUPERADA")
        assertThat(vm.ui.value.pending?.precioCobrado).isEqualTo(0)

        // La anterior quedó revocada
        val previa = licencias.getById("previa-uuid")
        assertThat(previa?.status).isEqualTo(dev.gl.license.domain.model.EstadoLicencia.REVOCADA)
    }

    @Test
    fun spviRecuperacionRechazaAnteriorInvalida() = runTest {
        val licencias = MemLic()
        val vm = vm(
            licencias = licencias,
            puerta = PuertaFake(solicitud = solSpvi(recupera = "previa-inexistente")),
        )
        vm.onRawChange("SPVIR1:AAAA")

        assertThat(vm.ui.value.spvi).isNull()
        assertThat(vm.ui.value.error).isEqualTo(AppError.RecuperacionInvalida.userMessage)
    }
}
