# AGENTS.md — GL

Generador de licencias Android offline. Kotlin + Compose + Hilt + Room/SQLCipher. Lee `README.md` para el producto y `docs/ARCHITECTURE.md` para las capas.

## Entorno de build (verificado en esta máquina)

El repo **no tiene `gradlew` ni `gradle-wrapper.jar`**, solo `gradle/wrapper/gradle-wrapper.properties`. Usa el `gradle` 8.9 del PATH o el wrapper de Android Studio.

En esta máquina, la forma que funciona:

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"   # el default del sistema es JDK 25 y NO compila
$env:ANDROID_HOME="C:\Users\666\AppData\Local\Android\Sdk"
$gradle="C:\Users\666\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat"
& $gradle :app:testDebugUnitTest
```

- `local.properties` no existe y `.gitignore` lo ignora. Sin `ANDROID_HOME`, ponlo.
- `adb` viene empaquetado con scrcpy, no con el SDK.
- Dispositivo de referencia: serial `4bc9f8f3`, API 35, arm64-v8a.

```bash
adb shell pm clear dev.gl.license.debug   # OBLIGATORIO antes de instrumentados
gradle :app:connectedDebugAndroidTest
```

## Comandos

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest       # 110 tests / 22 suites / 0 fallos
gradle :app:connectedDebugAndroidTest
```

Para filtrar un solo test instrumentado, `am instrument` directo (PowerShell mangla `-Pandroid.testInstrumentationRunnerArguments.class`):

```bash
adb shell am instrument -w -e class dev.gl.license.KeystoreCryptoTest \
  dev.gl.license.debug.test/dev.gl.license.HiltTestRunner
```

## Los instrumentados de UI NO corren en este dispositivo

Restricción de background-activity-start de Android 15 sobre `ActivityScenario`. Secuencia real en `logcat -v time`:

```
START ... InstrumentationActivityInvoker$EmptyActivity   result code=0    <- ActivityScenario monta su activity vacía
Abort background activity starts from 10337
START ... MainActivity  flg=0x10008000                   result code=102  <- ABORTADA
```

`102` = `START_ABORTED`. `ActivityScenario` espera una Activity que nunca existirá: cuelgue eterno, `Process crashed.` y **`adb logcat -d -b crash` vacío**. Afecta a `SmokeTest`, `MainFlowsTest` y `LicenseGenerationE2ETest` (los tres usan `createAndroidComposeRule<MainActivity>()`). `KeystoreCryptoTest` sí corre: no lanza Activity.

- `am instrument` **no expone ningún flag** para desactivarlo (solo `-r -e -p -w -m -f --user --no-hidden-api-checks --no-test-api-access --no-isolated-storage --no-window-animation --abi`).
- Lanzar la app a mano antes de instrumentar **no** lo arregla: el proceso del test pasa a BG de todas formas.
- Para diagnosticar UI en el dispositivo usa `uiautomator dump` (ver más abajo), no Espresso.

## Antes de culpar al código: ¿están instalados los paquetes?

```bash
adb shell pm list packages | grep gl.license    # debe listar dev.gl.license.debug Y .debug.test
adb shell pm list instrumentation | grep gl.license
```

`connectedDebugAndroidTest` **desinstala ambos APK al terminar**. Si el build se interrumpe (p. ej. matar el daemon de Gradle) los deja fuera, y entonces todo falla con `Error: Activity class ... does not exist`, que parece un bug de la app y no lo es.

**`enabled=0` en `dumpsys package` NO significa deshabilitado**: es `COMPONENT_ENABLED_STATE_DEFAULT`, el estado normal. `stopped=true notLaunched=true` también es normal en un APK recién instalado.

## Verificar en el dispositivo sin instrumentar

Más rápido que un test E2E para comprobar si la app crashea de verdad:

```powershell
adb install -r "app\build\outputs\apk\debug\app-debug.apk"
adb install -r -t "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
adb shell pm clear dev.gl.license.debug
adb logcat -c
adb shell am start -n dev.gl.license.debug/dev.gl.license.presentation.MainActivity
Start-Sleep -Seconds 8
adb logcat -d -b crash                            # VACÍO = no hay crash
adb shell pidof dev.gl.license.debug               # PID = proceso vivo
```

**Room crea la BD de forma perezosa, en la primera consulta.** Tras `pm clear`, arrancar la app **no** crea `databases/`; hay que abrir la pestaña **Registro** para que aparezcan `gl_licenses.db`, `-shm` y `-wal`. No lo tomes como fallo.

Dos señales confirman que la BD SQLCipher abre bien:

```powershell
adb shell run-as dev.gl.license.debug ls databases/   # tras abrir Registro
adb logcat -d | Select-String "libsqlcipher.so"      # debe acabar en ": ok"
```

Fíjate en el **PID**: `adb logcat -d -b crash` sin filtrar devuelve ruido de MIUI, Finsky y otras apps. Filtra siempre por el PID de GL con `--pid=`.

Para recorrer la UI, parsea `uiautomator dump` en PowerShell (no en shell: el quoting de `adb shell` + PowerShell rompe `$`, comillas y corchetes). Varios nodos comparten texto (`GUARDAR` aparece en la toolbar y en el botón): **elige el de mayor área**, no el primero que encuentres.

Tres lecciones más de `uiautomator`, verificadas con la pantalla Contacto:
- **La pestaña seleccionada pierde su `contentDescription`.** M3 mete el icono en la píldora del indicador y el nodo sale con `content-desc=""`. Si buscas la pestaña activa y no aparece, no es que falte: navega a otra y vuelve a mirar.
- **El canal `adb shell cat` → PowerShell mangla lo no-ASCII.** Todo `desc` con `ñ/é/ú/ó` falla al casar aunque el patrón esté bien construido. Para automatizar usa `desc` 100% ASCII, o localiza por `class="android.widget.Button"` + banda Y de los bounds, o por el texto de los dígitos.
- **El desplegable (`ExposedDropdownMenuBox`) come taps.** Con el menú abierto, el tap al botón de abajo solo lo cierra. Para cerrarlo toca una zona neutra (el título de la pantalla); **no mandes `KEYCODE_BACK` estando en una pestaña**, porque hace pop del NavHost y te saca de la pantalla. Y para leer el `enabled` real de un botón mira el `View` exterior (`clickable="true"`), no el `android.widget.Button` interior, que sale siempre `enabled="true"`.
- **El popup del desplegable NO sale en `uiautomator dump`.** Las filas del menú (`Editar X` / `Eliminar X`) no aparecen en el XML aunque estén visibles; los taps a iconos por content-desc son imposibles y hay que tocar por coordenadas (medidas una vez, reutilizables). En cambio el `AlertDialog` de confirmación SÍ sale: chequear su texto ASCII en el dump confirma que la papelera funcionó.
- **En PowerShell, `if (Tap ...)` se traga los fallos.** La salida Write-Output de la función en posición de condición se captura y un string no vacío es truthy: hasta un `NO ENCONTRADO` entra al `if`. Captura a variable primero, nunca llames en la condición.
- **Ante duda sobre datos, ground truth por DAO con test temporal.** Un `androidTest` efímero que abre Room a mano (como `ProvideModule.db()`: `AppRuntime.ensureInitialized` + clave Keystore + prefs `gl_db_meta` + `SupportOpenHelperFactory` + `ALL`), vuelca con `Log.i` y se lee con `logcat -s`. Se borra después (como se hizo con `ZzKeyDumpTest` y `ZzContactDumpTest`). Sirvió para probar un borrado y para restaurar un dato del usuario borrado por error.

## Trampas que cuestan una hora

1. **`sqlcipher-android` no autocarga `libsqlcipher.so`.** Sin `AppRuntime.ensureInitialized()` antes de abrir Room: `UnsatisfiedLinkError: No implementation found for ...nativeOpen`. Ya está invocado en `GlApplication.onCreate()` y en `ProvideModule.db()`; **no lo quites de ninguno de los dos**.
2. **`GlApplication.onCreate()` no corre en instrumentados.** `HiltTestRunner` sustituye la Application por `HiltTestApplication`. Cualquier init que solo viva en la Application es invisible para los tests.
3. **El IV lo genera el Keystore.** `KeystoreManager` fija `setRandomizedEncryptionRequired(true)`. Pasar IV propio lanza `InvalidAlgorithmParameterException: Caller-provided IV not permitted`. No "simplifiques" `CryptoEngine.aesGcmEncrypt` para quitar el try/catch.
4. **`SecretKey.getFormat()` devuelve `null`** en Android para claves de AndroidKeyStore. Imposible detectar el origen de la clave así. Ya se intentó y falló.
5. **`connectedDebugAndroidTest` desinstala los APK al terminar.** No es un fallo.
6. **Los instrumentados no limpian datos de la app.** Sin `pm clear`, el arranque falla si hay estado previo.
7. **No llames `advanceUntilIdle()`** en tests de ViewModel si el VM arma un `delay()` de auto-bloqueo/temporizador: `Dispatchers.Main` es `UnconfinedTestDispatcher`, y adelantar el reloj dispara el temporizador. Bug real ya cometido y corregido en `LockViewModelTest` (fichero eliminado).
8. **`adb shell input text` pierde las comillas dobles** y este ROM no tiene `cmd clipboard`. Para texto complejo en la UI usa Espresso `performTextInput`, no shell.
9. **`Select-String -Path "...\**\*.kt"` no cubre ficheros en la raíz del paquete** (`**\*.kt` exige ≥1 nivel). Usa `grep`. Esta trampa dio una conclusión errónea sobre `loadLibrary`.
10. `Select-String` devuelve `MatchInfo`: usa `$_.Line.Trim()`, no `$_.Trim()`. Y sobre `$null` eso lanza excepción.

## Estado real (no asumir cobertura completa)

- **El happy path cifrado (`LicenseGenerationE2ETest`) falla en la primera `waitUntil`.** El sobre entra en el campo pero `"Solicitud válida"` nunca aparece. Falló primero por `HiltTestApplication` (sin `AppRuntime`, sin `libsqlcipher.so`) y después —ya con `AppRuntime` instalado— sigue en timeout, sin causa diagnosticada. Los tests de `@Ignore` que quedan no cubren el happy path.
- Para depurar el E2E, acuña un sobre en vez de teclearlo: `ClientRequestHelper.buildEnvelope(req, KeystoreManager.ecdhPair().public, json)` es exactamente lo que un cliente haría, y hay `KeystoreCryptoTest` instrumentado que prueba el criptográfico contra el Keystore real.
- El camino **fácil de depurar con el E2E**: `ValidarSolicitudUseCase` / `FieldValidator` son puros y no tocan el Keystore. Un `descifrarSolicitud` que devuelve `Err(InvalidPayload)` con un sobre recién minteado significa que el problema está en `CryptoGatewayImpl.descifrarSolicitud` (AAD, `info` o verificación de firma), no en la inyección de texto de Espresso.
- Verificado a mano en API 35: arranque, Generador, validación de envelope inválido, Registro, export→import `.glreg` (roundtrip, "Importación correcta (0)"), fichero exportado con cabecera `GLRG` + binario cifrado.
- No hay envelope de muestra en el repo: uno minteado con otro Keystore no lo puede descifrar este.
- **No hay autenticación.** Se retiró el módulo completo (PIN, biometría, lock por idle). `FLAG_SECURE` sigue activo, pero el registro solo se protege con cifrado en reposo.
- `CryptoEngine` es un objeto **deliberadamente puro sin Android Keystore** para poder testear en JVM. `pbkdf2HmacSha256` y `constantTimeEquals` son primitivas genéricas que quedan, aunque ya no haya PIN.

## Convenciones

- `contentDescription` **Solicitud cifrada** en el campo del Generador. Los instrumentados dependen de él, junto a `Generar licencia`, `Registrar`, `Generador`, `Registro`, `Sin licencias`. No los cambies para arreglar un test.
- La casilla de pago es un `Row` con `toggleable(..., Role.Checkbox)` y **no tiene `contentDescription`**. Búscala con `isToggleable() and hasText("Pago realizado")`, nunca por descripción.
- Para avanzar de `Licencia generada.` a `Registrada.` hace falta disparar `ON_RESUME` (es lo que llama a `onReturnedFromShare()` y habilita el botón). En un test: `scenario.moveToState(CREATED)` y luego `moveToState(RESUMED)`. `activity.onResume()` es `protected` y no compila.
- `AppError.userMessage` es genérico y sin secretos. Fail-closed.
- `ClientRequestHelper` no se usa en producción; es la referencia para que el cliente construya envelopes.
- Contrato criptográfico v1 inmutable. Cambiar AAD, curva o KDF = `v+1`. Añadir un campo al payload NO sube la versión: la firma va sobre `epk + ciphertext`, nunca sobre el plaintext.
- **`appName` es obligatorio** en `SolicitudLicencia` y en `Licencia` (regex `^[\p{L}0-9 ._'-]{2,80}$`). El cliente tiene que mandarlo o `validateRequest` devuelve `InvalidPayload`. Si el JSON no trae la clave, kotlinx lanza `MissingFieldException` y `descifrarSolicitud` la captura en su `catch (_: Exception)` (CryptoGatewayImpl.kt:50) → `InvalidPayload`, no crash.
- `FieldValidator.validateLicense` **reconstruye una `SolicitudLicencia` artificial** para reusar `validateRequest`. Si añades un campo a `SolicitudLicencia`, propágalo ahí o toda licencia emitida se rechazará a sí misma al revalidarse en la importación.
- `secundarias` en el flujo v1 es opcional y solo aplica a otras apps (`Secundarias.aplicaA`); ausente/`null` significa app anterior, y `-1`, `11` o no entero devuelven `InvalidSecundarias`. En `Licencia` v1 se omite la clave cuando es `null`, `precioCobrado` es `@Transient` (no viaja firmado ni en `.glreg`), y el precio queda congelado al emitir. SPVI 0.23.1 no usa el flujo v1: solicitud `SPVIR1:` → `Spvi23.abrir` (HKDF `SPVI-R1`, AAD `SPVI-R1|`), licencia corta `SPVI2:` de 210 caracteres (no 216) → `Spvi23.sellarLicencia` (HKDF `SPVI-L2`); cualquier solicitud v1 de SPVI se rechaza con `SpviDesactualizada` y no se emite licencia larga. Ver `CONTEXTO_LICENCIAS.md`.
- Room está en **version 5**. `GlMigrations.M_1_2` añade `appName TEXT NOT NULL DEFAULT ''`; `M_2_3` crea `contact_methods (id PK "$kind:$value", kind, value, createdAtIso)`; `M_3_4` añade `secundarias INTEGER` y `precioCobrado INTEGER`, ambos opcionales; `M_4_5` añade `codigoCorto TEXT` (código SPVI2, nullable). `DatabaseMigrationTest` corre las migraciones sobre el DDL real y verifica que las filas previas sobreviven. Si añades columna, sube versión, añade Migration **y** el test.
- `.glreg` con licencias emitidas antes de un campo obligatorio nuevo **no importa**: `importar` corre `EnvelopeValidator.license` sobre cada una y falla con `ImportFailure`.
- `.glreg` no es portable entre teléfonos (firma Keystore local).
- **Las claves públicas de GL son por dispositivo, no del producto.** `KeystoreManager` las genera en el Android Keystore local (`gl.ecdh.p256.v1` y `gl.sign.ec.p256.v1`). Si borras datos de GL o cambias de teléfono, sale otro par y el cliente que fijó las viejas rechazará todo.
  - **`pm clear dev.gl.license.debug` las regenera.** Verificado: un `pm clear` cambió las huellas de `sha256:4ea0:…`/`sha256:219e:…` a `sha256:7b51:…`/`sha256:8a91:…`. Cualquier despliegue de pruebas que haga `pm clear` invalida las claves que el cliente haya fijado. Si el cliente externo guarda huellas fijas, hay que exportarlas **después** del último `pm clear`, no antes.
  - La pestaña **Claves** (`presentation/trust/TrustScreen.kt`) las exporta en Base64 SPKI + huella `sha256:` en hex; la huella se compara a ojo, por eso va en monoespaciada.

## Trampas de la sesión de la pantalla de Claves

- **`stringResource` dentro de `onClick` no compila**: `Compose invocations can only happen from the context of a @Composable function`. Resuélvelo en el cuerpo del composable (`val label = stringResource(...)`) y usa `val` en el lambda.
- **`StatusBanner(error = null, success = ...)` no se muestra nunca**: su `visible = error != null`. Para un mensaje de éxito usa un `Text` con el color del tema.
- `MetaRow` reserva `width(120.dp)` al label: inútil para una clave de ~92 caracteres. `TrustScreen` pinta la clave en `FontFamily.Monospace` con su propia etiqueta.
- En `adb shell uiautomator dump`, un nodo con `bounds="[0,0][0,0]"` está **fuera de pantalla**, no en el origen. Haz scroll antes de asumir que el botón no existe. Y parsea los bounds con `[int]$matches[1]` explícito: concatenarlos como string da `tap 439966 10911118` y falla sin quejarse.
