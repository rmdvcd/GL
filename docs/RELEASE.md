# APK / AAB de release

No hay keystore en el repo (correcto). Firma en tu máquina.

## Android Studio

1. **Build > Generate Signed App Bundle or APK**
2. Crear o elegir un `.jks` **fuera** de `GL/`
3. Build type **release**
4. AAB para Play; APK para sideload

R8 ya está `isMinifyEnabled = true` en `release`.

## CLI

```bash
# Requiere signingConfig en app/build.gradle.kts o gradle.properties locales
# (NO commitear storePassword / keyPassword)
# Este repo no incluye gradlew: usar `gradle` 8.9 en el PATH.

gradle :app:assembleRelease
# APK: app/build/outputs/apk/release/

gradle :app:bundleRelease
# AAB: app/build/outputs/bundle/release/
```

Ejemplo local (no versionar):

```
GL_STORE_FILE=/ruta/gl.jks
GL_STORE_PASSWORD=...
GL_KEY_ALIAS=gl
GL_KEY_PASSWORD=...
```

Este proyecto **no** incluye ese `signingConfig` para no meter secretos.

## Comprobaciones post-build

- Instalar el APK firmado en API 26+.
- Confirmar FLAG_SECURE (screenshot del sistema en negro).
- `allowBackup=false`.
- Sin permiso `INTERNET` en el manifest.
