# TESTING.md — GL

## Antes de nada

Este repo **no tiene `gradlew` ni `gradle-wrapper.jar`** (solo `gradle/wrapper/gradle-wrapper.properties`). Usa el wrapper de Android Studio o un `gradle` 8.9 en el PATH.

- **JDK 17 obligatorio.** Con un JDK superior el build falla.
- `local.properties` con `sdk.dir`, o `ANDROID_HOME` en el entorno.
- `adb` en el PATH. Un solo dispositivo, o `ANDROID_SERIAL=<serial>`.

## Suites

Unitarios (JVM, sin dispositivo):

```bash
gradle :app:testDebugUnitTest
```

Instrumentados (dispositivo o emulador):

```bash
adb shell pm clear dev.gl.license.debug   # ver gotcha 2
gradle :app:connectedDebugAndroidTest
```

## Qué cubre cada suite

| Suite | Cubre |
|-------|-------|
| `CryptoEngineTest` | AES-GCM+AAD, PBKDF2, ECDH, ECDSA, HybridBox, `.glreg` |
| `DomainLayerTest` | modelos, repositorio in-memory, use cases |
| `EdgeCasesTest` | límites y casos degenerados |
| `FieldValidatorTest` | teléfono, CI, fechas, tipo, nonce |
| `GeneratorViewModelTest` | validación, pago, generar, registrar |
| `InMemoryLicenseRepositoryTest` | Room in-memory (Robolectric, **sin** SQLCipher) |
| `RegistryViewModelTest` | listado, countdown, import/export |
| `KeystoreCryptoTest` *(instrumentado)* | AES-GCM con clave real de AndroidKeyStore, IV del Keystore |
| `MintRequestEnvelopeTest` *(instrumentado)* | acuña un sobre válido para este teléfono e imprime el JSON |
| `SmokeTest` *(instrumentado)* | Generador visible al lanzar, no hay pantalla de lock |
| `MainFlowsTest` *(instrumentado)* | bottom bar Generador ↔ Registro |
| `LicenseGenerationE2ETest` *(instrumentado)* | **rojo** — ver abajo |

Estado verificado: **29 tests unitarios / 7 suites / 0 fallos**. En verde también
`KeystoreCryptoTest`, `MintRequestEnvelopeTest`, `SmokeTest` y `MainFlowsTest`.

### `LicenseGenerationE2ETest` falla en la primera espera

El recorrido es: escribir el sobre → esperar `"Solicitud válida"` → marcar el pago →
`Generar licencia` → `Licencia generada.` → `ON_RESUME` → `Registrar` → comprobar Room.

Falla en el **primer `waitUntil`**: el texto entra en el campo pero `"Solicitud válida"`
nunca aparece. Historizado, el fallo se dividió en dos:

1. Primero falló con `UnsatisfiedLinkError` de SQLCipher, porque los instrumentados corren
   bajo `HiltTestApplication` y `GlApplication.onCreate()` no se ejecuta (gotcha 2). Eso ya
   está resuelto con `AppRuntime`.
2. Con `AppRuntime` en su sitio, **vuelve a fallar en el mismo punto**. El sobre lo acuña
   `ClientRequestHelper` en el propio test contra `KeystoreManager.ecdhPair().public`, así que
   criptográficamente debería ser válido. Causa sin diagnosticar.

Pista para retomarlo: `ValidarSolicitudUseCase` y `FieldValidator` son puros y no tocan el
Keystore. Inyecta `SeguridadRepository` en el test y llama a `descifrarSolicitud` con un sobre
recién minteado. Si devuelve `Err(InvalidPayload)`, el problema está en
`CryptoGatewayImpl.descifrarSolicitud` (AAD, `info` o verificación de firma) y no en la
inyección de texto de Espresso.

No hay sobre de muestra en el repo: cualquier sobre minteado con el Keystore de otro teléfono
no lo puede descifrar este. Usa `MintRequestEnvelopeTest` para generar uno válido aquí.

## Gotchas que cuestan una hora

1. **`sqlcipher-android` no autocarga su `.so`.** Si abres Room sin `AppRuntime.ensureInitialized()` primero, revienta con `UnsatisfiedLinkError: No implementation found for ...SQLiteConnection.nativeOpen`. `core/AppRuntime.kt` lo carga; está invocado desde `GlApplication.onCreate()` y desde `ProvideModule.db()`.
2. **`HiltTestRunner` sustituye la Application por `HiltTestApplication`**, así que `GlApplication.onCreate()` **no corre en los instrumentados**. Por eso lo anterior está además en `ProvideModule.db()` y no solo en la Application.
3. **Los instrumentados no limpian datos de la app.** Si el usuario dejó un fixture o un estado previo, los tests de arranque fallan. `adb shell pm clear dev.gl.license.debug` antes de la corrida.
4. **`connectedDebugAndroidTest` desinstala los APK al terminar.** Comportamiento normal de AGP, no un fallo. Reinstala con `gradle :app:installDebug` si los quieres en el teléfono.
5. **`SecretKey.getFormat()` devuelve `null`** para claves de `AndroidKeyStore` en Android. No intentes detectar el origen de una clave así.
6. El IV de la BD lo genera el Keystore (`setRandomizedEncryptionRequired(true)`). Pasar un IV propio lanza `InvalidAlgorithmParameterException: Caller-provided IV not permitted`. `CryptoEngine.aesGcmEncrypt` lo resuelve con try/catch.
7. Tests de ViewModel: **no llames `advanceUntilIdle()`** si el ViewModel arma un `delay()` de auto-bloqueo o similar sobre el mismo scheduler; `Dispatchers.Main` es un `UnconfinedTestDispatcher` y adelantar el reloj dispara el temporizador.
8. **`adb logcat -d -b crash` sin filtrar devuelve ruido de MIUI, Finsky y otras apps.** Antes de concluir que "la app crashea", filtra por el PID de GL (`adb shell pidof dev.gl.license.debug`) y mira solo esas líneas.
9. **`adb shell input text` se come las comillas dobles** y este ROM no tiene `cmd clipboard`. Para meter JSON complejo en la UI usa Espresso `performTextInput`, no el shell.

## Ver si la app crashea de verdad (sin instrumentar)

Es el camino más corto para separar "crash real" de "el test está mal":

```powershell
adb install -r "app\build\outputs\apk\debug\app-debug.apk"
adb shell pm clear dev.gl.license.debug
adb logcat -c
adb shell am start -n dev.gl.license.debug/dev.gl.license.presentation.MainActivity
Start-Sleep -Seconds 8
adb logcat -d -b crash                 # vacío = sin crash
adb shell pidof dev.gl.license.debug    # con PID = sigue vivo
```

Para confirmar que la BD SQLCipher abre (no basta con que la pantalla pinte):

```powershell
adb shell run-as dev.gl.license.debug ls databases/   # gl_licenses.db debe existir
adb logcat -d | Select-String "libsqlcipher.so"      # debe terminar en ": ok"
```

UI/IX: `docs/UI_UX_IX.md`. Heap de tests: 1024m (`app/build.gradle.kts`).
