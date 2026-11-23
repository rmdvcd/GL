# Informe de la verificación en el PC — GL (2026-11-23)

Repositorio: `rmdvcd/GL` (`git remote -v` → `https://github.com/rmdvcd/GL.git`).
Rama: `pc/2026-11-22-verificacion`, creada desde `main` @ `ff3a49b`.
Este informe cubre **solo la PARTE G** del encargo; la PARTE S corresponde al
repositorio `rmdvcd/Shalev` y no se ejecutó aquí.

## Entorno

- Windows; Gradle **8.11.1** (`~/.gradle/wrapper/dists/gradle-8.11.1-bin/…`), JDK 17
  (`~/.gradle/jdks/jdk17`), `ANDROID_HOME` = `%LOCALAPPDATA%\Android\Sdk`.
- Node 26.12.1, Wrangler 4.166.0, dispositivo `4bc9f8f3` conectado (no se instaló nada).

## Commits creados en la rama (sin `push`)

| Commit | Contenido | Motivo |
|---|---|---|
| `pc.1` | `tools/directorio`: comprobar el permiso `0600` solo en POSIX | La aserción comparaba modo POSIX (384) con el que devuelve Windows (438) y hacía fallar una prueba de Node válida en Linux/macOS. |
| `pc.2` | `DeclaredTransferRoomTest`: alinear con la normalización documentada de `tx` | La expectativa suponía que `TX-100` y `TX100` eran el mismo número; el código normaliza minúsculas y espacios internos, no guiones. Se corrigió la prueba, no el código. |
| `pc.3` | `DatabaseMigrationTest`: migración 6→7 con `txNumber` duplicado | Faltaba cubrir el punto delicado: dos licencias con el mismo `txNumber` y una sola reserva (`INSERT OR IGNORE`), sin índice único sobre `licenses.txNumber`. |
| `pc.4` | `services/redeem/.gitignore`: ignorar `.dev.vars` | El archivo local de `wrangler dev` contiene `CODE_PEPPER` y claves; no debe poder versionarse por descuido. |

## Comandos ejecutados y resultado

| Comando | Resultado |
|---|---|
| `gradle :app:assembleDebug --console=plain` | `BUILD SUCCESSFUL in 2m 27s` (41 tareas). Solo avisos de deprecación previos (`Icons.Filled.List`, `LocalLifecycleOwner`). |
| `gradle :app:testDebugUnitTest --rerun --no-build-cache` | **223 tests, 38 suites, 0 fallos, 0 errores, 0 omitidos**; `BUILD SUCCESSFUL in 18s`; resultados con marca `2026-11-23T06:08`. |
| `gradle :app:kspDebugKotlin --rerun-tasks --no-build-cache` (solo para comparar) | Regeneró `7.json`; comparación byte a byte con el versionado: **idéntico** salvo fin de línea (KSP escribe LF, el repo CRLF). Se restauró el archivo. |
| `cd services/redeem && npm test` | **128/128**, 0 fallos, 1,7 s, `EXIT=0`. |
| `cd tools/directorio && node --test` | **11/11**, 0 fallos, 2,6 s, `EXIT=0` (tras `pc.1`). |
| `wrangler d1 execute gl-redeem-local --local --file=migrations/0001_init.sql` + import | 2 filas `SHALEV-ANDROID/UNUSED` en la D1 **local**. Nunca `--remote`. |
| `wrangler dev --local` (127.0.0.1:8799) + `scripts/smoke.mjs` | `/health` y `/v1/time` OK; canje real por HTTP con clave de dispositivo efímera. |
| Aislamiento multi-app | Código de `SHALEV-ANDROID` con `APP_ID=DSHALEV` → **404 `CODE_UNAVAILABLE`**; con `SHALEV-ANDROID` → 200; reintento → **409 `CODE_ALREADY_REDEEMED`**. |

Las claves, códigos y lotes usados en la prueba local son **sintéticos** y viven
en `build-logs/` (ignorado por git). No se usó ningún secreto real.

## Pruebas que cubren el encargo G-4 (ya existentes, ahora ejecutadas)

- `RequestFreshnessTest`: 7 d − 1 s y 7 d exactos aceptados; 7 d + 1 s y
  +2 h + 1 s rechazados; `Instant.MAX/MIN` fail-closed.
- `NonceRetentionTest`: nonce consumido sigue rechazando dentro de la ventana y
  a 14 días; no hay limpieza por antigüedad en el código.
- `DeclaredTransferTest`, `DeclaredTransferRoomTest`, `TransactionMatchingTest`:
  `tx` presente/ausente/inválida/duplicada.
- `OperatorMessagesTest`: todos los textos de canje, SPVI2 y v1 usan solo el
  alfabeto GSM-7 sin tabla extendida (el sobre JSON v1 queda excluido por contrato).
- `RenovacionSpviTest.monotonaConLimiteDeUnDia`: reloj adelantado 1 h → `issuedAt`
  = anterior + 1 s; 3 d → `EmisorDesajustado`.
- `DatabaseMigrationTest` (10/10, incluida la nueva de duplicados).

## Room v7

`Db.version = 7`; `app/schemas/…/7.json` presente y coincide con lo que genera
KSP. Los esquemas 1–6 no se tocaron.

**Discrepancia pendiente (decisión del propietario):** el hash del esquema **6**
versionado no coincide con su propio JSON. No se «arregló» ni se regeneró, porque
cambiar un esquema histórico afecta a instalaciones existentes.

## Higiene del repositorio (G1)

`git ls-files` no contiene `wrangler.production.jsonc` ni `.wrangler/`; solo la
plantilla `services/redeem/examples/wrangler.production.example.jsonc`. Los
identificadores de cuenta/D1 siguen en el **historial**, que no se purgó.

## No verificado

- **iOS / Shalev**: fuera de este repositorio. Nada de la PARTE S se ejecutó.
- **Prueba en el teléfono emisor**: hay dispositivo conectado, pero no se instaló
  ni se tecleó nada sin autorización. El guion está en `docs/PRUEBA_MANUAL_GL.md`.
- **Worker/D1 reales**: solo local. No hay despliegue ni comprobación en Cloudflare.
- **Raíces del directorio**: no se generaron claves. `tools/directorio` está
  probado con el vector de prueba, no con raíces reales.
- **Clientes Shalev** (Android/iOS/DShalev) contra este emisor: no probado.
- La compilación de esta pasada salió de caché (`UP-TO-DATE`); el `assembleDebug`
  verde corresponde al mismo árbol, sin cambios de código posteriores.

## Decisiones pendientes del propietario

1. Purga del historial de Git (IDs de Cloudflare retirados en G1): comando
   preparado en `docs/PASOS_DEL_PROPIETARIO.md`, **no ejecutado**.
2. Hash del esquema 6: dejar como está o regenerar (afecta a instalaciones).
3. Autorizar `SHALEV-ANDROID` y `SHALEV-IOS` en el Worker; hoy
   `ALLOWED_APPS` de producción no se ha tocado.
4. Generar las raíces A y B y publicar `directorio.json` en la release
   `revocaciones` (`<REPO_SHALEV_RELEASES>` sin confirmar).
