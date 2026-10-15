# Guía de Implementación del Sistema de Licencias GL para Clientes

Este documento describe en detalle el contrato criptográfico, el formato de datos, las reglas de negocio y la guía de implementación paso a paso para que **cualquier IA o desarrollador** implemente o actualice el sistema de licencias en las aplicaciones cliente que reciben licencias generadas por **GL** (Generador de Licencias).

---

## 1. Resumen Ejecutivo y Arquitectura del Sistema

**GL** es un generador de licencias nativo offline para Android. No requiere servidores, backend en la nube ni conexión a Internet. La comunicación entre las apps cliente y GL se realiza fuera de banda (WhatsApp, SMS, portapapeles o archivo).

El flujo se divide en:
1. **Solicitud (Cliente → GL):** La app cliente recopila los datos del usuario, dispositivo y app, genera un par de claves efímero ECDH P-256 y cifra la solicitud hacia la clave pública ECDH de GL.
2. **Validación y Emisión (GL):**
   - GL descifra y valida la solicitud.
   - Comprueba en las licencias ya guardadas si coinciden usuario (`CI` / `teléfono`), `deviceId` y app; no registra solicitudes pendientes.
   - Si tiene una licencia activa, permite renovar el mismo tipo o cambiarlo y acumula el tiempo restante cuando corresponde (excepto al pasar a Perpetua o si la activa era de prueba).
   - Si tiene una licencia vencida y solicita el mismo tipo: se procesa como **Revalidación**.
   - El operador introduce manualmente el número indicado por el cliente y el que lee del SMS bancario. GL solo compara los valores tras quitar espacios en los extremos (distingue mayúsculas/minúsculas) para habilitar el botón; no consulta al banco ni verifica por sí mismo que el pago exista.
   - GL genera un **código alfanumérico de 16 caracteres en grupos de 4 (`XXXX-XXXX-XXXX-XXXX`)** y emite la licencia cifrada hacia la clave pública del dispositivo cliente, firmada con la clave privada ECDSA de GL.
3. **Activación (GL → Cliente):** El cliente recibe el mensaje con el código de 16 caracteres y el sobre cifrado, verifica la firma criptográfica de GL, valida que el `deviceId` y `appName` coincidan y persiste la licencia de forma segura.
4. **Clave Maestra de Desarrollo:** el ejemplo de cliente puede habilitar `GLMK-PERM-MAXS-9999` solo en builds de desarrollo. GL muestra la clave únicamente en debug; en release el campo de BuildConfig está vacío y el validador falla cerrado. La clave es pública y no debe usarse como autorización de producción.

---

## 2. Formato del Código de Licencia y Clave Maestra

### 2.1 Código de Licencia (16 caracteres)
- **Longitud:** 16 caracteres alfanuméricos agrupados en 4 grupos de 4, separados por guiones.
- **Formato:** `XXXX-XXXX-XXXX-XXXX`
- **Alfabeto permitido:** `[0-9A-Z]` (case-insensitive al procesar, ignorando guiones y espacios en blanco).
- **Ejemplo:** `4K7N-9X2M-B8PQ-3T5V`

### 2.2 Clave Maestra de Desarrollo (Master Key)
- **Código:** `GLMK-PERM-MAXS-9999`
- **Propósito:** Permite al desarrollador probar y activar de inmediato cualquier app cliente receptora de licencias de GL.
- **Debug solamente:** GL la compila en `BuildConfig` solo para `debug`; en `release` el valor es vacío, la tarjeta está oculta y `MasterKey.isMasterKey` falla cerrado por defecto. Para una app cliente, inyecta el código solo en BuildConfig de debug y pasa explícitamente `BuildConfig.DEBUG`; no pongas el literal en código común de producción. Esto es una barrera de desarrollo, no secreto criptográfico.
- Configuración recomendada de variantes: `debug { buildConfigField("String", "MASTER_KEY_CODE", "\"GLMK-PERM-MAXS-9999\"") }`; `release { buildConfigField("String", "MASTER_KEY_CODE", "\"\"") }`. Mantén el literal fuera de `src/main`.
- **Efecto en el cliente (en Debug):**
  - Estado: `ACTIVA` / `PERPETUA` (permanente, sin fecha de caducidad).
  - Apps secundarias habilitadas: `10` (el máximo permitido).
  - Modo: Offline, sin requerir envelope ni verificación de firma externa.

---

## 3. Claves Públicas fijas de GL (Ancla de Confianza)

Cada app cliente debe fijar las claves públicas de la instalación emisora correspondiente. El siguiente JSON es un ejemplo asociado a una instalación concreta y puede estar obsoleto: exporta las claves desde la pestaña **Claves** del GL emisor y compara las huellas en el momento de configurarlo.

```json
{
  "contractVersion": 1,
  "encryptionKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+bzI3jdfnqVmHOj3+O0dksDlQTJtdGLA1O0KP+dctJW3urECwUDhLEnS1DDu7cVeaVZZkdZCU8PuxGCcPw/hJA==",
  "encryptionFingerprint": "sha256:2a3f:9fce:b105:7ee8:563c:04cd:e4d1:00e7:058b:c7b8:0410:024b:e658:efe1:5905:666d",
  "signingKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1LFKFD7Af1xhbza5CrD7ArwOwq5h4qG70edEp54zozXoUa/r7P7xRQwwV82rXcy0mPznM3TNMk8wGxYCwR7ubg==",
  "signingFingerprint": "sha256:58d4:3aac:6f1b:099a:e86c:4375:b7c7:ffb5:8631:2399:246c:1f26:f861:e302:9c83:8c4e"
}
```

- **`encryptionKey`:** Clave ECDH P-256 en formato X.509 SPKI Base64. Se usa para derivar el secreto compartido que cifra la solicitud.
- **`signingKey`:** Clave ECDSA P-256 en formato X.509 SPKI Base64. Se usa para verificar la firma de GL en la licencia emitida.

---

## 4. Estructura Cifrada de la Solicitud (Cliente → GL)

### 4.1 Datos requeridos en la Solicitud
De acuerdo a las especificaciones, la solicitud de licencia debe incluir de forma cifrada:
1. **ID del dispositivo solicitante:** `deviceId` (identificador declarado por el cliente, 8-128 caracteres `[A-Za-z0-9:_-]`; GL no demuestra que sea único ni que corresponda a hardware).
2. **Nombre de la app:** `appName` (ej. `"MiApp"`, `"SPVI"`, 2-80 caracteres).
3. **Dispositivo desde el cual se solicita:** `dispositivo` (nombre o modelo del hardware, ej. `"Samsung Galaxy S22"`, `"Pixel 8 Pro"`).
4. **Datos del usuario:**
   - `nombre` (Unicode 2–80 letras)
   - `apellidos` (Unicode 2–80 letras)
   - `ci` (Carnet de Identidad, 5–20 caracteres alfanuméricos)
   - `telefono` (Número en formato E.164, 8–15 dígitos)
   - `via` (`WHATSAPP` | `SMS`)
5. **Tipo de licencia solicitada y secundarias:**
   - `tipo` (`MENSUAL` | `SEMESTRAL` | `ANUAL` | `PERPETUA`)
   - `secundarias` (Entero 0–10)

Campos criptográficos complementarios:
- `v`: Versión del contrato (`1`).
- `solicitadaEn`: Timestamp ISO-8601 UTC.
- `nonce`: Cadena aleatoria Base64/Base64url-ish (16–128 caracteres).
- `devicePub`: Clave pública EC P-256 del cliente (SPKI Base64), obligatoria. GL cifra la licencia hacia la clave suministrada, pero no demuestra que el cliente posea la privada, la guarde en hardware ni la vincule a `deviceId`.

### 4.2 Esquema JSON del Payload Plano de Solicitud

```json
{
  "v": 1,
  "deviceId": "dev-and-8f3a921c4b",
  "appName": "GestorPro",
  "dispositivo": "Xiaomi Redmi Note 11",
  "nombre": "Carlos",
  "apellidos": "Fernández Ruiz",
  "ci": "89051212345",
  "via": "WHATSAPP",
  "telefono": "+5352345678",
  "usuario": {
    "nombre": "Carlos",
    "apellidos": "Fernández Ruiz",
    "ci": "89051212345",
    "telefono": "+5352345678",
    "via": "WHATSAPP"
  },
  "tipo": "MENSUAL",
  "secundarias": 2,
  "solicitadaEn": "2026-10-15T14:30:00Z",
  "nonce": "k4T3n9X2mB8pQ3t5vA==",
  "devicePub": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE..."
}
```

*Nota:* GL acepta tanto los campos de usuario en la raíz como dentro del objeto anidado `"usuario"`.

---

## 5. Estructura Cifrada de la Licencia Generada (GL → Cliente)

### 5.1 Datos contenidos en la Licencia Cifrada
La licencia emitida contiene de forma cifrada:
1. **ID del dispositivo solicitante:** `deviceId`.
2. **Nombre de la app:** `appName`.
3. **Dispositivo solicitante:** `dispositivo`.
4. **Datos del usuario:** `nombre`, `apellidos`, `ci`, `telefono`, `via` (y objeto `usuario`).
5. **Licencia obtenida, apps secundarias y periodo restante:**
   - `licenciaObtenida`: Código de 16 caracteres alfanuméricos agrupados en grupos de 4 (`XXXX-XXXX-XXXX-XXXX`).
   - `secundarias`: Cantidad de apps secundarias autorizadas (0 a 10).
   - `periodoRestante`: días remanentes transferidos desde una licencia previa, cuando corresponde; no es la duración total de la nueva licencia ni prueba de pago.
6. **Campos de control:**
   - `id`: UUID de la licencia.
   - `tipo`: `MENSUAL` | `SEMESTRAL` | `ANUAL` | `PERPETUA`.
   - `emitidaEn`: Timestamp ISO-8601 UTC.
   - `venceEn`: Timestamp ISO-8601 UTC (o `null` para PERPETUA).
   - `estado`: `ACTIVA`, `PERPETUA`, `VENCIDA` o `REVOCADA`, según el ciclo de vida.
   - `codigoCorto`: Idéntico a `licenciaObtenida`.
   - `nonce`: Nonce fresco generado por GL.
   - `txNumber` (en la licencia JSON v1): número de transacción que el operador compara manualmente; el payload va cifrado al cliente, pero el campo no demuestra que el banco haya confirmado el pago. SPVI2 no incluye este dato en su código corto.

### 5.2 Esquema JSON del Payload Plano de Licencia

```json
{
  "v": 1,
  "id": "b7c29e10-91a4-4a25-9e6b-7c8d9e0f1a2b",
  "deviceId": "dev-and-8f3a921c4b",
  "appName": "GestorPro",
  "dispositivo": "Xiaomi Redmi Note 11",
  "nombre": "Carlos",
  "apellidos": "Fernández Ruiz",
  "ci": "89051212345",
  "via": "WHATSAPP",
  "telefono": "+5352345678",
  "usuario": {
    "nombre": "Carlos",
    "apellidos": "Fernández Ruiz",
    "ci": "89051212345",
    "telefono": "+5352345678",
    "via": "WHATSAPP"
  },
  "tipo": "MENSUAL",
  "licenciaObtenida": "4K7N-9X2M-B8PQ-3T5V",
  "secundarias": 2,
  "periodoRestante": 45,
  "solicitadaEn": "2026-10-15T14:30:00Z",
  "emitidaEn": "2026-10-15T14:35:00Z",
  "venceEn": "2026-12-29T14:35:00Z",
  "estado": "ACTIVA",
  "codigoCorto": "4K7N-9X2M-B8PQ-3T5V",
  "nonce": "N7q9L2mP5t8V1w4Z==",
  "txNumber": "TX-20261015-001"
}
```

---

## 6. Sobre Criptográfico (Envelope JSON v1)

Tanto la solicitud como la licencia viajan encapsuladas en un Envelope JSON:

```json
{
  "v": 1,
  "alg": "ECIES-P256-AES256GCM-v1",
  "epk": "<Base64 de clave pública efímera SPKI>",
  "iv": "<Base64 de 12 bytes IV>",
  "ct": "<Base64 del texto cifrado AES-GCM>",
  "tag": "<Base64 de 16 bytes de autenticación GCM>",
  "sig": "<Base64 de firma ECDSA DER>",
  "kid": "gl-sign-v1"
}
```

### 6.1 Primitivas y Parámetros
- **Curva elíptica:** SECP256r1 (`secp256r1` / NIST P-256).
- **Acuerdo de claves:** ECDH P-256.
- **KDF:** HKDF-SHA256 (`salt = IV de 12 bytes`, `info = "gl-req-v1"` para solicitudes, `"gl-lic-v1"` para licencias, salida 32 bytes AES-256).
- **Cifrado autenticado:** AES-256-GCM (`IV` = 12 bytes, `Tag` = 16 bytes / 128 bits).
- **Firma digital:** ECDSA SHA-256 en formato ASN.1 DER.
- **Transcript firmado (bytes):** `epkDER || iv || ct || tag`
- **AAD GCM para solicitud:** `CryptoEngine.canonical(listOf("1", "ECIES-P256-AES256GCM-v1", epkBase64, "gl-sign-v1"))`.
- **AAD GCM para licencia:** `CryptoEngine.canonical(listOf("1", "ECIES-P256-AES256GCM-v1", licenseId))`.
- `canonical` concatena campos UTF-8 prefijando cada uno con una longitud de 4 bytes big-endian. **No** se usa la representación textual con `|` que aparece en documentos antiguos; esos bytes son parte del contrato v1 y no deben cambiarse sin versionar.

La frescura del emisor acepta como máximo 72 horas de antigüedad y 2 horas de adelanto, con comparación exacta. **No** consume ni persiste el nonce; la misma solicitud puede reproducirse si se vuelve a confirmar el pago. El reloj del emisor es de pared y no es resistente a rollback.

---

## 7. Reglas de Negocio del Ciclo de Vida de Licencias

Al implementar el cliente, tenga en cuenta las reglas que aplica GL:

1. **Petición por primera vez:**
   - El cliente genera la solicitud y la envía a GL.
   - GL no encuentra registros previos de ese usuario con ese `deviceId` y emite la licencia correspondiente.
2. **Petición con Licencia Activa (Renovación Anticipada o Cambio de Plan):**
   - Si el usuario tiene una licencia activa y solicita el **mismo tipo de licencia** (renovación anticipada): GL calcula el tiempo restante de la licencia activa y **se lo suma a la nueva licencia** (ej. si le quedaban 12 días de una Mensual y compra otra Mensual, la nueva durará 30 + 12 = 42 días).
   - Si solicita un **tipo diferente** (ej. de Mensual a Anual): GL igualmente calcula los días restantes de la licencia activa y **se los suma a la duración de la nueva licencia**.
   - *Excepciones:*
     - Si la nueva licencia es `PERPETUA`, no se añaden días adicionales (la licencia no expira).
     - Si la licencia previa activa era un periodo de prueba (`precioCobrado == 0`), el periodo restante **no se acumula**.
3. **Petición con Licencia Vencida (Revalidación):**
   - Si la licencia previa caducó y el usuario solicita **el mismo tipo**, GL la procesa como **Revalidación**, emitiendo un nuevo código y periodo completo a partir de la fecha actual.
   - Si solicita un tipo distinto, se procesa como renovación con cambio de plan.
4. **Comparación manual de Números de Transacción:**
   - El operador introduce el número que comunica el cliente y el que lee del SMS bancario; GL habilita el botón solo si coinciden después de quitar espacios externos (comparación sensible a mayúsculas/minúsculas).
   - GL no lee SMS, no se conecta a Transfermóvil/EnZona ni a un banco y no verifica que el pago sea real. Esa comprobación queda a cargo del operador.

---

## 8. Código de Implementación para Aplicaciones Cliente (Kotlin / Android)

A continuación se presenta la implementación de referencia en Kotlin puro para cualquier app cliente de Android (sin dependencias externas pesadas, usando `java.security` y `javax.crypto` nativos de Android):

### 8.1 Modelo de Datos del Cliente

```kotlin
package com.example.cliente.licensing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Envelope(
    val v: Int,
    val alg: String,
    val epk: String,
    val iv: String,
    val ct: String,
    val tag: String,
    val sig: String,
    val kid: String,
)

@Serializable
data class DatosUsuario(
    val nombre: String,
    val apellidos: String,
    val ci: String,
    val telefono: String,
    val via: String = "WHATSAPP",
)

@Serializable
data class RequestPayload(
    val v: Int = 1,
    val deviceId: String,
    val appName: String,
    val dispositivo: String,
    val nombre: String,
    val apellidos: String,
    val ci: String,
    val via: String,
    val telefono: String,
    val usuario: DatosUsuario,
    val tipo: String,
    val secundarias: Int,
    val solicitadaEn: String,
    val nonce: String,
    val devicePub: String,
)

@Serializable
data class LicensePayload(
    val v: Int,
    val id: String,
    val deviceId: String,
    val appName: String,
    val dispositivo: String = "",
    val nombre: String,
    val apellidos: String,
    val ci: String,
    val via: String,
    val telefono: String,
    val usuario: DatosUsuario? = null,
    val tipo: String,
    val licenciaObtenida: String? = null,
    val secundarias: Int? = null,
    val periodoRestante: Long? = null,
    val solicitadaEn: String,
    val emitidaEn: String,
    val venceEn: String? = null,
    val estado: String,
    val codigoCorto: String? = null,
    val nonce: String,
    val txNumber: String? = null,
)
```

### 8.2 Motor Criptográfico del Cliente

```kotlin
package com.example.cliente.licensing

import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ClientCryptoEngine {
    private const val GCM_TAG_LENGTH = 128
    private const val INFO_REQ = "gl-req-v1"
    private const val INFO_LIC = "gl-lic-v1"

    fun b64E(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun b64D(s: String): ByteArray = Base64.getDecoder().decode(s.trim())

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return kpg.generateKeyPair()
    }

    fun parsePublicKey(spkiBase64: String): PublicKey {
        val bytes = b64D(spkiBase64)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(X509EncodedKeySpec(bytes))
    }

    fun ecdh(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        return ka.generateSecret()
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: String, len: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info.toByteArray(Charsets.UTF_8))
        mac.update(0x01.toByte())
        val okm = mac.doFinal()
        return okm.copyOfRange(0, len)
    }

    fun aesGcmEncrypt(
        keyBytes: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        cipher.updateAAD(aad)
        val full = cipher.doFinal(plaintext)
        val ct = full.copyOfRange(0, full.size - 16)
        val tag = full.copyOfRange(full.size - 16, full.size)
        return ct to tag
    }

    fun aesGcmDecrypt(
        keyBytes: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext + tag)
    }

    fun verifyEcdsa(publicKey: PublicKey, data: ByteArray, signatureDer: ByteArray): Boolean {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(publicKey)
        sig.update(data)
        return sig.verify(signatureDer)
    }

    fun signEcdsa(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    /**
     * Canonicalización de AAD con longitud prefijada (Length-Prefixing) para cada campo.
     * Inmune a colisiones o inyección de delimitadores (Mitigación Vuln 8, Opción A).
     */
    fun canonical(parts: List<String>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            val len = bytes.size
            bos.write(byteArrayOf(
                ((len ushr 24) and 0xff).toByte(),
                ((len ushr 16) and 0xff).toByte(),
                ((len ushr 8) and 0xff).toByte(),
                (len and 0xff).toByte(),
            ))
            bos.write(bytes)
        }
        return bos.toByteArray()
    }
}
```

### 8.3 Gestor de Licencias del Cliente (Generación de Solicitud y Activación)

```kotlin
package com.example.cliente.licensing

import android.os.Build
import kotlinx.serialization.json.Json
import java.time.Instant

class ClientLicenseManager(
    private val appName: String,
    private val deviceId: String,
    private val glEncryptionKeyB64: String,
    private val glSigningKeyB64: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // Configura el literal solo en buildConfigField de debug; en release usa "".
    private val masterKeyCode = com.example.cliente.BuildConfig.MASTER_KEY_CODE

    /** Nunca uses un valor por defecto permisivo para el modo debug. */
    fun isMasterKey(input: String, isDebug: Boolean): Boolean {
        if (!isDebug || masterKeyCode.isBlank()) return false
        val clean = input.replace("-", "").replace(" ", "").trim().uppercase()
        return clean == masterKeyCode.replace("-", "")
    }

    /**
     * Construye y cifra la solicitud hacia la clave pública de GL.
     */
    fun crearSolicitudCifrada(
        nombre: String,
        apellidos: String,
        ci: String,
        telefono: String,
        via: String,
        tipo: String,
        secundarias: Int,
        deviceKeyPair: java.security.KeyPair,
    ): String {
        val glEncPub = ClientCryptoEngine.parsePublicKey(glEncryptionKeyB64)
        val ephPair = ClientCryptoEngine.generateKeyPair()

        val dispositivo = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val nowIso = Instant.now().toString()
        val nonceBytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val nonce = ClientCryptoEngine.b64E(nonceBytes)

        val payload = RequestPayload(
            deviceId = deviceId,
            appName = appName,
            dispositivo = dispositivo,
            nombre = nombre,
            apellidos = apellidos,
            ci = ci,
            via = via,
            telefono = telefono,
            usuario = DatosUsuario(nombre, apellidos, ci, telefono, via),
            tipo = tipo,
            secundarias = secundarias,
            solicitadaEn = nowIso,
            nonce = nonce,
            devicePub = ClientCryptoEngine.b64E(deviceKeyPair.public.encoded),
        )

        val plainJson = json.encodeToString(RequestPayload.serializer(), payload).toByteArray(Charsets.UTF_8)

        val sharedSecret = ClientCryptoEngine.ecdh(ephPair.private, glEncPub)
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val aesKey = ClientCryptoEngine.hkdfSha256(sharedSecret, iv, "gl-req-v1")

        val epkDER = ephPair.public.encoded
        val epkB64 = ClientCryptoEngine.b64E(epkDER)
        val aad = ClientCryptoEngine.canonical(listOf("1", "ECIES-P256-AES256GCM-v1", epkB64, "gl-sign-v1"))

        val (ct, tag) = ClientCryptoEngine.aesGcmEncrypt(aesKey, iv, plainJson, aad)
        val transcript = epkDER + iv + ct + tag
        val sig = ClientCryptoEngine.signEcdsa(ephPair.private, transcript)

        val envelope = Envelope(
            v = 1,
            alg = "ECIES-P256-AES256GCM-v1",
            epk = epkB64,
            iv = ClientCryptoEngine.b64E(iv),
            ct = ClientCryptoEngine.b64E(ct),
            tag = ClientCryptoEngine.b64E(tag),
            sig = ClientCryptoEngine.b64E(sig),
            kid = "gl-sign-v1",
        )

        return json.encodeToString(Envelope.serializer(), envelope)
    }

    /**
     * Procesa y activa una licencia recibida desde GL.
     * Soporta:
     * 1. Clave maestra (GLMK-PERM-MAXS-9999): activación inmediata permanente con 10 secundarias.
     * 2. Envelope cifrado devuelto por GL.
     */
    fun activarLicencia(
        rawInput: String,
        devicePrivateKey: java.security.PrivateKey,
        expectedLicenseId: String? = null,
    ): Result<LicensePayload> {
        val trimmed = rawInput.trim()

        // 1. Verificación de Clave Maestra
        if (isMasterKey(trimmed, com.example.cliente.BuildConfig.DEBUG)) {
            val masterLic = LicensePayload(
                v = 1,
                id = "master-license-local",
                deviceId = deviceId,
                appName = appName,
                dispositivo = "${Build.MANUFACTURER} ${Build.MODEL}",
                nombre = "Desarrollador",
                apellidos = "Master",
                ci = "00000000000",
                via = "MASTER",
                telefono = "+0000000000",
                tipo = "PERPETUA",
                licenciaObtenida = masterKeyCode,
                secundarias = 10,
                periodoRestante = null,
                solicitadaEn = Instant.now().toString(),
                emitidaEn = Instant.now().toString(),
                venceEn = null,
                estado = "PERPETUA",
                codigoCorto = masterKeyCode,
                nonce = "master-key-nonce",
            )
            return Result.success(masterLic)
        }

        // El envelope no lleva el ID; para descifrarlo se necesita la línea `ID:` externa.
        val licenseId = expectedLicenseId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: return Result.failure(IllegalArgumentException("ID de licencia ausente o inválido."))

        // 2. Extracción de Envelope JSON si viene acompañado de texto o código
        val jsonStart = trimmed.indexOf("{\"v\":")
        val jsonEnd = trimmed.lastIndexOf("}")
        if (jsonStart < 0 || jsonEnd < 0 || jsonEnd <= jsonStart) {
            return Result.failure(IllegalArgumentException("Formato no reconocido ni envelope válido."))
        }
        val envJson = trimmed.substring(jsonStart, jsonEnd + 1)

        val env = try {
            json.decodeFromString(Envelope.serializer(), envJson)
        } catch (e: Exception) {
            return Result.failure(e)
        }

        // 3. Descifrado del Envelope
        val glSignPub = ClientCryptoEngine.parsePublicKey(glSigningKeyB64)
        val epkBytes = ClientCryptoEngine.b64D(env.epk)
        val iv = ClientCryptoEngine.b64D(env.iv)
        val ct = ClientCryptoEngine.b64D(env.ct)
        val tag = ClientCryptoEngine.b64D(env.tag)
        val sig = ClientCryptoEngine.b64D(env.sig)

        // Verificar firma digital de GL
        val transcript = epkBytes + iv + ct + tag
        if (!ClientCryptoEngine.verifyEcdsa(glSignPub, transcript, sig)) {
            return Result.failure(SecurityException("Firma digital de GL inválida. Licencia alterada o apócrifa."))
        }

        // Descifrado ECIES
        val epkPub = ClientCryptoEngine.parsePublicKey(env.epk)
        val sharedSecret = ClientCryptoEngine.ecdh(devicePrivateKey, epkPub)
        val aesKey = ClientCryptoEngine.hkdfSha256(sharedSecret, iv, "gl-lic-v1")

        // El ID llega fuera del envelope (línea `ID:` del mensaje). Forma parte del AAD.
        val aad = ClientCryptoEngine.canonical(
            listOf("1", "ECIES-P256-AES256GCM-v1", licenseId),
        )
        val decryptedBytes = try {
            ClientCryptoEngine.aesGcmDecrypt(aesKey, iv, ct, tag, aad = aad)
        } catch (_: Exception) {
            return Result.failure(SecurityException("No se pudo descifrar la licencia (fallo de autenticación GCM)."))
        }

        val license = json.decodeFromString(LicensePayload.serializer(), decryptedBytes.decodeToString())

        // 4. Validaciones de Integridad y Correspondencia
        if (license.id != licenseId) {
            return Result.failure(IllegalStateException("El ID externo no coincide con la licencia descifrada."))
        }
        if (!license.appName.equals(appName, ignoreCase = true)) {
            return Result.failure(IllegalStateException("La licencia fue emitida para '${license.appName}', no para '$appName'."))
        }
        if (license.deviceId != deviceId) {
            return Result.failure(IllegalStateException("La licencia corresponde a otro dispositivo (${license.deviceId})."))
        }
        if (license.venceEn != null && Instant.parse(license.venceEn).isBefore(Instant.now())) {
            return Result.failure(IllegalStateException("La licencia recibida ya se encuentra vencida."))
        }

        return Result.success(license)
    }
}
```

### 8.4 Componentes de Seguridad Avanzada (Hardware Keystore, Watermark y Sub-tokens)

```kotlin
package com.example.cliente.licensing

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.time.Instant

/**
 * 1. Generador de Par de Claves atado a Hardware (TEE / StrongBox) - Vuln 4, Opción A
 */
object HardwareKeyStoreHelper {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "client_device_key_p256"

    fun getOrCreateHardwareKeyPair(context: Context): KeyPair {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            val cert = ks.getCertificate(KEY_ALIAS)
            val priv = ks.getKey(KEY_ALIAS, null) as java.security.PrivateKey
            return KeyPair(cert.publicKey, priv)
        }

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val specBuilder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_AGREE_KEY,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))

        try {
            // Intentar StrongBox en dispositivos compatibles (API 28+)
            specBuilder.setIsStrongBoxBacked(true)
            kpg.initialize(specBuilder.build())
            return kpg.generateKeyPair()
        } catch (_: Exception) {
            // Fallback a TEE estándar
            specBuilder.setIsStrongBoxBacked(false)
            kpg.initialize(specBuilder.build())
            return kpg.generateKeyPair()
        }
    }
}

/**
 * 2. Defensa local de mejor esfuerzo ante Clock Rollback mediante una marca persistida
 */
// Defensa local de mejor esfuerzo, no un reloj confiable: se basa en la hora del dispositivo
// y SharedPreferences puede restaurarse, borrarse o alterarse. GL no aporta hora de servidor.
class ClockWatermarkManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("lic_watermark_prefs", Context.MODE_PRIVATE)
    private val KEY_WATERMARK = "last_known_timestamp_epoch"

    @Synchronized
    fun validateAndAdvanceTime(venceEnIso: String?): Boolean {
        val nowEpoch = System.currentTimeMillis()
        val lastKnown = prefs.getLong(KEY_WATERMARK, 0L)

        // Si la hora actual es anterior a la última conocida, el reloj fue alterado hacia atrás
        if (nowEpoch < lastKnown) {
            return false // Rechazar licencia por manipulación de reloj
        }

        // Avanzar marca de agua
        prefs.edit().putLong(KEY_WATERMARK, nowEpoch).apply()

        if (venceEnIso == null) return true // Licencia PERPETUA
        val expiryEpoch = try {
            Instant.parse(venceEnIso).toEpochMilli()
        } catch (_: Exception) {
            return false
        }
        return nowEpoch <= expiryEpoch
    }
}

/**
 * 3. Emisión y Verificación de Sub-tokens de Apps Secundarias - Vuln 7, Opción A
 */
object SecondaryQuotaTokenManager {
    fun createSubToken(
        primaryPrivateKey: java.security.PrivateKey,
        masterLicenseId: String,
        secondaryDeviceId: String,
        slotIndex: Int,
        totalAllowed: Int,
        venceEnIso: String?,
    ): String {
        require(slotIndex in 1..totalAllowed) { "Slot fuera de rango" }
        val payload = "$masterLicenseId|$secondaryDeviceId|$slotIndex|$totalAllowed|${venceEnIso ?: "PERPETUA"}"
        val sig = ClientCryptoEngine.signEcdsa(primaryPrivateKey, payload.toByteArray(Charsets.UTF_8))
        return "$payload.${ClientCryptoEngine.b64E(sig)}"
    }

    fun verifySubToken(
        primaryPublicKey: java.security.PublicKey,
        subToken: String,
        expectedSecondaryDeviceId: String,
    ): Boolean {
        val parts = subToken.split(".")
        if (parts.size != 2) return false
        val payloadStr = parts[0]
        val sigBytes = ClientCryptoEngine.b64D(parts[1])
        if (!ClientCryptoEngine.verifyEcdsa(primaryPublicKey, payloadStr.toByteArray(Charsets.UTF_8), sigBytes)) {
            return false
        }
        val fields = payloadStr.split("|")
        if (fields.size != 5) return false
        val targetDevice = fields[1]
        val venceEn = fields[4]
        if (targetDevice != expectedSecondaryDeviceId) return false
        if (venceEn != "PERPETUA" && Instant.parse(venceEn).isBefore(Instant.now())) return false
        return true
    }
}
```

---

## 9. Checklist para quien implemente una app cliente

Estos puntos son tareas/recomendaciones para el **cliente externo**; no indican que el código de este emisor ya las implemente:

- [ ] Fijar las claves públicas del GL concreto y verificar sus huellas por un canal confiable.
- [ ] Proteger la privada del cliente (Keystore cuando esté disponible). GL cifra hacia la pública recibida, pero no verifica attestation ni vincula la clave a `deviceId`.
- [ ] Incluir el modelo de dispositivo (`Build.MANUFACTURER + " " + Build.MODEL`) si se desea mostrarlo al operador.
- [ ] Pedir el número de transacción y enviarlo para la comprobación manual del emisor; no asumir una pasarela de pago automática.
- [ ] Mantener cualquier clave maestra de prueba exclusivamente en la variante debug, con `BuildConfig.DEBUG` explícito y el literal ausente de release.
- [ ] Si el cliente necesita resistir rollback, diseñar y probar su propia fuente/marca monotónica; GL no aporta una marca de agua confiable.
- [ ] Si hay apps secundarias, diseñar y verificar sus cuotas en el cliente; los sub-tokens descritos en esta guía no son un módulo de GL.
- [ ] Implementar AAD v1 exactamente con `ClientCryptoEngine.canonical` (prefijo de longitud big-endian), no con delimitadores `|`.

---

## 10. Estado de los controles en el emisor GL

| # | Riesgo | Estado verificado en este repositorio |
|---|---|---|
| **1** | Clave maestra de desarrollo en release | El literal se genera solo en `BuildConfig` debug; release queda vacío y la tarjeta no se muestra. `MasterKey.isMasterKey` falla cerrado por defecto. Credencial pública de prueba, no secreto. |
| **2** | Reutilización de transacciones | Se consulta `txNumber` contra licencias guardadas y los errores de lectura bloquean emisión; SPVI también conserva el valor. No hay índice único ni reserva atómica; existe una carrera hasta pulsar Registrar. |
| **3** | Clock rollback / vigencia | v1 y la emisión SPVI aplican una ventana exacta de 72 h de antigüedad y 2 h futura. El reloj es de pared; no hay watermark ni resistencia a rollback en GL. |
| **4** | Clonación de `deviceId` / `devicePub` | GL exige una clave EC P-256 parseable y cifra a esa clave. No hay proof-of-possession, attestation ni vínculo con `deviceId`. |
| **5** | Omisión o malformación de `devicePub` | Obligatoria en v1; se rechaza si falta o no parsea como EC P-256. |
| **6** | Replay de solicitudes | Ventana temporal limitada; el nonce no se consume ni persiste, así que replay dentro de la ventana sigue siendo posible. |
| **7** | Cuota de apps secundarias | No hay módulo de sub-tokens/verificación de apps secundarias en este repositorio; es responsabilidad del cliente. |
| **8** | Ambigüedad de AAD | El contrato v1 usa `canonical()` con longitud de 4 bytes big-endian. No cambiar esos bytes sin subir versión/definir compatibilidad. |

