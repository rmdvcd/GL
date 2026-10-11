# GL — Generador de Licencias

Aplicación Android nativa **offline** para un desarrollador individual que emite licencias de **sus propias** apps web/móviles. No hay backend, analytics, publicidad ni telemetría.

Nombre: **GL**. Paquete: `dev.gl.license`. Versión actual: `versionCode 3` / `versionName 1.2.0`.

## Requisitos

| Ítem | Valor |
|------|--------|
| Android Studio | Ladybug o superior |
| JDK | 17 |
| minSdk | 26 (Android 8) |
| compileSdk / targetSdk | 35 |
| Kotlin | 2.0.21 |
| Gradle | 8.9 / AGP 8.7.2 |

No requiere biometría ni PIN. Abre directo al Generador. Ten en cuenta que **cualquiera con el teléfono desbloqueado accede al registro**: ver *Advertencias y limitaciones*.

## Configuración

1. Abrir la carpeta `GL` en Android Studio.
2. Sync Gradle (catálogo: `gradle/libs.versions.toml`).
3. Run `:app` (debug añade sufijo `.debug`).

No hay `google-services.json`. No hace falta cuenta de Google.

## Compilar y ejecutar

> Este repo **no incluye `gradlew` ni `gradle-wrapper.jar`**, solo `gradle/wrapper/gradle-wrapper.properties`. Usa el Gradle wrapper de Android Studio o un `gradle` 8.9 en el PATH.

```bash
# Debug
gradle :app:assembleDebug
gradle :app:installDebug

# Release (R8/ProGuard activo)
gradle :app:assembleRelease

# Tests
gradle :app:testDebugUnitTest
gradle :app:connectedDebugAndroidTest
```

JDK 17 obligatorio (el toolchain apunta a 17; con un JDK superior falla). `local.properties` con `sdk.dir` es necesario si no hay `ANDROID_HOME`.

Detalle: `docs/TESTING.md`.

## Comprobar que la app arranca

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear dev.gl.license.debug
adb logcat -c
adb shell am start -n dev.gl.license.debug/dev.gl.license.presentation.MainActivity
sleep 8
adb logcat -d -b crash              # vacío = sin crash
adb shell pidof dev.gl.license.debug # con PID = proceso vivo
```

`databases/gl_licenses.db` debe existir y en el logcat la línea de `libsqlcipher.so` debe
terminar en `: ok`. Sin eso, la app pinta pero la BD no abre.

## Arquitectura

Clean Architecture + MVVM.

`presentation` → `domain` ← `data` + `security`  
Hilt, Compose Material 3 (oscuro forzado), Navigation, Room+SQLCipher.

`core/AppRuntime.kt` carga `libsqlcipher.so` e instala BouncyCastle. Lo invoca `GlApplication.onCreate()` **y** `ProvideModule.db()`: el AAR `sqlcipher-android` no autocarga su `.so` y `GlApplication` no corre en los tests instrumentados.

## Contrato criptográfico v1

`alg` = `ECIES-P256-AES256GCM-v1`  
Curva P-256, HKDF-SHA256, AES-256-GCM (IV 12, tag 16), ECDSA SHA-256.

### Envelope JSON (solicitud y licencia)

```json
{
  "v": 1,
  "alg": "ECIES-P256-AES256GCM-v1",
  "epk": "<Base64 SPKI P-256>",
  "iv": "<Base64 12 bytes>",
  "ct": "<Base64>",
  "tag": "<Base64 16 bytes>",
  "sig": "<Base64 ECDSA DER>",
  "kid": "gl-sign-v1"
}
```

**AAD solicitud:** `v|alg|epk|kid`  
**AAD licencia:** `1|ECIES-P256-AES256GCM-v1|<licenseId>`  
**Transcript firmado:** `epkDER || iv || ct || tag`  
Solicitud: firma con la clave efímera del cliente (también se acepta la de firma de GL).  
Licencia: firma con ECDSA de GL en Keystore.

### Solicitud (plaintext)

Campos JSON: `v`, `nombre`, `apellidos`, `ci`, `via` (`WHATSAPP`\|`SMS`), `telefono` (E.164), `deviceId`, `appName` (obligatorio), `secundarias` (opcional entero 0–10 en otras apps con flujo v1; ausente/`null` = no indicado), `tipo` (`MENSUAL`\|`SEMESTRAL`\|`ANUAL`\|`PERPETUA`), `solicitadaEn` (ISO-8601 Instant), `nonce`, `devicePub` (opcional, SPKI P-256). SPVI ya no usa este formato: ver `CONTEXTO_LICENCIAS.md` (sección SPVI 0.23.1).

Si `devicePub` es null, GL cifra hacia **su propio** par ECDH. El cliente no obtiene confidencialidad exclusiva. Limitación real.

### Licencia (plaintext)

Todo lo anterior más `id` (UUID), `emitidaEn`, `venceEn` (null si perpetua), `estado` (`ACTIVA`\|`VENCIDA`\|`REVOCADA`\|`PERPETUA`), `secundarias` (otras apps con flujo v1; su clave se omite si es `null`), `codigoCorto` (solo licencias SPVI 0.23.1; se omite si es `null`), `nonce` nuevo. `precioCobrado` no viaja en el JSON firmado: vive solo en memoria y en Room. SPVI 0.23.1 no emite licencia larga: ver `CONTEXTO_LICENCIAS.md`.

Vencimiento: +30 / +180 / +365 días desde emisión (reloj del dispositivo).

Validación fail-closed → mensaje genérico.

El cliente de tus apps debe construir el envelope (ver `ClientRequestHelper` y `SECURITY.md`). GL no publica un SDK.

## Esquema de base de datos

Room **v5**, SQLCipher, tablas `licenses` y `contact_methods`:

`id PK, firstName, lastName, nationalId, channel, phone, deviceId, appName, type, requestedAtIso, issuedAtIso, expiresAtIso, status, secundarias, precioCobrado, codigoCorto, nonce, version`

`contact_methods`: `id PK` (`"$kind:$value"`, deduplica por REPLACE), `kind` (`card`|`phone`), `value`, `createdAtIso`. Sin CVC en ningún sitio.

v1→v2 añade `appName TEXT NOT NULL DEFAULT ''` en `GlMigrations.M_1_2`. v2→v3 crea `contact_methods` en `GlMigrations.M_2_3`. v3→v4 añade `secundarias INTEGER` y `precioCobrado INTEGER`, ambos opcionales, en `GlMigrations.M_3_4`. v4→v5 añade `codigoCorto TEXT` (opcional, código SPVI2) en `GlMigrations.M_4_5`.

Orden: `issuedAtIso DESC`. Migraciones: `GlMigrations.ALL` contiene `M_1_2`, `M_2_3`, `M_3_4` y `M_4_5`; no se usa migración destructiva. Passphrase 32 B envuelta con AES-GCM del Keystore (`AAD=gl-db-v1`), ciphertext en `gl_db_meta`. El IV lo genera el Keystore (`setRandomizedEncryptionRequired(true)`) y se persiste en `w_iv`.

Exportación: `docs/GLREG.md`.

## Manual de uso

Ver también `docs/MANUAL.md`.

1. **Generador** (arranque): con la app visible y con foco de ventana, se lee el portapapeles si parece un envelope o un código `SPVIR1:`. También puedes pegar el texto a mano o usar el botón **Pegar**. El panel y la casilla “Pago realizado” solo aparecen si la solicitud es válida.
2. En SPVI 0.23.1 se muestra el panel con los datos descifrados y el desglose de precio (sin corrección de secundarias). Marca el pago, pulsa **Generar licencia**. Abre WhatsApp (`wa.me`) o SMS solo con texto: el mensaje de respuesta (datos + renglón en blanco + código `SPVI2:`), que queda además en un cuadro de solo lectura con botón **Copiar mensaje**. En otras apps con flujo v1 el mensaje sigue siendo de 3 líneas: cobro, `ID:` y envelope.
3. Al volver de la app de compartir, pulsa **Registrar**. La licencia entra en Room con estado `ACTIVA`, con secundarias y precio cobrado cuando corresponden (SPVI 0.23.1 guarda además el código corto).
4. **Registro:** lista con countdown 1 Hz (solo con la pestaña visible). Cada fila muestra secundarias y precio cobrado cuando existen. Toque → detalle con ambas filas. Importar/Exportar `.glreg` con el selector de documentos (SAF).
5. **Contacto** (entre Generador y Registro): dos desplegables editables con botón Añadir — tarjetas/cuentas y teléfonos — persistidos en `contact_methods`. El botón **Copiar** deja en el portapapeles el encabezado `Tarjeta y numero a confirmar para compra de la licencia` + tarjeta + teléfono, en 3 líneas. Sin CVC en ningún sitio.
6. **Claves:** las dos claves públicas de *este* teléfono (cifrado y firma) en Base64 SPKI con huella SHA-256, para fijarlas como ancla de confianza en la app cliente. Ver `CONTEXTO_LICENCIAS.md`.

Sin permisos propios. Intents a WhatsApp/SMS. Sin almacenamiento amplio.

## Advertencias y limitaciones (no negociables)

- No es “grado militar”. Es el techo razonable en un teléfono de consumo.
- Root / malware privilegiado gana. `FLAG_SECURE` no para root.
- StrongBox no está en todos los SoC → fallback TEE.
- ECDH en Keystore con `PURPOSE_AGREE_KEY` es API 31+; por debajo el par EC se crea con otros propósitos (limitación Android).
- Sin `devicePub`, la licencia no es exclusiva del dispositivo cliente.
- WhatsApp/SMS no son canales confidenciales (metadatos visibles).
- No hay revocación remota. `REVOCADA` es estado local.
- Reloj de vencimiento = reloj del aparato.
- `.glreg` **no** se abre en otro teléfono (clave de firma Keystore).
- **No hay autenticación.** Se retiró el PIN y la biometría. Mientras la app está abierta, las claves no exigen `userAuthenticationRequired`, así que un atacante con el teléfono desbloqueado llega al registro. `FLAG_SECURE` sigue activo.
- El IV de la BD lo genera el Keystore, no el código (`setRandomizedEncryptionRequired(true)`).
- `CryptoEngine` expone `pbkdf2HmacSha256` y `constantTimeEquals` como primitivas genéricas. No hay Argon2id: ya no hay PIN que derivar.
- Tests de Room in-memory **no** usan SQLCipher.
- El happy path completo (envelope válido → generar → registrar) **no tiene test verde**: `LicenseGenerationE2ETest` falla esperando `"Solicitud válida"`, causa sin diagnosticar. El camino de error sí está verificado a mano (`La solicitud no es válida.`).
- Uso previsto: licenciar **tu** software, no el de terceros.

## Tests y docs

- `docs/TESTING.md` — cómo correr tests  
- `GUIA_IA_TESTS_USB.md` — tests en dispositivo USB (OpenCode Desktop)  
- `CONTEXTO_LICENCIAS.md` — contrato para IAs y apps cliente  
- `SECURITY.md` — cripto y amenazas  
- `docs/MANUAL.md` — usuario final  
- `docs/GLREG.md` — formato de archivo  
- `docs/ARCHITECTURE.md` / `INTEGRATION.md` / `docs/FILETREE.md`
- `AGENTS.md` — estado verificado del repo, gotchas y trampas de tooling
