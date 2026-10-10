# Arquitectura GL

## Capas

```
presentation  →  Compose, Navigation, ViewModels
domain        →  modelos de contrato, repositorios, casos de uso, validadores
data          →  Room/SQLCipher, mappers, registro cifrado
security      →  Keystore, AES-GCM, ECDH, ECDSA, FLAG_SECURE
core          →  Hilt, errores, reloj, AppRuntime
```

Dependencias: presentation → domain ← data. security lo consume data. Sin ciclos.

## DI

Hilt `@HiltAndroidApp` + módulos `BindModule` / `ProvideModule` en `core/Di.kt`.

`ProvideModule.db()` llama primero a `AppRuntime.ensureInitialized()`. No es opcional: el AAR `sqlcipher-android` no autocarga `libsqlcipher.so`, y en instrumentados `GlApplication.onCreate()` no se ejecuta porque `HiltTestRunner` sustituye la Application. Sin esa llamada, Room revienta con `UnsatisfiedLinkError`.

## Navegación

`Route.Generator | Registry | Registry/{id}`. Start = Generator. No hay `Route.Lock`.

## Suposiciones

- Un solo usuario local (el emisor).
- Contrato criptográfico v1 inmutable; cambios = `v+1`.
- SQLCipher passphrase envuelta con AES del Keystore; el IV lo genera el Keystore.
- StrongBox best-effort.
- Sin backend.
- Sin autenticación: el registro se protege solo con cifrado en reposo.
