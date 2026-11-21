# Notas de implementación

Baseline Node 22.22.3: 107 pasan, 0 fallan. Las 7 regresiones de cascada y concurrencia fallaron sobre el código original.
Fase 1: npm test — 117 pasan, 0 fallan (cascadas en memoria/D1, concurrencia 20 veces, binding, reloj y SQL de operador). No se probó Cloudflare real.

3.1: el ejemplo que pide emitir después de corregir tres días contradice el límite explícito de 24 h. Se bloquea a tres días y se prueba la monotonía para un desfase menor.
3.2: SPVI ya pasa y persiste txNumber en este commit base. Se conserva esa ruta; lo nuevo es reservar antes de compartir. Liberar un pago no libera el nonce ni invalida criptográficamente una licencia entregada.

Room: `7.json` preparado a partir de las entidades, con el algoritmo SchemaIdentityKey de Room (reproduce v1–v5; el hash de v6 ya versionado no coincide con su propio JSON). DDL de v7 comprobado en SQLite, **no** es una exportación ejecutada por KSP. Regenerar/comparar en la compilación Android; no modificar los esquemas históricos. No hay regla fiable para distinguir pruebas de recuperaciones gratuitas antiguas: quedan como EMISION.

Resultado final, Node 22.22.3: `services/redeem`: 119/119; `tools/directorio`: 11/11 (incluye CLI, root temporal cifrada, rechazo de passphrase incorrecta y vectores). No se utilizaron claves de producción.

## Qué NO se verificó

Android sin JDK 17, Gradle ni SDK: no se afirma compilación, pruebas JVM, SQLCipher/Keystore, borrado del portapapeles ni recorrido en dispositivo. `ContactClipboard` no contenía temporizador; la nueva UI intenta borrar solo su propio clip. No se ejecutaron Wrangler/D1 reales, pruebas de carga, publicación en releases, cliente Shalev ni validación cruzada móvil. La [guía de despliegue](GUIA_CLOUDFLARE_WORKERS_D1.md) se incorporó después; no implica que el servicio esté desplegado. Archivos Kotlin tocados, todos **no verificados**:
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

### G1 — configuración local de Cloudflare

`git ls-files` confirmó que se habían publicado la configuración de producción y la caché de cuenta. Se retiraron **del índice** (no del disco); usa `services/redeem/examples/wrangler.production.example.jsonc` como plantilla. Los IDs de cuenta/D1 y los metadatos de la cuenta siguen en el historial. Una purga con `git filter-repo` requiere decisión y coordinación del propietario; no se hizo. La prueba de higiene revisa el índice, no la configuración local ignorada.

### G2 — mensajes: excepción de contrato

Lotes ya copiaba el código y anotaba entregas, pero no construía un mensaje para el cliente. La acción nueva obtiene tipo y código de la **entrada guardada**, no del selector de creación; abrir una app externa no prueba la entrega. La nota y el cambio LIBRE → ENTREGADO siguen siendo explícitos.

`SpviResponseMessage` y el mensaje de canje se comprueban completos en la prueba JVM. El legado `CryptoGatewayImpl.cifrarLicencia` devuelve un sobre **JSON literal**, con `{}` incompatibles con la lista permitida. Se aplica GSM-7 al texto de acompañamiento v1, no al sobre; recodificarlo rompería el contrato. Solo el mensaje de **canje** cabe en una parte; SPVI2 puede requerir varias.

### G3 — entrega directa sin permisos nuevos

Generator ya abría el canal de la solicitud. Ahora usa el mismo normalizador que el detalle: móvil local `5xxxxxxx` → `+535xxxxxxx`, conserva el móvil cubano internacional y los internacionales explícitos no cubanos. Si el número es ambiguo o no hay manejador, el cuerpo queda disponible para copiar o compartir. La entrega abre un borrador; GL no envía en segundo plano.

El Registro guarda el código SPVI2 y permite reconstruir su mensaje sin emitir otra licencia. Para v1 solo guarda los datos de licencia y un código de referencia: no guarda el sobre cifrado de entrega. Se ofrece el envío v1 completo en Generator, **no** un falso reenvío desde el detalle ni un sobre regenerado.

### G4 — política de frescura y decisión del propietario

Los validadores v1 y SPVIR1 ya delegaban en `RequestFreshness`. Ahora un único intervalo inclusivo alimenta la validación y el aviso de error: se aceptan desde 72 horas antes hasta 2 horas después de la hora del emisor. No es la tolerancia del Worker de canje. Una prueba JVM fija los extremos y los instantes un nanosegundo fuera del intervalo.

Ampliar la antigüedad, por ejemplo a siete días, permitiría presentar más tarde una **solicitud aún no utilizada**. No reabre por sí mismo la repetición de una solicitud ya emitida: la reserva atómica consume su nonce al emitir, siempre que se conserve ese registro. Requiere decisión del propietario y actualizar «Vale 72 horas» en los clientes Shalev antes del cambio. Esta entrega **no amplía** la ventana.
