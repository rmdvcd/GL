# Pruebas y validación — GL

## Entorno requerido

El repositorio no incluye `gradlew` ni `gradle-wrapper.jar`; contiene solo `gradle/wrapper/gradle-wrapper.properties`. Se requiere JDK 17, Gradle 8.9 y Android SDK (compile/target API 35), configurado mediante `ANDROID_HOME` o `local.properties`.

En la sesión de auditoría actual no están disponibles `java`, `gradle` ni `ANDROID_HOME`. **No se han ejecutado build, pruebas unitarias, lint ni instrumentados.** El número de pruebas anotadas más abajo es un conteo estático, no un resultado de ejecución.

## Ejecutar

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
adb shell pm clear dev.gl.license.debug   # antes de instrumentados
gradle :app:connectedDebugAndroidTest
```

## Cobertura del árbol actual

Hay **167 métodos `@Test` en 27 clases de pruebas unitarias** bajo `app/src/test/java` y **9 métodos `@Test` instrumentados en 4 clases de prueba** bajo `app/src/androidTest/java` (más `HiltTestRunner`, que no contiene tests). `LicenseGenerationE2ETest` es uno de los instrumentados y sigue marcado `@Ignore`.

| Prueba | Cobertura principal |
|---|---|
| `ClientRequestHelperTest` | Round-trip JVM del envelope de solicitud v1, IV/AAD y `devicePub` obligatorio |
| `CryptoEngineTest` | AES-GCM, AAD, ECDH/ECDSA, `HybridBox`, P-256 y `.glreg` |
| `FieldValidatorTest`, `EdgeCasesTest` | Validaciones de campos, límites y fechas |
| `DomainLayerTest`, `EvaluarSolicitudHistorialTest` | Emisión, reloj, rollover e historial fail-closed |
| `GeneratorViewModelTest`, `TransactionMatchingTest` | Carreras de UI, pago y deduplicación de transacciones |
| `RegistryArchiveTest`, `RegistryViewModelTest`, `InMemoryLicenseRepositoryTest` | Formato y metadatos `.glreg`, lista, importación y persistencia en Room in-memory |
| `Spvi23*`, `RenovacionSpviTest`, `SpviGlregTest`, `ExtraccionSpviTest` | Solicitud SPVI, vector, extracción, emisión, renovación y límites |
| `KeystoreCryptoTest` *(instrumentado)* | AES-GCM con clave real de Android Keystore; **no** cubre ECDH del flujo completo |
| `SmokeTest`, `MainFlowsTest` *(instrumentados)* | Arranque y navegación UI |
| `LicenseGenerationE2ETest` *(instrumentado, ignorado)* | Sobre → validación → emisión → registro; fixture actualizado, happy path aún sin validar |

## Límite conocido del E2E

Una ejecución anterior falló antes de mostrar `Solicitud válida`; primero por inicialización de SQLCipher bajo `HiltTestApplication` y, después de corregirla, por una causa no diagnosticada. El helper y el fixture se corrigieron desde entonces: ahora incluyen `devicePub` y números de transacción coincidentes. El test continúa ignorado hasta poder ejecutarlo en un runner Android compatible; no se afirma que compile ni que pase.

Los instrumentados de UI también tuvieron una restricción de inicio de actividad en un dispositivo de referencia Android 15/API 35. Es un resultado histórico, no confirmado para otros runners. `KeystoreCryptoTest` prueba AES con Android Keystore, no el ECDH de la licencia ni la matriz API 26–30.

## Precauciones de pruebas

- No llames `advanceUntilIdle()` en tests de ViewModel que usen temporizadores sobre el mismo scheduler.
- `connectedDebugAndroidTest` puede desinstalar los APK de prueba al terminar; vuelve a instalar si hace falta.
- Las claves públicas/privadas del Keystore son locales al dispositivo; un envelope creado para otra instalación GL no se puede descifrar aquí.
- `CryptoEngine` mantiene las primitivas JVM independientes de Android Keystore; el fallback de IV para claves AES de Keystore está cubierto por `KeystoreCryptoTest`.
- Los límites de transacción y `.glreg` deben probarse además con archivos grandes/truncados en un dispositivo o runner real.
