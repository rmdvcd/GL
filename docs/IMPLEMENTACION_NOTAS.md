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

> **Superado por G6 (2026-11-20):** la ventana ya es de 7 días. Lo que sigue describe el estado de aquel PR (72 h) y se conserva como historial.

Los validadores v1 y SPVIR1 ya delegaban en `RequestFreshness`. Ahora un único intervalo inclusivo alimenta la validación y el aviso de error: se aceptan desde 72 horas antes hasta 2 horas después de la hora del emisor. No es la tolerancia del Worker de canje. Una prueba JVM fija los extremos y los instantes un nanosegundo fuera del intervalo.

Ampliar la antigüedad, por ejemplo a siete días, permitiría presentar más tarde una **solicitud aún no utilizada**. No reabre por sí mismo la repetición de una solicitud ya emitida: la reserva atómica consume su nonce al emitir, siempre que se conserve ese registro. Requiere decisión del propietario y actualizar «Vale 72 horas» en los clientes Shalev antes del cambio. Esta entrega **no amplía** la ventana.

### G5 — cierre de la entrega del emisor (sin producción)

| Tarea | Estado | Evidencia y límites |
|---|---|---|
| G1 | Hecho-verificado | Ambas rutas fuera de `git ls-files` y cubiertas por `git check-ignore`. `cd services/redeem && npm test`: **121/121**; incluye las dos comprobaciones nuevas de higiene. Historial sin purgar. |
| G2 | Hecho-no-ejecutado (Android/JVM) | Mensaje para código libre, cuatro acciones, confirmación manual con nota y prueba GSM-7/longitud/formato. Omitida únicamente la exigencia GSM-7 sobre el JSON literal v1 (contrato). |
| G3 | Hecho-no-ejecutado (Android/JVM) | Direcciones y construcción de URLs cubiertas en fuente de prueba; envío y alternativas en Generator y detalle SPVI2. Reenvío v1 desde Registro omitido: no conserva el sobre. |
| G4 | Hecho-no-ejecutado (JVM) | Misma política para ambos validadores, prueba de límites añadida; no se ha cambiado a siete días. |

`cd tools/directorio && node --test test/*.test.mjs`: **11/11**. Resultados de Node locales, no pruebas de Android ni de la infraestructura desplegada. **Kotlin: no ejecutado** (sin compilación, suites JVM, instrumentación, apertura de apps externas ni prueba en el emisor).

**Archivos Kotlin tocados sin verificar por compilación** (rutas relativas a `app/src/main/java/dev/gl/license/`):

- `core/AppError.kt`
- `domain/model/DeliveryAddress.kt`
- `domain/model/LicenseMessage.kt`
- `domain/model/OperatorMessages.kt`
- `domain/model/SpviResponseMessage.kt`
- `domain/usecase/RequestFreshness.kt`
- `presentation/components/MessageActions.kt`
- `presentation/generator/GeneratorScreen.kt`
- `presentation/generator/GeneratorViewModel.kt`
- `presentation/lotes/LotesScreen.kt`
- `presentation/lotes/LotesViewModel.kt`
- `presentation/registry/DetailScreen.kt`

Pruebas JVM tocadas, también sin ejecutar (`app/src/test/java/dev/gl/license/`):

- `DeliveryAddressTest.kt`
- `LicenseMessageTest.kt`
- `OperatorMessagesTest.kt`
- `RequestFreshnessTest.kt`

**Decisiones pendientes del propietario:**

1. Cuándo autorizar `SHALEV-ANDROID` y `SHALEV-IOS` en el Worker (independientemente de que GL ya permita elegirlos para lotes).
2. Si ampliar la ventana de frescura y coordinar el aviso en Shalev; los límites actuales siguen siendo 72 h / 2 h.
3. Si incorporar un campo de nº de transferencia a la **solicitud**. La nota local de entrega no introduce ese campo ni modifica SPVIR1.
4. Si purgar los metadatos de Cloudflare del historial, coordinando clones y ramas. No se reescribió Git ni se ejecutó Wrangler.

### Anexo G6–G9 (encargo complementario del 2026-11-20)

Estas notas **anulan** lo que el apartado G4 decía sobre «no ampliar la ventana» y las decisiones pendientes 2 y 3 del cierre anterior: el propietario pidió expresamente 7 días y el campo opcional `tx`.

- **G6:** `RequestFreshness.maxAge` pasa de 72 h a 7 días (168 h) y `maxFutureSkew` sigue en 2 h. Es la única constante: la usan `FieldValidator` (v1), `EmitirSpvi2UseCase` (SPVIR1 v=2) y el texto de `AppError.RequestExpired`.
- **Retención de nonces:** no hay ninguna limpieza por antigüedad de `consumed_nonces` ni de `tx_reservations`. No debe añadirse con menos de 14 días. La única liberación existente es manual y por `licenseId` (`releaseReservation`). Una prueba Room comprueba que un nonce consumido sigue rechazando a 7 días menos un segundo y a 14 días.
- **G7:** `SolicitudSpviV2.tx` es opcional (`String?`, `@SerialName("tx")`). El decodificador tolera claves desconocidas (ya lo hacía: `ignoreUnknownKeys = true`) y clientes antiguos sin el campo. Al descifrar, `Spvi23.declaredTx` recorta espacios y exige `^[A-Za-z0-9-]{4,30}$`; si no cumple, el campo se ignora y **nunca** se rechaza la solicitud. Generator precarga el campo editable, muestra «Transferencia declarada: X» y consulta la reserva (`transactionOwner`) para indicar libre o ya usada con el ID de la licencia. La emisión sigue usando el número que confirma el operador y la misma reserva atómica (`txKey`). Sin migración de Room.

## Cierre G6–G9 (encargo complementario, 2026-11-20)

### Estado por tarea

| Tarea | Estado | Evidencia y límites |
|---|---|---|
| **G6** ventana de 7 días | Hecho-verificado (Node) / no ejecutado (Kotlin) | `RequestFreshness.maxAge` = 168 h y `maxFutureSkew` = 2 h, una sola constante para v1 y SPVIR1. Pruebas de frontera escritas: 7 d − 1 s aceptada, 7 d + 1 s rechazada, +2 h + 1 s rechazada. **No ejecutadas** aquí. |
| **G6** retención de nonces | Hecho-verificado por inspección + prueba escrita | No existe ninguna limpieza de `consumed_nonces` ni `tx_reservations`: la única escritura negativa es `release(licenseId)` (manual, por licencia). Se documenta el mínimo de 14 días. Prueba Room escrita: nonce consumido rechazado a 7 d − 1 s y a 14 d. **No ejecutada**. |
| **G7** campo opcional `tx` | Hecho-verificado (Node inexistente aquí) / no ejecutado (Kotlin) | `SolicitudSpviV2.tx` opcional; decodificador tolera claves desconocidas y clientes sin `tx`; `Spvi23.declaredTx` recorta y exige `^[A-Za-z0-9-]{4,30}$`, ignorando el campo si no cumple. Generator precarga, avisa «Transferencia declarada: X» y consulta `transactionOwner` para Libre / Ya usada con el ID. Sin migración de Room. |
| **G7** reserva atómica | Hecho-no-ejecutado (Kotlin) | Se reutiliza `tx_reservations` con `txKey` (recorte, minúsculas, sin espacios internos). Pruebas escritas de duplicado bloqueado y de corrección por el operador. **No ejecutadas**. |
| **G8** varias apps (servicio) | **Hecho-verificado** | `npm test`: **128/128** (7 nuevas). Aislamiento entre `DSHALEV`, `SHALEV-ANDROID` y `SHALEV-IOS`, `CODE_UNAVAILABLE` cruzado, misma clave con licencias en apps distintas sin rollover ni cascada cruzada, `MAX_SECUNDARIAS=0` en las tres y lectura de `ALLOWED_APPS` desde el entorno. |
| **G8** selector de Lotes | Hecho-no-ejecutado (Kotlin) | `RedeemApps.allowed` alimenta el selector y el aviso «Este código solo sirve en la app elegida: X»; `create()` rechaza una app fuera de la lista antes de firmar. Pruebas escritas, **no ejecutadas**. |
| **G8** guía del Worker | Hecho (solo documentación) | Nueva sección «4.1 Autorizar un appId nuevo». No se ejecutó Wrangler ni se tocó D1. |
| **G9** documentación | Hecho | Este cierre, `README.md`, `SECURITY.md`, `docs/GL_ESPECIFICACION.md` y la guía. |

### Contradicciones con el encargo, resueltas por el código (gana el código)

1. **`RequestPayload` / `maxAge` / `maxFutureSkew` no existen con esos nombres.** En este repositorio la solicitud SPVIR1 es `SolicitudSpviV2` (`domain/model/SpviV2.kt`) y la ventana es `RequestFreshness.maxAge` / `maxFutureSkew`. Se implementó sobre esos nombres.
2. **El servicio de canje no valida antigüedad de solicitudes.** Su ventana es `MAX_CLOCK_SKEW_MS` (5 min) sobre el sobre firmado del cliente; los 7 días son del emisor. No se modificó el Worker.
3. **`consumed_nonces` y `tx_reservations` son tablas de Room en el APK de GL**, no del Worker, y no tienen limpieza por antigüedad. No se añadió ninguna.
4. **El campo `tx` va en el JSON cifrado del cliente Shalev.** GL puede leerlo y precargarlo, pero un cliente aún no editado nunca lo enviará: no rompe nada, simplemente llega `null`. Publicar el texto «Vale 7 días» y enviar `tx` exige cambios en el repositorio Shalev, fuera de este encargo.
5. **`CrearLoteCanjeUseCase` solo valida el formato del appId** (`^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$`). La restricción a las tres apps de Shalev vive en la pantalla (`RedeemApps`), no en el acuñador.

### Archivos Kotlin tocados y **no verificados por compilación** (G6–G8)

Producción (`app/src/main/java/dev/gl/license/`):
- `core/AppError.kt` — mensaje de `RequestExpired` derivado de las constantes.
- `domain/usecase/RequestFreshness.kt` — 7 días / 2 h, inclusivo, fail-closed.
- `domain/model/SpviV2.kt` — campo `tx`.
- `security/Spvi23.kt` — `declaredTx`, normalización tolerante.
- `domain/repository/Repositories.kt` — `transactionOwner` con implementación por defecto.
- `domain/model/RedeemApps.kt` (nuevo) — apps permitidas y aviso.
- `data/local/Db.kt` — consulta `ownerByTxNumber`.
- `data/local/LicenseRepositoryImpl.kt` — `transactionOwner`.
- `presentation/generator/GeneratorViewModel.kt` — precarga, aviso, Libre/Ya usada, nuevo parámetro `licencias`.
- `presentation/generator/GeneratorScreen.kt` — textos y estados de transferencia.
- `presentation/lotes/LotesViewModel.kt` — `appOptions`, `appNotice`, rechazo previo.
- `presentation/lotes/LotesScreen.kt` — selector desde `RedeemApps` + aviso.
- `app/src/main/res/values/strings.xml` — tres cadenas nuevas.

Pruebas JVM escritas y **no ejecutadas** (`app/src/test/java/dev/gl/license/`):
`RequestFreshnessTest.kt`, `NonceRetentionTest.kt`, `FieldValidatorTest.kt`, `RenovacionSpviTest.kt`, `DeclaredTransferTest.kt`, `DeclaredTransferRoomTest.kt`, `GeneratorViewModelTest.kt`, `TransactionMatchingTest.kt`, `DomainLayerTest.kt`, `RedeemAppsTest.kt`, `LotesViewModelTest.kt`.

### Pasos del propietario (en este orden)

1. Compilar y publicar el **APK de GL** (7 días, precarga de `tx`, selector de apps) y probarlo en el teléfono del emisor.
2. Publicar los clientes **Shalev** que digan «Vale 7 días» y envíen `tx`. Sin este paso, la ventana ampliada no aporta nada a los usuarios, pero tampoco rompe a los clientes viejos.
3. Autorizar `SHALEV-ANDROID` y `SHALEV-IOS` en el Worker (`vars.ALLOWED_APPS` → `wrangler deploy` → `/health` → lote por app → importar → humo con `APP_ID=...`), según el apartado 4.1 de la guía.
4. Sigue pendiente la decisión sobre purgar del historial de Git los identificadores de Cloudflare retirados en G1.

### Qué NO se verificó

- **Kotlin no se compiló ni se ejecutó**: ni `:app:assembleDebug`, ni `:app:testDebugUnitTest`, ni pruebas instrumentadas. Las afirmaciones sobre Lotes, Generator y SPVIR1 se basan en revisión de código y en las pruebas escritas, no en resultados.
- No se probó en dispositivo la precarga de `tx`, el aviso Libre/Ya usada, el selector de apps ni la ventana de 7 días de extremo a extremo con un cliente Shalev real.
- No se ejecutó Wrangler, no se desplegó, no se tocó D1, `ALLOWED_APPS` real, claves, peppers ni lotes reales.
- `services/redeem` (**128/128**) y `tools/directorio` (**11/11**) sí se ejecutaron aquí con Node 22; no cubren Android ni el entorno desplegado.
