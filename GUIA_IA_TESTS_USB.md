# Guía para una IA: tests GL en dispositivo USB (OpenCode Desktop)

Documento para un agente que corre **en el PC del usuario**, con **OpenCode Desktop**, un **teléfono o emulador Android conectado por USB** (o ADB inalámbrico ya emparejado) y el **Android SDK** instalado. Este texto no asume que el sandbox Arena tenga ADB.

## Objetivo

Ejecutar la suite de GL **sin red de la app**, sobre el proyecto en disco, y devolver resultados (pass/fail, logs). No reescribir cripto ni la app.

## Precondiciones (comprobar en este orden)

1. OpenCode Desktop abierto en la carpeta del repo (`GL/`, donde están `settings.gradle.kts` y `app/`).
2. **JDK 17**: `java -version`. Con un JDK superior el build falla.
3. Android SDK: `ANDROID_HOME` o `ANDROID_SDK_ROOT` (típico `~/Android/Sdk`), o `local.properties` con `sdk.dir`.
4. `adb` en PATH o `$ANDROID_HOME/platform-tools/adb`.
5. Dispositivo:
   - USB: depuración USB activa; `adb devices` muestra `device` (no `unauthorized`).
   - Si `unauthorized`: el usuario debe aceptar el diálogo RSA en el teléfono.
6. API ≥ 26. `adb shell getprop ro.build.version.sdk`.
7. Gradle: **este repo no incluye `gradlew` ni `gradle-wrapper.jar`**, solo `gradle/wrapper/gradle-wrapper.properties`. Usar un `gradle` 8.9 en el PATH o el wrapper de Android Studio. En Linux/macOS: `chmod +x gradlew` si lo generas.

**No** hace falta internet en el teléfono. La primera sync Gradle **sí** puede necesitar red en el PC para dependencias.

## Qué no hacer

- No instalar apps de terceros ni abrir puertos innecesarios.
- No desactivar FLAG_SECURE ni cambiar el manifiesto.
- No publicar APK firmado con un JKS inventado.
- No esperar Firebase ni servidor.
- No reinstalar el APK a mitad de una corrida instrumentada.
- Argon2id ya no se usa (se retiró el PIN). No perder tiempo buscándolo.

## Comandos (cwd = raíz `GL/`)

```bash
# 1. Dispositivo
adb devices -l
adb shell getprop ro.build.version.sdk

# 2. Unitarios (JVM, no necesitan USB)
gradle :app:testDebugUnitTest --info

# 3. Instalar + instrumentados en el USB
adb shell pm clear dev.gl.license.debug
gradle :app:connectedDebugAndroidTest

# Alternativa en dos pasos
gradle :app:installDebug
gradle :app:connectedDebugAndroidTest
```

Si hay **varios** dispositivos: `ANDROID_SERIAL=<serial> gradle :app:connectedDebugAndroidTest`  
Serial = primera columna de `adb devices`.

## Qué debe pasar

| Suite | Criterio de éxito |
|-------|-------------------|
| `testDebugUnitTest` | Crypto, validación, ViewModels, Room in-memory (Robolectric, **sin** SQLCipher), countdown. Referencia actual: 29 tests / 7 suites / 0 fallos |
| `connectedDebugAndroidTest` | `SmokeTest` y `MainFlowsTest`: al lanzar se ve **«Generador»** y **no** existe pantalla de lock; la bottom bar cambia entre «Generador» y «Registro». `KeystoreCryptoTest`: roundtrip AES-GCM con clave real de AndroidKeyStore |

Textos/TalkBack que **no** cambiar al “arreglar” fallos de UI test:

- `contentDescription` **Solicitud cifrada** (campo del Generador)
- `Generar licencia`
- `Registrar`
- `Generador` / `Registro` (pestañas)

Informe: extraer `BUILD SUCCESSFUL` / `FAILED`, y de `app/build/reports/` el HTML de tests.

## Fallos típicos

| Síntoma | Qué hacer |
|---------|-----------|
| `adb: no devices` | Cable, depuración, `adb kill-server && adb start-server` |
| `unauthorized` | Aceptar huella en el teléfono |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | `adb uninstall dev.gl.license.debug` (debug tiene `applicationIdSuffix`) |
| Gradle no encuentra SDK | `local.properties` → `sdk.dir=/ruta/al/Sdk` |
| `UnsatisfiedLinkError` en `nativeOpen` | La `.so` de SQLCipher no se cargó. Verificar `AppRuntime.ensureInitialized()` antes de abrir Room |
| Tests de arranque fallan sin tocar código | Estado previo en el dispositivo: `adb shell pm clear dev.gl.license.debug` |
| La app “desapareció” tras los tests | `connectedAndroidTest` **desinstala los APK al terminar**. Comportamiento normal de AGP |
| `connectedDebugAndroidTest` 0 devices | USB desconectado a mitad; repetir `adb devices` |
| `Failed to create MD5 hash for file content` | Estado incremental corrupto: `gradle --stop`, borrar `app/build/outputs/androidTest-results` y reintentar |

## Alcance opcional (solo si el usuario lo pide)

Flujos manuales post-tests: arrancar la app, comprobar Generador y Registro, exportar/importar `.glreg`. **No hay envelope de muestra cifrado con el Keystore de ese teléfono en el repo**; no inventar uno que “debería” descifrar. `LicenseGenerationE2ETest` mintea el suyo y está en `@Ignore` por un fallo sin diagnosticar en `ValidarSolicitudUseCase`.

## Informe que debe devolver la IA

1. `adb devices` y API.
2. Resultado unitarios (nº tests, fallos).
3. Resultado instrumentados.
4. Rutas a reportes.
5. Si falló: últimas 30 líneas del task Gradle y causa probable de la tabla de arriba.
