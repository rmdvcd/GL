# Integración

## DI (Hilt)

`BindModule`: LicenciaRepository, RegistroRepository, SeguridadRepository, SecureClock.  
`ProvideModule`: Json, GlDatabase SQLCipher (Room v6), LicenseDao.  
ViewModels y casos de uso: constructor `@Inject` (no hace falta `@Binds`).

`ProvideModule.db()` llama a `AppRuntime.ensureInitialized()` antes de construir la BD: el AAR `sqlcipher-android` no autocarga `libsqlcipher.so` y en los instrumentados `GlApplication.onCreate()` no corre (ver `AGENTS.md`).

## Navegación

`generator` | `registry` | `registry/{id}`. Start = `generator`. Bottom bar solo en las dos primeras.

## Errores y Seguridad

- `AppError.userMessage` / `asUiMessage()`: genéricos, sin secretos.
- Deduplicación de transacciones: `AppError.TransactionAlreadyUsed(tx)`.
- `devicePub` es obligatorio para v1 (`AppError.DevicePubRequired`), pero GL no verifica attestation ni posesión de la clave privada; no debe describirse como una clave hardware-validada.
- Frescura: `AppError.RequestExpired` fuera del margen exacto de 72 horas de antigüedad / 2 horas de adelanto, tanto en v1 como al emitir SPVI; `AppError.ClockFailure` si no puede leerse/parsearse la hora. Usa reloj de pared, sin resistencia a rollback ni consumo persistente de nonce.
- AAD v1: `CryptoEngine.canonical()` concatena los campos UTF-8 con su longitud de 4 bytes big-endian; no es texto unido por `|`.

## Flujo de Licencias y Códigos

- **Formato del código emitido:** 16 caracteres alfanuméricos agrupados en 4 bloques de 4 (`XXXX-XXXX-XXXX-XXXX`).
- **Clave Maestra de desarrollo:** la tarjeta solo se muestra en `DEBUG`; `MasterKey.isMasterKey` falla cerrado por defecto (`isDebug = false`). Es una credencial de prueba publicada en el código, no un secreto. En apps cliente debe rechazarse en release.
- **Acumulación de tiempo remanente (Rollover):** Si el cliente tiene una licencia activa no vencida (mismo tipo o diferente), los días restantes se acumulan automáticamente a la nueva licencia emitida (salvo perpetuas o periodos de prueba).
- **Revalidación:** Si una licencia previa ha vencido y el usuario solicita el mismo tipo, se procesa como revalidación.
- **Transacciones:** el generador exige que coincidan `txCliente` y `txSms`, y consulta el historial persistido antes de emitir (incluido SPVI). No hay índice único/reserva atómica: hasta registrar la licencia existe una ventana de carrera y no se debe presentar como prevención absoluta de reuso.

## Insets

`enableEdgeToEdge` + `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` + `navigationBars` en la barra.

## Notas

- `FLAG_SECURE` se activa en `MainActivity.onCreate()` vía `SecureUi.enableFlagSecure`.
- No hay sesión ni lock: `Route.Lock`, `LockViewModel` y `PreferenciasRepository` ya no existen.
- Para la guía completa de integración del sistema de licencias en apps cliente, mitigación de vulnerabilidades (Opción A) y consumo por otras IAs, consultar `SISTEMA_LICENCIAS_CLIENTES.md`.
