package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.EstadoLicencia
import dev.gl.license.domain.model.Licencia
import dev.gl.license.domain.model.SolicitudLicencia
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.model.ViaSolicitud
import dev.gl.license.domain.repository.LicenciaRepository
import dev.gl.license.domain.repository.SecureClock
import dev.gl.license.domain.usecase.EvaluacionHistorial
import dev.gl.license.domain.usecase.EvaluarSolicitudHistorialUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class EvaluarSolicitudHistorialTest {

    private val fixedNow = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = object : SecureClock {
        override fun nowEpochMillis(): Long = fixedNow.toEpochMilli()
        override fun nowIso(): String = fixedNow.toString()
    }

    private fun req(
        tipo: TipoLicencia = TipoLicencia.MENSUAL,
        appName: String = "MiApp",
        deviceId: String = "dev-12345",
        ci: String = "85010112345",
        phone: String = "+5355512345",
    ) = SolicitudLicencia(
        version = CONTRACT_VERSION,
        deviceId = deviceId,
        appName = appName,
        firstName = "Ana",
        lastName = "Perez",
        nationalId = ci,
        channel = ViaSolicitud.WHATSAPP,
        phone = phone,
        type = tipo,
        requestedAtIso = fixedNow.toString(),
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
        devicePublicKeyPem = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEq+BkCJzxNvUEV6J0XQyY0g71EvOfZ87syGdhURoMlOdgWFlEVs2aDxsGlia4Df+ZS6LPE6dmfCpCGZhSoC6J1Q==",
    )

    private fun lic(
        id: String,
        tipo: TipoLicencia,
        status: EstadoLicencia,
        issuedIso: String,
        expiresIso: String?,
        precioCobrado: Int? = 6_000,
        appName: String = "MiApp",
        deviceId: String = "dev-12345",
        ci: String = "85010112345",
        phone: String = "+5355512345",
    ) = Licencia(
        version = CONTRACT_VERSION,
        id = id,
        deviceId = deviceId,
        appName = appName,
        firstName = "Ana",
        lastName = "Perez",
        nationalId = ci,
        channel = ViaSolicitud.WHATSAPP,
        phone = phone,
        type = tipo,
        requestedAtIso = issuedIso,
        issuedAtIso = issuedIso,
        expiresAtIso = expiresIso,
        status = status,
        precioCobrado = precioCobrado,
        codigoCorto = "ABCD-1234-EFGH-5678",
        nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
    )

    @Test
    fun nuevaSolicitudSinHistorial() = runTest {
        val repo = MemLic()
        val uc = EvaluarSolicitudHistorialUseCase(repo, clock)

        val res = uc(req())
        assertThat(res).isInstanceOf(EvaluacionHistorial.NuevaSolicitud::class.java)
    }

    @Test
    fun sumaPeriodoRestanteSiMismaLicenciaEstaActiva() = runTest {
        val repo = MemLic()
        // Licencia activa mensual emitida hace 10 días, vence en 20 días exactos
        val vence = fixedNow.plusSeconds(20 * 86400L).toString()
        repo.upsert(
            lic(
                id = "lic-1",
                tipo = TipoLicencia.MENSUAL,
                status = EstadoLicencia.ACTIVA,
                issuedIso = fixedNow.minusSeconds(10 * 86400L).toString(),
                expiresIso = vence,
            )
        )

        val uc = EvaluarSolicitudHistorialUseCase(repo, clock)
        val res = uc(req(tipo = TipoLicencia.MENSUAL))

        assertThat(res).isInstanceOf(EvaluacionHistorial.ActivaMismoTipo::class.java)
        val act = res as EvaluacionHistorial.ActivaMismoTipo
        assertThat(act.activa.id).isEqualTo("lic-1")
        assertThat(act.diasRestantes).isEqualTo(20L)
        assertThat(act.aviso).contains("20 días restantes")
    }

    @Test
    fun rechazaSiYaTienePerpetuaActiva() = runTest {
        val repo = MemLic()
        repo.upsert(
            lic(
                id = "lic-perp",
                tipo = TipoLicencia.PERPETUA,
                status = EstadoLicencia.PERPETUA,
                issuedIso = "2026-08-01T12:00:00Z",
                expiresIso = null,
                precioCobrado = 90_000,
            )
        )

        val uc = EvaluarSolicitudHistorialUseCase(repo, clock)
        val res = uc(req(tipo = TipoLicencia.ANUAL))

        assertThat(res).isInstanceOf(EvaluacionHistorial.RechazadaYaPerpetua::class.java)
    }

    @Test
    fun sumaPeriodoRestanteSiLicenciaActivaEsDiferente() = runTest {
        val repo = MemLic()
        // Licencia mensual vence en 20 días exactos (20 * 86400 segundos)
        val vence = fixedNow.plusSeconds(20 * 86400L).toString()
        repo.upsert(
            lic(
                id = "lic-activa",
                tipo = TipoLicencia.MENSUAL,
                status = EstadoLicencia.ACTIVA,
                issuedIso = fixedNow.minusSeconds(10 * 86400L).toString(),
                expiresIso = vence,
            )
        )

        val uc = EvaluarSolicitudHistorialUseCase(repo, clock)
        val res = uc(req(tipo = TipoLicencia.SEMESTRAL))

        assertThat(res).isInstanceOf(EvaluacionHistorial.ActivaDiferente::class.java)
        val act = res as EvaluacionHistorial.ActivaDiferente
        assertThat(act.diasRestantes).isEqualTo(20L)
        assertThat(act.aviso).contains("20 días restantes")
    }

    @Test
    fun noSumaPeriodoRestanteSiSeSolicitaPerpetua() = runTest {
        val repo = MemLic()
        val vence = fixedNow.plusSeconds(15 * 86400L).toString()
        repo.upsert(
            lic(
                id = "lic-activa",
                tipo = TipoLicencia.MENSUAL,
                status = EstadoLicencia.ACTIVA,
                issuedIso = fixedNow.minusSeconds(15 * 86400L).toString(),
                expiresIso = vence,
            )
        )

        val uc = EvaluarSolicitudHistorialUseCase(repo, clock)
        val res = uc(req(tipo = TipoLicencia.PERPETUA))

        assertThat(res).isInstanceOf(EvaluacionHistorial.ActivaDiferente::class.java)
        val act = res as EvaluacionHistorial.ActivaDiferente
        assertThat(act.diasRestantes).isEqualTo(0L)
    }

    @Test
    fun periodoDePruebaNoCuentaParaSumarDias() = runTest {
        val repo = MemLic()
        // Periodo de prueba: precioCobrado == 0
        val vence = fixedNow.plusSeconds(10 * 86400L).toString()
        repo.upsert(
            lic(
                id = "lic-prueba",
                tipo = TipoLicencia.MENSUAL,
                status = EstadoLicencia.ACTIVA,
                issuedIso = fixedNow.minusSeconds(5 * 86400L).toString(),
                expiresIso = vence,
                precioCobrado = 0,
            )
        )

        val uc = EvaluarSolicitudHistorialUseCase(repo, clock)
        val res = uc(req(tipo = TipoLicencia.ANUAL))

        assertThat(res).isInstanceOf(EvaluacionHistorial.ActivaDiferente::class.java)
        val act = res as EvaluacionHistorial.ActivaDiferente
        assertThat(act.diasRestantes).isEqualTo(0L)
        assertThat(act.aviso).contains("periodo de prueba no se acumula")
    }

    @Test
    fun revalidacionSiLicenciaVencidaEsDelMismoTipo() = runTest {
        val repo = MemLic()
        // Licencia vencida hace 5 días
        repo.upsert(
            lic(
                id = "lic-vencida",
                tipo = TipoLicencia.MENSUAL,
                status = EstadoLicencia.VENCIDA,
                issuedIso = fixedNow.minusSeconds(35 * 86400L).toString(),
                expiresIso = fixedNow.minusSeconds(5 * 86400L).toString(),
            )
        )

        val uc = EvaluarSolicitudHistorialUseCase(repo, clock)
        val res = uc(req(tipo = TipoLicencia.MENSUAL))

        assertThat(res).isInstanceOf(EvaluacionHistorial.Revalidacion::class.java)
        val rev = res as EvaluacionHistorial.Revalidacion
        assertThat(rev.vencida.id).isEqualTo("lic-vencida")
        assertThat(rev.aviso).contains("Revalidación")
    }

    @Test
    fun renovacionConCambioSiLicenciaVencidaEsDeDistintoTipo() = runTest {
        val repo = MemLic()
        repo.upsert(
            lic(
                id = "lic-vencida",
                tipo = TipoLicencia.MENSUAL,
                status = EstadoLicencia.VENCIDA,
                issuedIso = fixedNow.minusSeconds(35 * 86400L).toString(),
                expiresIso = fixedNow.minusSeconds(5 * 86400L).toString(),
            )
        )

        val uc = EvaluarSolicitudHistorialUseCase(repo, clock)
        val res = uc(req(tipo = TipoLicencia.ANUAL))

        assertThat(res).isInstanceOf(EvaluacionHistorial.RenovacionCambio::class.java)
    }

    @Test
    fun falloAlLeerHistorialNoSeInterpretaComoSolicitudNueva() = runTest {
        val repo = object : LicenciaRepository by MemLic() {
            override suspend fun all(): List<Licencia> = error("fallo de almacenamiento")
        }

        val res = EvaluarSolicitudHistorialUseCase(repo, clock)(req())

        assertThat(res).isInstanceOf(EvaluacionHistorial.ErrorEvaluacion::class.java)
    }

    @Test
    fun fechaInvalidaEnLicenciaActivaBloqueaLaEvaluacion() = runTest {
        val repo = MemLic()
        repo.upsert(
            lic(
                id = "lic-activa-invalida",
                tipo = TipoLicencia.MENSUAL,
                status = EstadoLicencia.ACTIVA,
                issuedIso = fixedNow.minusSeconds(10 * 86400L).toString(),
                expiresIso = "fecha-invalida",
            )
        )

        val res = EvaluarSolicitudHistorialUseCase(repo, clock)(req())

        assertThat(res).isInstanceOf(EvaluacionHistorial.ErrorEvaluacion::class.java)
    }
}
