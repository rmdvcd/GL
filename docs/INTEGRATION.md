# Integración

## DI (Hilt)

`BindModule`: LicenciaRepository, RegistroRepository, SeguridadRepository, SecureClock.  
`ProvideModule`: Json, GlDatabase SQLCipher, LicenseDao.  
ViewModels y casos de uso: constructor `@Inject` (no hace falta `@Binds`).

`ProvideModule.db()` llama a `AppRuntime.ensureInitialized()` antes de construir la BD: el AAR `sqlcipher-android` no autocarga `libsqlcipher.so` y en los instrumentados `GlApplication.onCreate()` no corre (ver `AGENTS.md`).

## Navegación

`generator` | `registry` | `registry/{id}`. Start = `generator`. Bottom bar solo en las dos primeras.

## Errores

`AppError.userMessage` / `asUiMessage()`: genéricos, sin secretos.

## Insets

`enableEdgeToEdge` + `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` + `navigationBars` en la barra.

## Notas

- `FLAG_SECURE` se activa en `MainActivity.onCreate()` vía `SecureUi.enableFlagSecure`.
- No hay sesión ni lock: `Route.Lock`, `LockViewModel` y `PreferenciasRepository` ya no existen.
