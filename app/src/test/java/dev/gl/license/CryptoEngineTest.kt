package dev.gl.license

import com.google.common.truth.Truth.assertThat
import dev.gl.license.core.Outcome
import dev.gl.license.domain.model.CONTRACT_VERSION
import dev.gl.license.domain.model.IssuedLicense
import dev.gl.license.domain.model.LicenseRequest
import dev.gl.license.domain.model.LicenseStatus
import dev.gl.license.domain.model.LicenseType
import dev.gl.license.domain.model.RequestChannel
import dev.gl.license.domain.usecase.FieldValidator
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.EnvelopeValidator
import dev.gl.license.security.HybridBox
import dev.gl.license.security.RegistryPack
import dev.gl.license.security.SecureLog
import kotlinx.serialization.json.Json
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class CryptoEngineTest {

    @Test
    fun aesGcmRoundTripAndAadBind() {
        val key = SecretKeySpec(CryptoEngine.randomBytes(32), "AES")
        val pt = "hello-gl".toByteArray()
        val (iv, ct, tag) = CryptoEngine.aesGcmEncrypt(key, pt, aad = "aad".toByteArray())
        val out = CryptoEngine.aesGcmDecrypt(key, iv, ct, tag, aad = "aad".toByteArray())
        assertThat(out.decodeToString()).isEqualTo("hello-gl")
        try {
            CryptoEngine.aesGcmDecrypt(key, iv, ct, tag, aad = "other".toByteArray())
            throw AssertionError("aad")
        } catch (_: Exception) {
        }
    }

    @Test
    fun pbkdf2HighCostRound() {
        val salt = ByteArray(16) { 2 }
        val a = CryptoEngine.pbkdf2HmacSha256("13579246".toCharArray(), salt, iterations = 10_000)
        val b = CryptoEngine.pbkdf2HmacSha256("13579246".toCharArray(), salt, iterations = 10_000)
        assertThat(a).isEqualTo(b)
        assertThat(a.size).isEqualTo(32)
    }

    @Test
    fun ecdhSharedSecret() {
        val a = CryptoEngine.generateEphemeralP256()
        val b = CryptoEngine.generateEphemeralP256()
        assertThat(CryptoEngine.ecdh(a.private, b.public)).isEqualTo(CryptoEngine.ecdh(b.private, a.public))
    }

    @Test
    fun ecdsaSignVerify() {
        val kp = CryptoEngine.generateEphemeralP256()
        val msg = "payload".toByteArray()
        val sig = CryptoEngine.sign(kp.private, msg)
        assertThat(CryptoEngine.verify(kp.public, msg, sig)).isTrue()
        assertThat(CryptoEngine.verify(kp.public, "x".toByteArray(), sig)).isFalse()
    }

    @Test
    fun hybridSealOpen() {
        val recipient = CryptoEngine.generateEphemeralP256()
        val signer = CryptoEngine.generateEphemeralP256()
        val env = HybridBox.seal(
            plaintext = "req".toByteArray(),
            peerPublic = recipient.public,
            signingPrivate = signer.private,
            info = CryptoEngine.INFO_REQ,
            aadParts = listOf("1", CryptoEngine.ALG_HYBRID, "kid"),
        )
        val opened = HybridBox.open(
            env,
            recipient.private,
            listOf(signer.public),
            CryptoEngine.INFO_REQ,
            listOf("1", CryptoEngine.ALG_HYBRID, "kid"),
        )
        assertThat(opened).isInstanceOf(Outcome.Ok::class.java)
        assertThat((opened as Outcome.Ok).value.decodeToString()).isEqualTo("req")
        val bad = HybridBox.open(
            env,
            recipient.private,
            listOf(signer.public),
            CryptoEngine.INFO_REQ,
            listOf("tamper"),
        )
        assertThat(bad).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun registryPackRoundTripAndRejectsBadMagic() {
        val aes = SecretKeySpec(CryptoEngine.randomBytes(32), "AES")
        val kp = CryptoEngine.generateEphemeralP256()
        val packed = RegistryPack.pack("licenses".toByteArray(), aes, kp.private)
        val out = RegistryPack.unpack(packed, aes, kp.public)
        assertThat((out as Outcome.Ok).value.decodeToString()).isEqualTo("licenses")
        packed[0] = 'X'.code.toByte()
        assertThat(RegistryPack.unpack(packed, aes, kp.public)).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun envelopeStructuralAndFields() {
        val env = HybridBox.seal(
            "x".toByteArray(),
            CryptoEngine.generateEphemeralP256().public,
            CryptoEngine.generateEphemeralP256().private,
            CryptoEngine.INFO_LIC,
            listOf("1"),
        )
        assertThat(EnvelopeValidator.structural(env)).isInstanceOf(Outcome.Ok::class.java)
        assertThat(EnvelopeValidator.structural(env.copy(alg = "none"))).isInstanceOf(Outcome.Err::class.java)

        val req = LicenseRequest(
            version = CONTRACT_VERSION,
            firstName = "Ana",
            lastName = "Perez",
            nationalId = "85010112345",
            channel = RequestChannel.WHATSAPP,
            phone = "+5355512345",
            appName = "MiApp",
            deviceId = "device-abc-12345",
            type = LicenseType.MENSUAL,
            requestedAtIso = "2026-01-01T00:00:00Z",
            nonce = "AAAAAAAAAAAAAAAAAAAAAA==",
        )
        assertThat(FieldValidator.validateRequest(req)).isInstanceOf(Outcome.Ok::class.java)
        assertThat(FieldValidator.validateRequest(req.copy(phone = "00"))).isInstanceOf(Outcome.Err::class.java)

        val lic = IssuedLicense(
            version = 1,
            id = "id-1",
            firstName = req.firstName,
            lastName = req.lastName,
            nationalId = req.nationalId,
            channel = req.channel,
            phone = req.phone,
            appName = "MiApp",
            deviceId = req.deviceId,
            type = LicenseType.PERPETUA,
            requestedAtIso = req.requestedAtIso,
            issuedAtIso = "2026-01-02T00:00:00Z",
            expiresAtIso = null,
            status = LicenseStatus.PERPETUA,
            nonce = req.nonce,
        )
        assertThat(EnvelopeValidator.license(lic)).isInstanceOf(Outcome.Ok::class.java)
        assertThat(EnvelopeValidator.license(lic.copy(expiresAtIso = "2026-02-01T00:00:00Z")))
            .isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun secureLogRedacts() {
        val s = SecureLog.redact("""{"ci":"85010112345","ct":"AAAA","sig":"BB","tel":+5355512345}""")
        assertThat(s).doesNotContain("85010112345")
        assertThat(s).doesNotContain("AAAA")
    }

    @Test
    fun jsonEnvelopeRoundtrip() {
        val json = Json { encodeDefaults = true }
        val env = HybridBox.seal(
            "{}".toByteArray(),
            CryptoEngine.generateEphemeralP256().public,
            CryptoEngine.generateEphemeralP256().private,
            CryptoEngine.INFO_REQ,
            listOf("1"),
        )
        val s = json.encodeToString(dev.gl.license.domain.model.Envelope.serializer(), env)
        val back = json.decodeFromString(dev.gl.license.domain.model.Envelope.serializer(), s)
        assertThat(back.alg).isEqualTo(CryptoEngine.ALG_HYBRID)
    }
}
