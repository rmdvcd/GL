package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.repository.LicenciaRepository
import dev.gl.license.domain.repository.RegistroRepository
import dev.gl.license.domain.repository.SeguridadRepository
import dev.gl.license.domain.repository.SpviPuerta
import dev.gl.license.domain.usecase.EmitirSpvi2UseCase
import dev.gl.license.domain.usecase.EvaluarSolicitudHistorialUseCase
import dev.gl.license.domain.usecase.GenerarLicenciaUseCase
import dev.gl.license.domain.usecase.RegistrarLicenciaUseCase
import dev.gl.license.domain.usecase.ValidarSolicitudUseCase
import dev.gl.license.presentation.generator.GeneratorViewModel
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
class TransactionMatchingTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(
        validar: SeguridadRepository = MemSeg(),
        licencias: LicenciaRepository = MemLic(),
        registro: RegistroRepository = MemReg(),
    ): GeneratorViewModel {
        return GeneratorViewModel(
            ValidarSolicitudUseCase(validar, FixedClock),
            GenerarLicenciaUseCase(validar, FixedClock, licencias),
            RegistrarLicenciaUseCase(licencias, registro, FixedClock),
            object : SpviPuerta {
                override fun abrirSolicitud(raw: String) = dev.gl.license.core.Outcome.Err(dev.gl.license.core.AppError.SpviDanada)
                override fun emitirCodigo(lic: Licencia, devicePub33: ByteArray) = dev.gl.license.core.Outcome.Ok("SPVI2:OK")
            },
            EmitirSpvi2UseCase(
                object : SpviPuerta {
                    override fun abrirSolicitud(raw: String) = dev.gl.license.core.Outcome.Err(dev.gl.license.core.AppError.SpviDanada)
                    override fun emitirCodigo(lic: Licencia, devicePub33: ByteArray) = dev.gl.license.core.Outcome.Ok("SPVI2:OK")
                },
                licencias,
                FixedClock,
            ),
            EvaluarSolicitudHistorialUseCase(licencias, FixedClock),
        )
    }

    @Test
    fun paymentCannotBeConfirmedWithoutMatchingTransactions() = runTest {
        val vm = vm()
        vm.onRawChange("ok")

        assertThat(vm.ui.value.solicitud).isNotNull()
        assertThat(vm.ui.value.txMatch).isFalse()

        // Intento de marcar pago sin transacciones introducidas
        vm.setPaid(true)
        assertThat(vm.ui.value.paid).isFalse()

        // Transacciones diferentes
        vm.onTxClienteChange("12345")
        vm.onTxSmsChange("99999")
        assertThat(vm.ui.value.txMatch).isFalse()

        vm.setPaid(true)
        assertThat(vm.ui.value.paid).isFalse()

        // Transacciones coincidentes
        vm.onTxSmsChange("12345")
        assertThat(vm.ui.value.txMatch).isTrue()

        vm.setPaid(true)
        assertThat(vm.ui.value.paid).isTrue()

        // Si se altera una de las transacciones, el pago se desactiva
        vm.onTxSmsChange("123456")
        assertThat(vm.ui.value.txMatch).isFalse()
        assertThat(vm.ui.value.paid).isFalse()
    }

    @Test
    fun generationRequiresPaidAndMatchingTransactions() = runTest {
        val vm = vm()
        vm.onRawChange("ok")

        vm.onTxClienteChange("8888")
        vm.onTxSmsChange("9999")
        vm.generateLicense()
        assertThat(vm.ui.value.pending).isNull()

        // Con transacciones coincidentes y pago confirmado
        vm.onTxSmsChange("8888")
        vm.setPaid(true)
        vm.generateLicense()

        assertThat(vm.ui.value.pending).isNotNull()
        val generated = vm.ui.value.pending!!
        // Código de 16 caracteres agrupado en 4 grupos de 4
        assertThat(generated.codigoCorto).isNotNull()
        assertThat(generated.codigoCorto!!.split("-")).hasSize(4)
    }

    @Test
    fun generationRejectsAlreadyUsedTransaction() = runTest {
        val lic = MemLic()
        val vm1 = vm(licencias = lic)
        vm1.onRawChange("ok")
        vm1.onTxClienteChange("TX-USED")
        vm1.onTxSmsChange("TX-USED")
        vm1.setPaid(true)
        vm1.generateLicense()
        assertThat(vm1.ui.value.pending).isNotNull()
        lic.upsert(vm1.ui.value.pending!!)

        // Segunda solicitud intentando reusar el mismo número de transacción
        val vm2 = vm(licencias = lic)
        vm2.onRawChange("ok")
        vm2.onTxClienteChange("TX-USED")
        vm2.onTxSmsChange("TX-USED")
        vm2.setPaid(true)
        vm2.generateLicense()
        assertThat(vm2.ui.value.pending).isNull()
        assertThat(vm2.ui.value.error).contains("TX-USED")
    }
}
