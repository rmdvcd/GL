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
| `.glreg` | AES-256-GCM AAD `glreg-v1` + ECDSA; magia `GLRG` |
| Firma app | Par EC Keystore alias `gl.sign.ec.p256.v1` |
| ECDH estático GL | alias `gl.ecdh.p256.v1` |
| Master AES | alias `gl.master.aes.v1` |
| Primitivas puras | `CryptoEngine` expone `pbkdf2HmacSha256` y `constantTimeEquals` genéricos, sin Android Keystore, para poder testear en JVM |

`kid` = `gl-sign-v1` es **nombre**, no secreto.

StrongBox (API 28+): se pide al generar; `StrongBoxUnavailableException` → TEE.

### IV generado por el Keystore

`KeystoreManager` fija `setRandomizedEncryptionRequired(true)`, así que el Keystore **debe** generar el IV. `CryptoEngine.aesGcmEncrypt` lo intenta así: primero `init` con `GCMParameterSpec`; si el Keystore lanza `InvalidAlgorithmParameterException` (`Caller-provided IV not permitted`), reinicializa sin parámetros y toma `cipher.iv`. El contrato hacia el llamante no cambia: el IV efectivo siempre se persiste.

No se puede detectar este caso por `SecretKey.getFormat()`: en Android devuelve `null` para claves de `AndroidKeyStore`.

## Gestión de claves

- No hay claves en código, assets ni BuildConfig.
- Material de Keystore no se exporta.
- `.glreg` se cifra con la AES master del **mismo** dispositivo y se firma con el par local: **no es portable**.
- Sin autenticación de usuario en las claves: la passphrase de la BD se puede descifrar mientras la app está abierta. Ver *Consecuencia* abajo.

## Consecuencia de haber retirado la autenticación

Ya no hay PIN ni biometría. La app abre directo al Generador y `FLAG_SECURE` sigue activo, pero **cualquiera con el teléfono desbloqueado entra al registro de licencias sin ninguna credencial**. Lo que protege el registro es exclusivamente el cifrado en reposo (SQLCipher) y la atestación del Keystore al `.glreg`, no un gate de sesión. Para un teléfono compartido o con acceso físico frecuente, esto es insuficiente.

## Auditoría de Seguridad: 8 Vulnerabilidades y Remediaciones (Opción A)

En la auditoría del sistema de licencias se identificaron 8 vectores de vulnerabilidad. Se ha aplicado e integrado formalmente la **Opción A** en todos los componentes del emisor y en las especificaciones para clientes:

1. **Vulnerabilidad 1 — Exposición de Master Key en Producción:**
   - *Mitigación:* La validación de la Master Key `GLMK-PERM-MAXS-9999` está restringida exclusivamente a builds `DEBUG` (`BuildConfig.DEBUG`). En builds de release o producción, `isMasterKey` siempre devuelve `false`.
2. **Vulnerabilidad 2 — Reutilización de Transacciones Bancarias:**
   - *Mitigación:* Almacenamiento e indexación del campo `txNumber` en la base de datos SQLCipher. `GenerarLicenciaUseCase` y `GeneratorViewModel` verifican la unicidad del número de transacción y rechazan transacciones duplicadas con `AppError.TransactionAlreadyUsed`.
3. **Vulnerabilidad 3 — Ataque de Retraso de Reloj (Clock Rollback):**
   - *Mitigación:* Las aplicaciones cliente mantienen una marca de agua monotónica persistente (`lastKnownTimestamp`). Si la hora del sistema retrocede respecto a la marca de agua, la app invalida la licencia.
4. **Vulnerabilidad 4 — Clonación de `deviceId` / `devicePub`:**
   - *Mitigación:* El par de claves del cliente se genera y custodia en `AndroidKeyStore` con respaldo en hardware (StrongBox o TEE), impidiendo la exportación de claves privadas y atando la licencia al chip físico.
5. **Vulnerabilidad 5 — Omisión de `devicePub` en la Solicitud:**
   - *Mitigación:* `devicePub` es ahora un campo estrictamente obligatorio en `FieldValidator.validateRequest`. Cualquier solicitud sin clave pública o con clave malformada se rechaza con `AppError.DevicePubRequired`.
6. **Vulnerabilidad 6 — Replay Indefinido de Solicitudes:**
   - *Mitigación:* Se valida la fecha `solicitadaEn` contra el reloj del emisor. Si la solicitud tiene más de 72 horas de antigüedad (o más de 2 horas en el futuro), se rechaza con `AppError.RequestExpired`.
7. **Vulnerabilidad 7 — Cuota de Apps Secundarias sin Control Criptográfico:**
   - *Mitigación:* Emisión y verificación de sub-tokens offline firmados con la clave primaria, validando el identificador del dispositivo secundario y el índice de cuota asignado.
8. **Vulnerabilidad 8 — Inyección de Delimitadores en AAD:**
   - *Mitigación:* Sustitución de concatenación con barras `|` por canonicalización con Length-Prefixing (longitud prefijada de 4 bytes en big-endian) en `CryptoEngine.canonical()`.

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
