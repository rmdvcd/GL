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

- No hay claves **privadas** de criptografía en código, assets ni `BuildConfig`.
- Material privado del Android Keystore no se exporta.
- `.glreg` se cifra con la AES master del **mismo** dispositivo y se firma con el par local: **no es portable**.
- Sin autenticación de usuario en las claves: la passphrase de la BD se puede descifrar mientras la app está abierta. Ver *Consecuencia* abajo.

## Consecuencia de haber retirado la autenticación

Ya no hay PIN ni biometría. La app abre directo al Generador y `FLAG_SECURE` sigue activo, pero **cualquiera con el teléfono desbloqueado entra al registro de licencias sin ninguna credencial**. Lo que protege el registro es el cifrado en reposo (SQLCipher) y la firma del paquete `.glreg` con una clave local de Android Keystore; eso no es atestación de hardware ni un gate de sesión. Para un teléfono compartido o con acceso físico frecuente, esto es insuficiente.

## Estado de las correcciones de seguridad (2026-10-14)

Las notas siguientes describen el código de este repositorio, no las aplicaciones cliente externas:

### Corregido en el código

- **Clave de desarrollo:** eliminada. Ya no existe `MASTER_KEY_CODE` ni la tarjeta en `TrustScreen`; el emisor no tiene ningún bypass de licencia.
- **Helper v1:** deriva y cifra con el mismo IV; exige `devicePub` y comprueba que las claves EC recibidas usan parámetros P-256. Hay un test JVM de round-trip; el E2E Android continúa ignorado y no se declara validado.
- **Entrada v1/SPVI:** límites de longitud antes del parseo, límites de tamaño en SPVI y archivo `.glreg`, comprobación de longitudes exactas y máximo de 10 000 entradas de backup.
- **Frescura:** ventana inclusiva de 7 días (168 h) de antigüedad y 2 horas de adelanto, aplicada a v1 y antes de emitir SPVI desde una única constante (`RequestFreshness`).
- **Por qué ampliar a 7 días no reabre el reenvío:** el nonce de la solicitud se inserta en `consumed_nonces` dentro de la misma transacción que crea la licencia y no tiene caducidad propia. Una solicitud ya emitida se rechaza con `SolicitudYaProcesada` aunque se presente otra vez a los 7 días menos un segundo; ampliar la ventana solo afecta a solicitudes **aún no utilizadas**. Además, la licencia queda ligada a la `devicePub` de esa solicitud (la clave del dispositivo solicitante), así que una copia reenviada no sirve en otro dispositivo.
- **Retención:** no existe ninguna limpieza por antigüedad de `consumed_nonces` ni de `tx_reservations`, y no debe añadirse por debajo de 14 días. La única liberación es manual (`releaseReservation`, por `licenseId`, desde el detalle del Registro).
- **Transacciones:** consultas suspendidas y fail-closed si falla el almacenamiento; ambas rutas pasan y guardan `txNumber`. La consulta es insensible a mayúsculas.
- **Backup:** `.glreg` preserva `precioCobrado` en metadatos separados del JSON de licencia y sigue importando el formato antiguo.
- **Integridad de UI:** validaciones asíncronas descartan resultados obsoletos, se evita volver a emitir desde el mismo estado pendiente y la copia individual de clave rellena el campo correcto.
- **Código muerto:** retirados permisos biométricos, alias/helper AES biométrico, errores de PIN/auth y dependencias directas no usadas de Biometric, Security Crypto y DataStore.

### Limitaciones que siguen abiertas

- **Replay y pagos:** Room v7 consume nonce, reserva pago y guarda la licencia en la misma transacción. Se bloquea al fallar la base; al liberar un pago manualmente no se libera el nonce. Esto protege una instalación intacta, no un borrado, un rollback de toda la BD ni dos emisores independientes. Un `.glreg` no es copia de las tablas de nonces ni de inventario de lotes.
- **`devicePub`:** es obligatorio y se valida como EC P-256; no hay proof-of-possession, attestation ni vínculo comprobado con `deviceId`. El cliente debe proteger su clave privada.
- **Android 26–30:** `KeystoreManager` usa `PURPOSE_SIGN | PURPOSE_VERIFY` para la clave ECDH en esas versiones; no se ha verificado un fallback de `KeyAgreement`. No se eleva `minSdk` sin decisión explícita.
- **Cliente no incluido:** watermark de reloj y verificación en runtime descritos en la guía son responsabilidad de las apps cliente y no están implementados por este APK. Los sub-tokens sí están implementados en el cliente JS de referencia; DShalev aún no los emite.
- **Validación pendiente:** en este entorno no se ejecutaron build, tests, lint ni pruebas en dispositivo. Hace falta un runner Android compatible para validar el E2E/Keystore/Room.

## Validación

Fail-closed. Fallo de versión, alg, IV, tag, firma, JSON o campos (teléfono, CI, fechas, tipo, nonce) → `AppError` genérico. Sin logs de plaintext. R8 elimina `Log` en release. `SecureLog.redact` en debug.

## Servicio de canje comercial (externo)

El servicio de activación por código descrito en `docs/REDEEM_SERVICE.md` es una
pieza **aparte** del APK. No añade `INTERNET` a GL.

- **Sub-token:** permiso firmado por la clave del dispositivo primario, ligado a
  `licenseId`, thumbprint secundario, `slot`, cupo y vencimiento. La secundaria
  lo verifica offline; no hay estado online ni revocación remota de la
  secundaria. El `slot` no se registra en el servidor: la app primaria debe
  impedir reutilizarlo. Si se compromete la clave primaria, puede emitir
  permisos para cualquier dispositivo hasta el vencimiento.
- **Renovación:** el rollover conserva días, pero dos canjes concurrentes del
  mismo dispositivo pueden crear dos licencias con el mismo vencimiento; no se
  pierde el derecho, aunque el cupo de secundarias queda duplicado.

- **Claves separadas.** GL firma lotes con `gl-sign-v1` (Keystore). El servicio
  firma licencias con `gl-redeem-v1` (secreto del servicio). El cliente fija solo
  la segunda. Una firma de lote no verifica como licencia.
- **Vínculo al dispositivo.** La licencia incluye `SHA-256(SPKI)` de una clave
  P-256 de la instalación; el canje exige una firma de esa clave sobre app,
  código normalizado, `requestId`, instante y nonce. Sin esa prueba, un
  interceptor de la respuesta no puede quedarse la licencia.
- **Atomicidad.** Un `requestId` y un canje activo por código están protegidos
  por índices únicos en D1 (`request_id` UNIQUE, índice parcial
  `UNIQUE(code_hash) WHERE active = 1`). El reintento devuelve la misma
  licencia; otro dispositivo recibe un error genérico.
- **Privacidad.** No se guardan códigos en claro (solo HMAC con pepper), ni
  tokens, nonces, IPs o datos personales. Los contadores de rate limit usan HMAC.
  El logger tiene lista blanca de campos.
- **Política offline.** La licencia vale hasta su vencimiento; la revocación solo
  se aplica al consultar `/v1/status` o al reintentar. No es revocación
  instantánea sin conexión.
- **Reloj.** El cliente usa `max(reloj, marca de agua)` y detecta retrocesos
  mayores de 2 h, pero el almacén local puede alterarse: es disuasión, no
  prueba.
- **Código portador.** Quien tenga el código puede canjearlo primero. El canal
  de venta y la entrega del código son parte de la superficie de ataque.
- **Sin attestation.** El servicio no verifica Play Integrity ni hardware; la
  clave del dispositivo es tan buena como el Keystore que la guarde.

### Gestión de claves del servicio

- `SERVICE_PRIVATE_KEY`, `CODE_PEPPER` y `GL_ISSUER_PUBLIC_KEY` viven en el
  gestor de secretos del proveedor (Cloudflare Secrets), nunca en Git.
- Los ficheros de `gen-keys`/`mint-batch` se escriben con permisos `0600`.
- No hay rotación implementada: `kid` es único. Definir un plan antes de
  producción.
- Comprometer `gl-redeem-v1` obliga a publicar una nueva clave en el cliente y
  a revocar licencias; comprometer `gl-sign-v1` obliga a dejar de importar lotes
  y revisar los ya importados.

## UI

`FLAG_SECURE`. `allowBackup=false` + data extraction rules que excluyen todo. Portapapeles solo `ON_RESUME` + foco de ventana + pestaña Generador. Sin accesibilidad, device admin, overlay, boot receiver.

## Buenas prácticas para el emisor

- No reenvíes envelopes por correo sin cifrar.
- Incrementa `v` si cambias AAD, curva o KDF.
- Asume que WhatsApp es un sobre opaco, no un túnel privado.
- Un teléfono nuevo = registro vacío; no hay migración entre Keystores.

## Lo que no está

Backend, HSM remoto, PQC, Play Integrity, certificate pinning, revocación online, auditoría remota, gate de sesión (retirado), `userAuthenticationRequired` en las claves.

## Raíces offline y reloj

A y B son anclas públicas de build. Una raíz firma el directorio; la clave de firma del teléfono emite licencias, listas y lotes, no nuevos directorios. ECDH solo cifra solicitudes. Una retirada limita la ventana de emisión; marcar la clave comprometida invalida sus firmas incluso retroactivamente. Quien obtenga raíz y passphrase puede firmar un directorio malicioso. Véanse [contrato](docs/DIRECTORIO_V1.md) y [operación](docs/OPERACION_DIRECTORIO.md).

SPVI aumenta la fecha por dispositivo/app y bloquea si compensar supera 24 h. En el canje online, un desfase superior a la ventana da HTTP 400 `CLOCK_SKEW` con `error.serverTime`; `/v1/time` usa TLS, no-store y limitación por IP. No es una prueba de tiempo offline. Ante fallo del binding se deniega la petición.
