package dev.gl.license

import dev.gl.license.domain.usecase.GenerarListaRevocadasUseCase
import dev.gl.license.security.CryptoEngine
import dev.gl.license.security.Spvi23
import kotlinx.serialization.json.*
import java.security.Signature
import org.junit.Assert.*
import org.junit.Test

class RevocadasTest {
    @Test fun verificaTextoExactoYHuella() {
        val pair = CryptoEngine.generateEphemeralP256() // Solo de prueba, no se persiste.
        for (ids in listOf(emptyList(), listOf("ABC"))) {
            val bytes = GenerarListaRevocadasUseCase.encode(ids, 123) { Spvi23.derToRaw(CryptoEngine.sign(pair.private, it)) }
            val env = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val datos = env.getValue("datos").jsonPrimitive.content
            val hashes = Json.parseToJsonElement(datos).jsonObject.getValue("revocadas").jsonArray
            assertEquals(ids.size, hashes.size)
            if (ids.isNotEmpty()) assertEquals("600521e2a002ab9505bf0fe44107bc23", hashes[0].jsonPrimitive.content)
            val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
            verifier.initVerify(pair.public); verifier.update(("SPVI-REV1|" + datos).toByteArray())
            assertTrue(verifier.verify(Spvi23.b64urlD(env.getValue("firma").jsonPrimitive.content)))
        }
    }
    @Test(expected = IllegalArgumentException::class) fun demasiadoGrande() {
        GenerarListaRevocadasUseCase.encode((1..16000).map { it.toString() }, 123) { ByteArray(64) }
    }
}
