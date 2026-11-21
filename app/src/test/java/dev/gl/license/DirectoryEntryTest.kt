package dev.gl.license

import dev.gl.license.security.PublicKeyBundle
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryEntryTest {
    @Test fun fragmentoExacto() {
        val fp = "sha256:e3b0:c442:98fc:1c14:9afb:f4c8:996f:b924:27ae:41e4:649b:934c:a495:991b:7852:b855"
        assertEquals("""{"kid":"gl-sign-v1","uso":"firma","spki":"","huella":"$fp","desde":77,"hasta":null,"estado":"vigente"}""", PublicKeyBundle.directoryEntry("", true, 77))
    }
}
