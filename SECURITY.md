# SECURITY.md — GL

## Modelo de amenazas

**Dentro de alcance:** acceso físico al teléfono desbloqueado o en recents; copias de seguridad en la nube; malware sin root; alguien que ve el chat de WhatsApp/SMS.

**Fuera de alcance:** root persistente con extracción de Keystore, criptoanálisis de P-256 a escala estado, canal lateral de potencia.

Objetivo: que un dump de almacenamiento o un backup no entregue el registro en claro; que un envelope alterado no se acepte; que la UI no filtre capturas triviales.

## Decisiones criptográficas

| Uso | Qué hay realmente |
|-----|-------------------|
| Datos en reposo (Room) | SQLCipher; passphrase 32 B aleatorios envueltos AES-256-GCM (Keystore), AAD `gl-db-v1` |
| Envelope ida/vuelta | ECDH P-256 → HKDF-SHA256 (`gl-req-v1` / `gl-lic-v1`) → AES-256-GCM + ECDSA P-256 sobre `epk\|\|iv\|\|ct\|\|tag` |
| `.glreg` | Formato local v2: AES-256-GCM AAD `glreg-v2` + ECDSA; se importan paquetes legacy v1 con AAD `glreg-v1` |
| Firma app | Par EC Keystore alias `gl.sign.ec.p256.v1` |
| ECDH estático GL | alias `gl.ecdh.p256.v1` |
| Master AES | alias `gl.master.aes.v1` |
| Primitivas puras | `CryptoEngine` expone `pbkdf2HmacSha256` y `constantTimeEquals` genéricos, sin Android Keystore, para poder testear en JVM |

`kid` = `gl-sign-v1` es **nombre**, no secreto. En envelopes v1, AAD = `CryptoEngine.canonical(listOf(v, alg, epkBase64, kid))` para solicitud y `canonical(listOf(v, alg, licenseId))` para licencia. Cada campo UTF-8 lleva longitud de 4 bytes big-endian; no se concatena con `|`.

StrongBox (API 28+): se pide al generar; `StrongBoxUnavailableException` → TEE. Esto no es una atestación de hardware.

### IV generado por el Keystore

`KeystoreManager` fija `setRandomizedEncryptionRequired(true)`, así que el Keystore **debe** generar el IV. `CryptoEngine.aesGcmEncrypt` lo intenta así: primero `init` con `GCMParameterSpec`; si el Keystore lanza `InvalidAlgorithmParameterException` (`Caller-provided IV not permitted`), reinicializa sin parámetros y toma `cipher.iv`. El contrato hacia el llamante no cambia: el IV efectivo siempre se persiste.

No se puede detectar este caso por `SecretKey.getFormat()`: en Android devuelve `null` para claves de `AndroidKeyStore`.

## Gestión de claves

- No hay claves **privadas** de criptografía en código, assets ni `BuildConfig`. La clave maestra de desarrollo es una credencial de prueba pública: el literal solo se genera en `BuildConfig` de debug, en release queda vacío, y no debe tratarse como secreto.
- Material privado del Android Keystore no se exporta.
- `.glreg` se cifra con la AES master del **mismo** dispositivo y se firma con el par local: **no es portable**.
- Sin autenticación de usuario en las claves: la passphrase de la BD se puede descifrar mientras la app está abierta. Ver *Consecuencia* abajo.

## Consecuencia de haber retirado la autenticación

Ya no hay PIN ni biometría. La app abre directo al Generador y `FLAG_SECURE` sigue activo, pero **cualquiera con el teléfono desbloqueado entra al registro de licencias sin ninguna credencial**. Lo que protege el registro es el cifrado en reposo (SQLCipher) y la firma del paquete `.glreg` con una clave local de Android Keystore; eso no es atestación de hardware ni un gate de sesión. Para un teléfono compartido o con acceso físico frecuente, esto es insuficiente.

## Estado de las correcciones de seguridad (2026-10-14)

Las notas siguientes describen el código de este repositorio, no las aplicaciones cliente externas:

### Corregido en el código

- **Clave de desarrollo:** el valor se define solo en el `BuildConfig` debug; el campo release queda vacío, `TrustScreen` no muestra la tarjeta en release e `isMasterKey` falla cerrado por defecto. Sigue siendo una credencial pública de desarrollo, no un secreto.
- **Helper v1:** deriva y cifra con el mismo IV; exige `devicePub` y comprueba que las claves EC recibidas usan parámetros P-256. Hay un test JVM de round-trip; el E2E Android continúa ignorado y no se declara validado.
- **Entrada v1/SPVI:** límites de longitud antes del parseo, límites de tamaño en SPVI y archivo `.glreg`, comprobación de longitudes exactas y máximo de 10 000 entradas de backup.
- **Frescura:** comparación exacta con ventana de 72 horas de antigüedad y 2 horas de adelanto, aplicada a v1 y antes de emitir SPVI.
- **Transacciones:** consultas suspendidas y fail-closed si falla el almacenamiento; ambas rutas pasan y guardan `txNumber`. La consulta es insensible a mayúsculas.
- **Backup:** `.glreg` preserva `precioCobrado` en metadatos separados del JSON de licencia y sigue importando el formato antiguo.
- **Integridad de UI:** validaciones asíncronas descartan resultados obsoletos, se evita volver a emitir desde el mismo estado pendiente y la copia individual de clave rellena el campo correcto.
- **Código muerto:** retirados permisos biométricos, alias/helper AES biométrico, errores de PIN/auth y dependencias directas no usadas de Biometric, Security Crypto y DataStore.

### Limitaciones que siguen abiertas

- **Replay:** el nonce de solicitud no se consume ni persiste. La ventana temporal no impide volver a presentar el mismo envelope y el reloj del emisor (`System.currentTimeMillis()`) puede retroceder. No hay watermark resistente a rollback en este APK.
- **Reserva de pago:** `txNumber` se comprueba contra licencias ya registradas, pero no hay índice único ni reserva atómica; la licencia se registra después de compartirla. Una carrera o un cierre antes de registrar todavía puede permitir reuso. No se añadió una migración que destruya/reescriba posibles duplicados históricos.
- **`devicePub`:** es obligatorio y se valida como EC P-256; no hay proof-of-possession, attestation ni vínculo comprobado con `deviceId`. El cliente debe proteger su clave privada.
- **Android 26–30:** `KeystoreManager` usa `PURPOSE_SIGN | PURPOSE_VERIFY` para la clave ECDH en esas versiones; no se ha verificado un fallback de `KeyAgreement`. No se eleva `minSdk` sin decisión explícita.
- **Cliente no incluido:** watermark de reloj, sub-tokens y verificación en runtime descritos en la guía son responsabilidad de las apps cliente y no están implementados por este APK.
- **Validación pendiente:** en este entorno no se ejecutaron build, tests, lint ni pruebas en dispositivo. Hace falta un runner Android compatible para validar el E2E/Keystore/Room.

## Validación

Fail-closed. Fallo de versión, alg, IV, tag, firma, JSON o campos (teléfono, CI, fechas, tipo, nonce) → `AppError` genérico. Sin logs de plaintext. R8 elimina `Log` en release. `SecureLog.redact` en debug.

## UI

`FLAG_SECURE`. `allowBackup=false` + data extraction rules que excluyen todo. Portapapeles solo `ON_RESUME` + foco de ventana + pestaña Generador. Sin accesibilidad, device admin, overlay, boot receiver.

## Buenas prácticas para el emisor

- No reenvíes envelopes por correo sin cifrar.
- Incrementa `v` si cambias AAD, curva o KDF.
- Asume que WhatsApp es un sobre opaco, no un túnel privado.
- Un teléfono nuevo = registro vacío; no hay migración entre Keystores.

## Lo que no está

Backend, HSM remoto, PQC, Play Integrity, certificate pinning, revocación online, auditoría remota, gate de sesión (retirado), `userAuthenticationRequired` en las claves.
