package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.RedeemBatch
import dev.gl.license.domain.model.RedeemBatchCanonical
import dev.gl.license.domain.model.RedeemBatchEntry
import dev.gl.license.domain.model.TipoLicencia
import dev.gl.license.domain.repository.SecureClock
import dev.gl.license.domain.usecase.CrearLoteCanjeUseCase
import dev.gl.license.security.CryptoEngine
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Test

class RedeemBatchTest {
    private val clock = object : SecureClock {
        override fun nowEpochMillis(): Long = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
        override fun nowIso(): String = "2026-11-10T12:00:00Z"
    }

    private fun batchVector(): RedeemBatch = RedeemBatch(
        batchId = "11111111-1111-4111-8111-111111111111",
        createdAt = "2026-11-10T12:00:00.000Z",
        appId = "DSHALEV",
        entries = listOf(
            RedeemBatchEntry(
                code = "4K7N-9X2M-B8PQ-3T5V",
                type = TipoLicencia.MENSUAL,
                secundarias = 1,
                redeemBefore = "2027-01-01T00:00:00.000Z",
            ),
            RedeemBatchEntry(
                code = "ABCD-EFGH-JKLM-NPQR",
                type = TipoLicencia.ANUAL,
                secundarias = 0,
            ),
        ),
        signature = "",
    )

    @Test
    fun `canonicalizacion coincide con el servicio JS`() {
        val entry0 = Base64.getEncoder().encodeToString(
            RedeemBatchCanonical.entryBytes(batchVector().entries[0]),
        )
        assertThat(entry0).isEqualTo(
            "AAAAD0dMLUJBVENIMS1FTlRSWQAAABA0SzdOOVgyTUI4UFEzVDVWAAAAB01FTlNVQUwAAAABMQAAABgyMDI3LTAxLTAxVDAwOjAwOjAwLjAwMFo=",
        )
        val batch = Base64.getEncoder().encodeToString(
            RedeemBatchCanonical.signingBytes(batchVector()),
        )
        assertThat(batch).isEqualTo(
            "AAAACUdMLUJBVENIMQAAAAExAAAAF0dMLUJBVENILVAyNTYtU0hBMjU2LXYxAAAAJDExMTExMTExLTExMTEtNDExMS04MTExLTExMTExMTExMTExMQAAAApnbC1zaWduLXYxAAAAGDIwMjYtMTEtMTBUMTI6MDA6MDAuMDAwWgAAAAdEU0hBTEVWAAAAATIAAAArQlRwbERSeUdEbnhTS2VsNGxkbkhwLVN5b2UwOGpfUnc5RnlaekRIVHB0WQAAACtJc3dEeFlMbUd0cFh4am9SeEtPU2l0SHZyVFN0RTNmVWJUUHE1N3ZLNTNB",
        )
    }

    @Test
    fun `normaliza y rechaza el alfabeto ambiguo`() {
        assertThat(RedeemBatchCanonical.normalize(" 4k7n-9x2m b8pq-3t5v ")).isEqualTo("4K7N9X2MB8PQ3T5V")
        assertThat(RedeemBatchCanonical.format("4K7N9X2MB8PQ3T5V")).isEqualTo("4K7N-9X2M-B8PQ-3T5V")
        assertThat(RedeemBatchCanonical.normalize("ABCD-EFGH-1234-IOIO")).isNull()
        assertThat(RedeemBatchCanonical.normalize("ABCD-EFGH-1234-567")).isNull()
    }

    @Test
    fun `acuña un lote firmado y verificable en JVM`() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keys = kpg.generateKeyPair()
        val useCase = CrearLoteCanjeUseCase(clock)
        val codes = listOf("4K7N-9X2M-B8PQ-3T5V", "ABCD-EFGH-JKLM-NPQR")
        var index = 0
        val result = useCase(
            appId = "DSHALEV",
            cantidad = 2,
            tipo = TipoLicencia.MENSUAL,
            secundarias = 2,
            batchId = "22222222-2222-4222-8222-222222222222",
            codigoFactory = { codes[index++] },
            signer = { bytes -> CryptoEngine.sign(keys.private, bytes) },
        )
        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        val batch = (result as Outcome.Ok).value
        assertThat(batch.entries).hasSize(2)
        assertThat(batch.signature).isNotEmpty()
        val bytes = RedeemBatchCanonical.signingBytes(batch)
        assertThat(CryptoEngine.verify(keys.public, bytes, CryptoEngine.b64d(batch.signature))).isTrue()

        val json = Json.encodeToString(RedeemBatch.serializer(), batch)
        val roundtrip = Json.decodeFromString(RedeemBatch.serializer(), json)
        assertThat(roundtrip).isEqualTo(batch)
        assertThat(roundtrip.entries[0].type).isEqualTo(TipoLicencia.MENSUAL)
    }

    @Test
    fun `rechaza app cantidad secundarias y fechas invalidas`() {
        val useCase = CrearLoteCanjeUseCase(clock)
        val signer = { _: ByteArray -> ByteArray(64) { 1 } }
        assertThat(
            useCase("DS HALEV", 1, TipoLicencia.MENSUAL, signer = signer),
        ).isInstanceOf(Outcome.Err::class.java)
        assertThat(
            useCase("DSHALEV", 0, TipoLicencia.MENSUAL, signer = signer),
        ).isInstanceOf(Outcome.Err::class.java)
        assertThat(
            useCase("DSHALEV", 1, TipoLicencia.MENSUAL, secundarias = 11, signer = signer),
        ).isInstanceOf(Outcome.Err::class.java)
        assertThat(
            useCase(
                "DSHALEV",
                1,
                TipoLicencia.MENSUAL,
                redeemBeforeIso = "2020-01-01T00:00:00Z",
                signer = signer,
            ),
        ).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun `si la fabrica no aporta codigos distintos falla sin firmar`() {
        val useCase = CrearLoteCanjeUseCase(clock)
        var firmado = false
        val result = useCase(
            appId = "DSHALEV",
            cantidad = 2,
            tipo = TipoLicencia.ANUAL,
            codigoFactory = { "4K7N-9X2M-B8PQ-3T5V" },
            signer = { bytes ->
                firmado = true
                ByteArray(64) { 1 }
            },
        )
        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat(firmado).isFalse()
    }
}
