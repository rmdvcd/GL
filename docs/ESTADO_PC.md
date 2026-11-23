# ESTADO_PC — verificación en el PC del propietario (GL)

Rama: `pc/2026-11-22-verificacion` (desde `main` @ `ff3a49b`).
Fecha de la sesión: 2026-11-22/23. Entorno: Windows, JDK 17, Gradle 8.11.1, Node 26.12.1, Wrangler 4.166.0.

Regla de lectura: **verificado** = comando ejecutado aquí con su salida leída. **No ejecutado** = solo inspección de código.

## Tabla tarea → estado → evidencia

| Tarea | Estado | Evidencia | Notas |
|---|---|---|---|
| Fase 0: estado del repo | Verificado | `git remote -v` → `rmdvcd/GL`; `git status` limpio; `main` = `ff3a49b` | Árbol limpio antes de ramificar. |
| G-1 compilación | Verificado | `:app:assembleDebug` → `BUILD SUCCESSFUL in 2m 27s` (41 tareas) | Solo 3 avisos de deprecación previos (`Icons.Filled.List`, `LocalLifecycleOwner`). |
| G-1 pruebas JVM | Verificado | `:app:testDebugUnitTest --rerun --no-build-cache` (2026-11-23 06:08) → 38 suites, **223 tests, 0 fallos, 0 errores, 0 omitidos** | Incluye las suites que Arena no pudo ejecutar. Reejecutado de verdad: la primera pasada salió `UP-TO-DATE` y la segunda `FROM-CACHE`; se relanzó con `--rerun --no-build-cache`. |
| G-1 corregir Kotlin de Arena | Verificado | Compila y pasa sin tocar producción; 2 correcciones en tests (pc.1–pc.3) | No hubo errores de compilación en producción. |
| G-2 `7.json` manual vs KSP | Verificado | Borrado `7.json`, `:app:kspDebugKotlin --rerun-tasks --no-build-cache` → regenerado; comparación byte a byte: **idéntico** salvo fin de línea | El esquema manual era correcto. KSP escribe LF y el repo tiene CRLF: regenerar deja un `M` espurio por fin de línea. Restaurado. |
| G-2 migración 6→7 con `txNumber` duplicado | Verificado | Nueva prueba `v7SiembraReservasDuplicadasSinPerderFilas` (pc.3): 2 licencias, 1 reserva, gana `w`; `PRAGMA index_list/index_info` sin índice sobre `txNumber` | `INSERT OR IGNORE` confirmado. `DatabaseMigrationTest` 10/10. |
| G-2 hash del esquema 6 | **Discrepancia persiste** | No se tocó `6.json` | Decisión del propietario; no se «arregla». |
| G-3 `services/redeem` | Verificado | `npm test` → **128/128**, 0 fallos, 3,4 s | Node 26.12.1. |
| G-3 `tools/directorio` | Verificado (con arreglo) | `node --test` → **11/11** tras pc.1 | Fallo inicial: aserción de permiso POSIX en Windows (438≠384). El CLI ya pide `0o600`; solo la aserción era POSIX-only. |
| G-3 D1 local + flujo completo | Verificado | `wrangler d1 execute --local` (migración + import) → 2 filas `SHALEV-ANDROID/UNUSED`; `wrangler dev --local` en 127.0.0.1:8799; `/health`, `/v1/time` OK; canje real por HTTP | Claves y códigos **sintéticos** en `build-logs/redeem-smoke/`. Nunca `--remote`. |
| G-3 aislamiento multi-app | Verificado | Código de `SHALEV-ANDROID` con `appId DSHALEV` → HTTP 404 `CODE_UNAVAILABLE`; con `SHALEV-ANDROID` → 200; reintento → 409 `CODE_ALREADY_REDEEMED` | `ALLOWED_APPS=DSHALEV,SHALEV-ANDROID` solo en `.dev.vars` local. |
| G-4 fronteras de ventana | Verificado | `RequestFreshnessTest`: 7 d−1 s y 7 d exactos aceptados; 7 d+1 s y +2 h+1 s rechazados; `Instant.MAX/MIN` fail-closed | — |
| G-4 nonce y retención | Verificado | `NonceRetentionTest` pasa; no hay limpieza por antigüedad en el código | Mínimo de 14 d documentado. |
| G-4 `tx` presente/ausente/inválida/duplicada | Verificado | `DeclaredTransferTest`, `DeclaredTransferRoomTest`, `TransactionMatchingTest` pasan | Corregida expectativa errónea en `DeclaredTransferRoomTest` (pc.2): `TX100` ≠ `TX-100`. |
| G-4 textos GSM-7 | Verificado | `OperatorMessagesTest` recorre todos los `TipoLicencia` y valida canje, SPVI2 y v1 | Alfabeto sin tabla extendida. El sobre JSON v1 queda excluido por contrato. |
| G-4 `emitidaEn` monótona con reloj adelantado | Verificado | `RenovacionSpviTest.monotonaConLimiteDeUnDia`: adelanto 1 h → `issuedAt` = anterior+1 s; 3 d → `EmisorDesajustado` | — |
| G-5 higiene G1 | Verificado | `git ls-files` no contiene `wrangler.production.jsonc` ni `.wrangler/`; solo `services/redeem/examples/*` | No se purgó historial. |
| G-6 prueba manual en dispositivo | No ejecutado | Guion en `docs/PRUEBA_MANUAL_GL.md` | `adb devices` lista `4bc9f8f3`; **no** se instaló nada en esta sesión sin permiso. |

## Comprobación de elementos esperados (Fase 0)

| Elemento | Presencia | Prueba |
|---|---|---|
| `RequestFreshness` 7 días | Sí (`maxAge = Duration.ofDays(7)`) | `RequestFreshnessTest`, `NonceRetentionTest` |
| `tx` opcional en `SolicitudSpviV2` + precarga | Sí (`SpviV2.kt`, `GeneratorViewModel.declaredTx`) | `DeclaredTransferTest` |
| `ALLOWED_APPS` multi-app + selector de Lotes | Sí (`RedeemApps`, `LotesViewModel.appOptions`) | `RedeemAppsTest`, `LotesViewModelTest`, Node G8 |
| `tools/directorio` (CLI + vector) | Sí (`cli.js`, `docs/vectores/directorio-v1.json`) | 11/11 |
| `GenerarListaRevocadasUseCase` | Sí | `RevocadasTest` |
| Pantalla Lotes | Sí (`LotesScreen`, `LotesViewModel`) | `LotesViewModelTest` |
| Room v7 | Sí (`Db.version = 7`, `7.json`) | `DatabaseMigrationTest` |
| `cascade.js`, `BindingRateLimiter`, `/v1/time`, `CLOCK_SKEW` | Sí | Node 128/128 |
| Prueba de mensajes GSM-7 | Sí (`OperatorMessagesTest`) | Pasa |
| `.wrangler/` y `wrangler.production.jsonc` fuera del índice | Sí | `git ls-files` |

## Commits creados

| Commit | Contenido |
|---|---|
| `pc.1` | `tools/directorio`: comprobar el permiso 0600 solo en POSIX |
| `pc.2` | `DeclaredTransferRoomTest`: alinear con la normalización documentada de `tx` |
| `pc.3` | `DatabaseMigrationTest`: migración 6→7 con `txNumber` duplicado |
| `pc.4` | `services/redeem/.gitignore`: ignorar `.dev.vars` |

## Re-verificación del 2026-11-23 (segunda pasada de la misma rama)

| Comprobación | Comando | Resultado |
|---|---|---|
| Canje (Node) | `cd services/redeem && npm test` | **128/128**, 0 fallos, 1,7 s; `EXIT=0` |
| Directorio (Node) | `cd tools/directorio && node --test` | **11/11**, 0 fallos, 2,6 s; `EXIT=0` |
| Pruebas JVM | `gradle :app:testDebugUnitTest --rerun --no-build-cache` | **223 tests, 0 fallos, 0 errores, 0 omitidos**; `BUILD SUCCESSFUL in 18s`; 38 ficheros de resultados con marca `2026-11-23T06:08` |
| Compilación | Tareas `compileDebugKotlin`/`kspDebugKotlin` `UP-TO-DATE` | Sin cambios de código desde el `assembleDebug` verificado (`BUILD SUCCESSFUL in 2m 27s`); no se recompiló en esta pasada. |
| Room v7 | `Db.kt` → `version = 7`; `app/schemas/.../7.json` presente; esquemas 1–6 intactos | — |
| Higiene G1 | `git ls-files` → solo `services/redeem/examples/wrangler.production.example.jsonc` | No hay `wrangler.production.jsonc` ni `.wrangler/` en el índice. |
| Dispositivo | `adb devices` → `4bc9f8f3 device` | Presente; **no** se instaló ni se ejecutó nada (requiere autorización). |

Salidas completas en `build-logs/gl-tests.txt` (ignorado por git vía `.git/info/exclude`).

Entregables de cierre: `docs/INFORME_PC_2026-11-23.md` y `docs/PASOS_DEL_PROPIETARIO.md`.

## Pendiente / bloqueado por el propietario

- Purga del historial de Git (G1): comando preparado, **no ejecutado**.
- `RAIZ_A_SPKI`/`RAIZ_B_SPKI` reales: no se generan ni se tocan.
- `ALLOWED_APPS`/`MAX_SECUNDARIAS` en producción: no se tocan.
- Prueba en teléfono emisor: requiere autorización para instalar.
- Esquema 6 con hash incoherente: no se corrige.
