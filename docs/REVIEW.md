# Informe de revisión final — GL

Actualizado tras compilar y ejecutar en dispositivo real (Xiaomi, API 35). Estado verificado, no supuesto.

## 1. Compilación / tests

| Ítem | Estado |
|------|--------|
| `:app:assembleDebug` | **OK** (46.9 MB) |
| `:app:testDebugUnitTest` | **OK** — 29 tests / 7 suites / 0 fallos |
| `:app:connectedDebugAndroidTest` | **OK** — `KeystoreCryptoTest`, `SmokeTest`, `MainFlowsTest` |
| Kotlin DSL + version catalog | OK |
| min 26 / target 35 | OK |
| R8 release + `proguard-rules.pro` | Declarado (`Log` stripped) |
| Hilt + KSP | OK |
| Lint | No corrido |

## 2. Seguridad

| Control | Implementado |
|---------|----------------|
| FLAG_SECURE | `SecureUi` en `MainActivity` |
| Backups | `allowBackup=false`, data extraction exclude all |
| Logs release | R8 `assumenosideeffects` Log |
| Keystore AES/EC + StrongBox best-effort | `KeystoreManager` |
| SQLCipher | passphrase envuelta, IV generado por el Keystore |
| `.glreg` cifrado+firmado, no portable | `RegistryPack` |
| Portapapeles solo primer plano+foco | `GeneratorViewModel` |
| Sin INTERNET en manifest | Sí |
| Sin secretos hardcodeados | Sí |
| **Gate de sesión** | **No. Se retiró el módulo de autenticación.** |

## 3. UI

- Tema M3 oscuro forzado (`GlTheme`).
- `enableEdgeToEdge` + `WindowInsets.safeDrawing` / `navigationBars`.
- Arranque directo al Generador; bottom bar Generador ↔ Registro.
- Countdown 1 Hz solo con pestaña Registro visible; Perpetua / Vencida.

## 4. Permisos y manifest

Sin permisos propios (los de biometría se retiraron). Queries WhatsApp/SMS. Activity única exported launcher. `networkSecurityConfig` sin cleartext.

## 5. Bugs reales encontrados y corregidos en dispositivo

Estos no los detectable ningún test JVM; aparecieron al ejecutar en hardware:

1. **`InvalidAlgorithmParameterException: Caller-provided IV not permitted`.** `KeystoreManager` fija `setRandomizedEncryptionRequired(true)` pero `CryptoEngine` pasaba IV del llamante. Rompía el primer arranque tras instalación limpia, al crear la BD. Corregido con try/catch: se usa `cipher.iv` del Keystore.
2. **`UnsatisfiedLinkError` en instrumentados.** `HiltTestRunner` sustituye la Application, así que `GlApplication.onCreate()` no corría y `libsqlcipher.so` nunca se cargaba. Creado `core/AppRuntime.kt`, invocado desde `Application` y desde `ProvideModule.db()`.
3. **Dos errores de compilación en `src/main`**: import `semantics` ausente en `DetailScreen.kt` y un `run {}` inválido sobre un *paquete* en `RegistroRepositoryImpl.kt`.

## 6. Limitaciones restantes (conocidas)

1. **No hay autenticación.** Quien tenga el teléfono desbloqueado accede al registro. Es el cambio de mayor impacto.
2. Happy path (`envelope` → generar → registrar) **sin cobertura verde**: `LicenseGenerationE2ETest` en `@Ignore`.
3. `.glreg` atado al Keystore del teléfono.
4. Sin `devicePub` → cifrado de licencia no exclusivo del cliente.
5. Reloj local; sin revocación remota.
6. ECDH Keystore `AGREE_KEY` solo API 31+.
7. Room test in-memory no usa SQLCipher.
8. FLAG_SECURE puede impedir leer el portapapeles en algunos OEMs; el usuario puede pegar a mano.
9. Release sin `signingConfig` en Gradle (firma manual).
10. No hay `gradlew` ni `gradle-wrapper.jar` en el repo.

**Veredicto:** app instalada y recorrida a mano en API 35: arranque, Generador, validación de envelope inválido, Registro, y roundtrip export→import `.glreg`. Falta el happy path cifrado por la limitación 2.
