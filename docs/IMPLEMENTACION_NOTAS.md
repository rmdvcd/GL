# Notas de implementación

Baseline Node 22.22.3: 107 pasan, 0 fallan. Las 7 regresiones de cascada y concurrencia fallaron sobre el código original.
Fase 1: npm test — 117 pasan, 0 fallan (cascadas en memoria/D1, concurrencia 20 veces, binding, reloj y SQL de operador). No se probó Cloudflare real.

3.1: el ejemplo que pide emitir después de corregir tres días contradice el límite explícito de 24 h. Se bloquea a tres días y se prueba la monotonía para un desfase menor.
3.2: SPVI ya pasa y persiste txNumber en este commit base. Se conserva esa ruta; lo nuevo es reservar antes de compartir. Liberar un pago no libera el nonce ni invalida criptográficamente una licencia entregada.

Room: `7.json` preparado a partir de las entidades, con el algoritmo SchemaIdentityKey de Room (reproduce v1–v5; el hash de v6 ya versionado no coincide con su propio JSON). DDL de v7 comprobado en SQLite, **no** es una exportación ejecutada por KSP. Regenerar/comparar en la compilación Android; no modificar los esquemas históricos. No hay regla fiable para distinguir pruebas de recuperaciones gratuitas antiguas: quedan como EMISION.

Resultado final, Node 22.22.3: `services/redeem`: 119/119; `tools/directorio`: 11/11 (incluye CLI, root temporal cifrada, rechazo de passphrase incorrecta y vectores). No se utilizaron claves de producción.

## Qué NO se verificó

Android sin JDK 17, Gradle ni SDK: no se afirma compilación, pruebas JVM, SQLCipher/Keystore, borrado del portapapeles ni recorrido en dispositivo. `ContactClipboard` no contenía temporizador; la nueva UI intenta borrar solo su propio clip. No se ejecutaron Wrangler/D1 reales, pruebas de carga, publicación en releases, cliente Shalev ni validación cruzada móvil. La guía independiente de Cloudflare no se adjuntó. Archivos Kotlin tocados, todos **no verificados**:
- `app/src/main/java/dev/gl/license/core/AppError.kt`
- `app/src/main/java/dev/gl/license/data/local/BatchInventory.kt`
- `app/src/main/java/dev/gl/license/data/local/Db.kt`
- `app/src/main/java/dev/gl/license/data/local/LicenseRepositoryImpl.kt`
- `app/src/main/java/dev/gl/license/data/local/Migrations.kt`
- `app/src/main/java/dev/gl/license/data/local/RegistroRepositoryImpl.kt`
- `app/src/main/java/dev/gl/license/data/mapper/Mappers.kt`
- `app/src/main/java/dev/gl/license/domain/model/Models.kt`
- `app/src/main/java/dev/gl/license/domain/model/SpviResponseMessage.kt`
- `app/src/main/java/dev/gl/license/domain/repository/Repositories.kt`
- `app/src/main/java/dev/gl/license/domain/usecase/CrearLoteCanjeUseCase.kt`
- `app/src/main/java/dev/gl/license/domain/usecase/EmitirSpvi2UseCase.kt`
- `app/src/main/java/dev/gl/license/domain/usecase/EvaluarSolicitudHistorialUseCase.kt`
- `app/src/main/java/dev/gl/license/domain/usecase/GenerarListaRevocadasUseCase.kt`
- `app/src/main/java/dev/gl/license/domain/usecase/UseCases.kt`
- `app/src/main/java/dev/gl/license/presentation/MainActivity.kt`
- `app/src/main/java/dev/gl/license/presentation/generator/GeneratorScreen.kt`
- `app/src/main/java/dev/gl/license/presentation/generator/GeneratorViewModel.kt`
- `app/src/main/java/dev/gl/license/presentation/lotes/LotesScreen.kt`
- `app/src/main/java/dev/gl/license/presentation/lotes/LotesViewModel.kt`
- `app/src/main/java/dev/gl/license/presentation/navigation/Routes.kt`
- `app/src/main/java/dev/gl/license/presentation/registry/DetailScreen.kt`
- `app/src/main/java/dev/gl/license/presentation/registry/RegistryScreen.kt`
- `app/src/main/java/dev/gl/license/presentation/registry/RegistryViewModel.kt`
- `app/src/main/java/dev/gl/license/presentation/trust/TrustScreen.kt`
- `app/src/main/java/dev/gl/license/security/PublicKeyBundle.kt`
- `app/src/main/java/dev/gl/license/security/RegistryArchive.kt`
- `app/src/test/java/dev/gl/license/DatabaseMigrationTest.kt`
- `app/src/test/java/dev/gl/license/DirectoryEntryTest.kt`
- `app/src/test/java/dev/gl/license/DomainLayerTest.kt`
- `app/src/test/java/dev/gl/license/EvaluarSolicitudHistorialTest.kt`
- `app/src/test/java/dev/gl/license/GeneratorViewModelTest.kt`
- `app/src/test/java/dev/gl/license/InMemoryLicenseRepositoryTest.kt`
- `app/src/test/java/dev/gl/license/RegistryViewModelTest.kt`
- `app/src/test/java/dev/gl/license/RenovacionSpviTest.kt`
- `app/src/test/java/dev/gl/license/RevocadasTest.kt`
- `app/src/test/java/dev/gl/license/SpviMensajeTest.kt`

| Entrada pendiente | Responsable / siguiente paso |
|---|---|
| `RAIZ_A_SPKI`, `RAIZ_B_SPKI` | Propietario: generar offline y fijar por canal autenticado; no usar claves de prueba. |
| `<URL_PUBLICA_WORKER>`, ID de D1 | Propietario: configurar, desplegar y comprobar bindings reales. |
| `<REPO_SHALEV_RELEASES>` | Confirmar destino de `revocaciones` antes de publicar bootstrap/directorio/lista. |
| IDs comerciales, secundarias | Confirmar DSHALEV/SHALEV-ANDROID/SHALEV-IOS. Mantener 0 hasta validar clientes. |

Se compararon en SQLite columnas, afinidad, nulabilidad, defaults y PK de las seis tablas: esquema v6 + DDL de `MIGRATION_6_7` frente a `7.json`. Coinciden. No sustituye compilar Room ni ejecutar la migración en Android. Búsqueda de marcadores PEM: la única coincidencia es la aserción preventiva de `compatibility.test.js`, no material de clave.
