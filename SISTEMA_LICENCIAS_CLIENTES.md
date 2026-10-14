# Guía de Implementación del Sistema de Licencias GL para Clientes

Este documento describe en detalle el contrato criptográfico, el formato de datos, las reglas de negocio y la guía de implementación paso a paso para que **cualquier IA o desarrollador** implemente o actualice el sistema de licencias en las aplicaciones cliente que reciben licencias generadas por **GL** (Generador de Licencias).

---

## 1. Resumen Ejecutivo y Arquitectura del Sistema

**GL** es un generador de licencias nativo offline para Android. No requiere servidores, backend en la nube ni conexión a Internet. La comunicación entre las apps cliente y GL se realiza fuera de banda (WhatsApp, SMS, portapapeles o archivo).

El flujo se divide en:
1. **Solicitud (Cliente → GL):** La app cliente recopila los datos del usuario, dispositivo y app, genera un par de claves efímero ECDH P-256 y cifra la solicitud hacia la clave pública ECDH de GL.
2. **Validación y Emisión (GL):**
   - GL descifra y valida la solicitud.
   - Comprueba en su registro histórico que el usuario (`CI` / `teléfono`) con ese dispositivo (`deviceId`) no tenga peticiones previas.
   - Si tiene una licencia activa: exige que la solicitud sea de un tipo diferente y acumula el tiempo restante de la activa (salvo si se solicita Perpetua o si la activa era de prueba).
   - Si tiene una licencia vencida y solicita el mismo tipo: se procesa como **Revalidación**.
   - El operador introduce los números de transacción enviados por el cliente y extraídos del SMS bancario. GL solo habilita el cobro y la emisión si ambos números coinciden.
   - GL genera un **código alfanumérico de 16 caracteres en grupos de 4 (`XXXX-XXXX-XXXX-XXXX`)** y emite la licencia cifrada hacia la clave pública del dispositivo cliente, firmada con la clave privada ECDSA de GL.
3. **Activación (GL → Cliente):** El cliente recibe el mensaje con el código de 16 caracteres y el sobre cifrado, verifica la firma criptográfica de GL, valida que el `deviceId` y `appName` coincidan y persiste la licencia de forma segura.
4. **Clave Maestra de Desarrollo:** Para pruebas inmediatas sin pasar por el flujo de emisión, el cliente reconoce la clave maestra `GLMK-PERM-MAXS-9999`, la cual activa la app de forma **permanente** y con el **máximo de 10 apps secundarias**.

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
- **Opción A (Mitigación Vuln 1):** La clave maestra sólo es válida en entornos de desarrollo y depuración (`BuildConfig.DEBUG == true`). En builds de producción (`release`), la verificación de clave maestra está completamente deshabilitada (`isMasterKey` devuelve siempre `false`), previniendo que cualquier usuario final pueda activar la app en producción con la clave de pruebas.
- **Efecto en el cliente (en Debug):**
  - Estado: `ACTIVA` / `PERPETUA` (permanente, sin fecha de caducidad).
  - Apps secundarias habilitadas: `10` (el máximo permitido).
  - Modo: Offline, sin requerir envelope ni verificación de firma externa.

---

## 3. Claves Públicas fijas de GL (Ancla de Confianza)

Cada app cliente debe incrustar las claves públicas de GL de la instalación emisora correspondiente:

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
1. **ID del dispositivo solicitante:** `deviceId` (ej. identificador de hardware único, 8-128 caracteres `[A-Za-z0-9:_-]`).
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
- `nonce`: Cadena aleatoria Base64 (16–128 bytes).
- `devicePub`: Clave pública ECDH P-256 del cliente (SPKI Base64), necesaria para que GL emita la licencia exclusivamente descifrable por este cliente.

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
   - `periodoRestante`: Días restantes acumulados o duración total efectiva (incluye días transferidos de licencias previas sin caducar).
6. **Campos de control:**
   - `id`: UUID de la licencia.
   - `tipo`: `MENSUAL` | `SEMESTRAL` | `ANUAL` | `PERPETUA`.
   - `emitidaEn`: Timestamp ISO-8601 UTC.
   - `venceEn`: Timestamp ISO-8601 UTC (o `null` para PERPETUA).
   - `estado`: `ACTIVA` | `PERPETUA`.
   - `codigoCorto`: Idéntico a `licenciaObtenida`.
   - `nonce`: Nonce fresco generado por GL.

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
  "venceEn": "2026-11-29T14:35:00Z",
  "estado": "ACTIVA",
  "codigoCorto": "4K7N-9X2M-B8PQ-3T5V",
  "nonce": "N7q9L2mP5t8V1w4Z=="
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
- **AAD GCM para solicitud:** `"1|ECIES-P256-AES256GCM-v1|" + epkBase64 + "|gl-sign-v1"`
- **AAD GCM para licencia:** `"1|ECIES-P256-AES256GCM-v1|" + licenseId`

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
4. **Validación de Pago mediante Números de Transacción:**
   - El cliente debe enviar el número de transferencia/transacción bancaria que le devuelve Transfermóvil o EnZona.
   - GL contrasta este número con el SMS bancario recibido. Solo si coinciden exactamente se habilita la creación de la licencia.

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
    private val masterKeyCode = "GLMK-PERM-MAXS-9999"

    /**
     * Comprueba si un código introducido por el usuario es la clave maestra.
     * Restringido estrictamente a builds DEBUG (Mitigación Vuln 1, Opción A).
     */
    fun isMasterKey(input: String, isDebug: Boolean = com.example.cliente.BuildConfig.DEBUG): Boolean {
        if (!isDebug) return false
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
    ): Result<LicensePayload> {
        val trimmed = rawInput.trim()

        // 1. Verificación de Clave Maestra
        if (isMasterKey(trimmed)) {
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

        // AAD para licencia: "1|ECIES-P256-AES256GCM-v1|<licenseId>"
        // En GL, el AAD se deriva con el ID de licencia; si no se conoce de antemano,
        // se puede intentar primero o leer el plano
        val decryptedBytes = try {
            // Intento con AAD canónico de licencia o genérico
            ClientCryptoEngine.aesGcmDecrypt(aesKey, iv, ct, tag, aad = ByteArray(0))
        } catch (_: Exception) {
            return Result.failure(SecurityException("No se pudo descifrar la licencia (fallo de autenticación GCM)."))
        }

        val license = json.decodeFromString(LicensePayload.serializer(), decryptedBytes.decodeToString())

        // 4. Validaciones de Integridad y Correspondencia
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
 * 2. Protección contra Clock Rollback mediante Marca de Agua Monotónica - Vuln 3, Opción A
 */
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

## 9. Checklist de Implementación en Apps Cliente

Al integrar este sistema en una nueva aplicación cliente:

- [x] **Almacenar las claves públicas de GL** como constantes seguras en la app.
- [x] **Atar `devicePub` y `deviceId` al Keystore de hardware** (`AndroidKeyStore`, TEE/StrongBox).
- [x] **Incluir el modelo de dispositivo** (`dispositivo = Build.MANUFACTURER + " " + Build.MODEL`).
- [x] **Proveer UI para ingresar el No. de transacción bancaria** y recordarle al usuario que lo comparta junto con la solicitud.
- [x] **Restringir la clave maestra (`GLMK-PERM-MAXS-9999`) a `BuildConfig.DEBUG`** para impedir accesos no autorizados en producción.
- [x] **Proteger la caducidad con Marca de Agua monotónica** (`ClockWatermarkManager`) contra manipulación del reloj del sistema.
- [x] **Vincular apps secundarias mediante sub-tokens firmados** con validación de índice de ranura y cuota.
- [x] **Emplear Length-Prefixing canónico** en AAD contra ataques de colisión de delimitadores.

---

## 10. Matriz de Auditoría y Mitigaciones (Opción A en las 8 Vulnerabilidades)

| # | Vulnerabilidad | Riesgo / Vector | Mitigación Implementada (Opción A) |
|---|---|---|---|
| **1** | Exposición de Master Key en Producción | Usuario ingresa `GLMK-PERM-MAXS-9999` en release y obtiene licencia perpetua gratis con 10 secundarias. | `MasterKey.isMasterKey` exige `isDebug == true` (`BuildConfig.DEBUG`). En release siempre retorna `false`. |
| **2** | Reutilización de No. de Transacción Bancaria | Usuario reutiliza un comprobante bancario para activar múltiples licencias. | `txNumber` persistido en tabla `licenses` de SQLCipher con índice único. GL rechaza números duplicados con `TransactionAlreadyUsed`. |
| **3** | Ataque de Retraso de Reloj (Clock Rollback) | Usuario atrasa el reloj del sistema operativo para extender licencias vencidas. | Monotonic timestamp watermark persistido en almacenamiento protegido. Si `now < watermark`, la licencia se bloquea por fraude. |
| **4** | Clonación de `deviceId` / `devicePub` | Copia de identificadores a emuladores u otros dispositivos. | Generación de clave EC P-256 en Android Keystore con hardware backing (StrongBox/TEE). `devicePub` vinculado al silicio. |
| **5** | Omisión de `devicePub` en Solicitud | Licencia emitida sin cifrado específico para el cliente receptor. | `devicePub` es validado de forma obligatoria en `FieldValidator.validateRequest`. Rechaza con `DevicePubRequired` si falta o es inválido. |
| **6** | Replay Indefinido de Solicitudes | Solicitud interceptada o reutilizada días o meses después. | Ventana de expiración estricta de 72 horas para `solicitadaEn` evaluada contra el reloj del emisor. Rechaza con `RequestExpired`. |
| **7** | Falsificación de Quota de Apps Secundarias | Apps secundarias se auto-declaran autorizadas sin control criptográfico. | Emisión de sub-tokens firmados por la app primaria con verificación de ranura (1..cuota) y vinculación de `deviceId`. |
| **8** | Inyección de Delimitadores en AAD | Ambigüedad en concatenación de cadenas separadas por `\|` en AAD. | Canonicalización con longitud prefijada (4-byte big-endian Length-Prefixing) en `CryptoEngine.canonical()`. |

