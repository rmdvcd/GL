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
- Validación obligatoria de hardware key: `AppError.DevicePubRequired`.
- Ventana de validez de solicitudes: `AppError.RequestExpired` (máximo 72 horas).
- Canonicalización segura: Length-Prefixing canónico de 4 bytes big-endian en `CryptoEngine.canonical()`.

## Flujo de Licencias y Códigos

- **Formato del código emitido:** 16 caracteres alfanuméricos agrupados en 4 bloques de 4 (`XXXX-XXXX-XXXX-XXXX`).
- **Clave Maestra:** `GLMK-PERM-MAXS-9999` expuesta en la pestaña Claves, utilizable exclusivamente en builds `DEBUG` (`BuildConfig.DEBUG`).
- **Acumulación de tiempo remanente (Rollover):** Si el cliente tiene una licencia activa no vencida (mismo tipo o diferente), los días restantes se acumulan automáticamente a la nueva licencia emitida (salvo perpetuas o periodos de prueba).
- **Revalidación:** Si una licencia previa ha vencido y el usuario solicita el mismo tipo, se procesa como revalidación.
- **Deduplicación de transacciones:** El generador exige comprobación cruzada de `txCliente` y `txSms`, y persiste el número de transacción para impedir reuso.

## Insets

`enableEdgeToEdge` + `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` + `navigationBars` en la barra.

## Notas

- `FLAG_SECURE` se activa en `MainActivity.onCreate()` vía `SecureUi.enableFlagSecure`.
- No hay sesión ni lock: `Route.Lock`, `LockViewModel` y `PreferenciasRepository` ya no existen.
- Para la guía completa de integración del sistema de licencias en apps cliente, mitigación de vulnerabilidades (Opción A) y consumo por otras IAs, consultar `SISTEMA_LICENCIAS_CLIENTES.md`.
