# Guía: desplegar el servicio de canje de GL en Cloudflare Workers + D1 (plan gratuito)

Servicio: `rmdvcd/GL` → carpeta `services/redeem/` (JavaScript, sin dependencias, WebCrypto).
Resultado: una URL `https://gl-redeem.<tu-subdominio>.workers.dev` que canjea códigos de 16 caracteres por licencias firmadas, con base de datos D1.

> **Alcance:** las pruebas del repositorio cubren el servicio local y D1 emulado; no acreditan un despliegue en tu cuenta. Valida primero en *staging* con claves y códigos de prueba.

---

## 0. Qué necesitas y qué cuesta

| Requisito | Detalle |
|---|---|
| Cuenta Cloudflare | Gratuita. Confirma que el servicio está disponible para tu cuenta y país antes de empezar. |
| Node.js | **22.5 o superior** (`node --version`). |
| Git y terminal | Linux/macOS, o **WSL / Git Bash** en Windows (los comandos usan sintaxis bash). |
| Repositorio | `git clone https://github.com/rmdvcd/GL` |
| Teléfono con GL | Para leer la clave pública de firma (pestaña **Claves**) en producción. |

**Cuotas del plan gratuito** (verifícalas en la documentación de Cloudflare antes de publicar; las cifras son orientativas):
- Workers: 100 000 peticiones/día y 10 ms de CPU por invocación.
- D1: 5 millones de filas leídas/día, **100 000 filas escritas/día**, 5 GB.
- Desde el 1-09-2026, **las consultas a D1 fallan** al superar el límite diario (se reinicia a las 00:00 UTC).
- Un canje escribe del orden de 5–6 filas (la fila más sus índices), así que la cuota alcanza para **unos 10 000 canjes diarios** (estimación mía, mídela en tu panel). Con el limitador nativo los intentos rechazados **no** consumen escrituras.
- El 10 ms de CPU incluye verificar una firma ECDSA y firmar la licencia; no lo he medido en Workers reales. Si ves errores de CPU, la alternativa es el plan de pago de Workers.

---

## 1. Preparar el proyecto y comprobar que todo pasa

```bash
git clone https://github.com/rmdvcd/GL
cd GL/services/redeem
node --version            # debe ser >= 22.5
npm test                  # esperado: todas pasan (119 en esta revisión)
umask 077                 # archivos nuevos solo para el operador
mkdir -p secrets          # ya está ignorada por git (secrets/ en .gitignore)
```

> Guarda el material operativo en `./secrets/`, fuera de Git. No actives trazas de shell (`set -x`) al manejar secretos.

---

## 2. Iniciar sesión en Cloudflare

```bash
export WRANGLER_SEND_METRICS=false
npx wrangler login        # abre el navegador; no compartas credenciales por chat
npx wrangler whoami       # confirma la cuenta
```

---

## 3. Crear la base de datos D1

Haz **dos** bases: una de pruebas (*staging*) y la real.

```bash
npx wrangler d1 create gl-redeem-staging
npx wrangler d1 create gl-redeem
```

Cada comando imprime un `database_id` (UUID). Anótalos.

---

## 4. Configuración del Worker

Copia `examples/wrangler.jsonc` como `wrangler.jsonc` en `services/redeem/` (está ignorado por git; no lo subas). Cambia `<ID_DE_TU_D1>` por el `database_id`.

```jsonc
{
  "name": "gl-redeem-staging",          // en producción: "gl-redeem"
  "main": "src/worker.js",
  "compatibility_date": "2026-11-01",
  "workers_dev": true,
  "observability": { "enabled": false },
  "d1_databases": [
    { "binding": "DB", "database_name": "gl-redeem-staging", "database_id": "<ID_DE_TU_D1>" }
  ],
  "vars": {
    "ALLOWED_APPS": "DSHALEV",           // varias: "DSHALEV,SHALEV-ANDROID,SHALEV-IOS"
    "MAX_SECUNDARIAS": "0",
    "SERVICE_KID": "gl-redeem-v1",
    "MAX_CLOCK_SKEW_MS": "300000"
  },
  // Limitador nativo. Mismo formato que el relay de Shalev.
  "ratelimits": [
    { "name": "RL_IP",     "namespace_id": "1002", "simple": { "limit": 120, "period": 60 } },
    { "name": "RL_DEVICE", "namespace_id": "1003", "simple": { "limit": 30,  "period": 60 } }
  ]
}
```

Notas:
- Los bindings `RL_IP` y `RL_DEVICE` son obligatorios: si faltan, el Worker no atiende las peticiones. No hay fallback a contadores D1.
- `ALLOWED_APPS` debe incluir exactamente el `appId` de cada cliente. Un lote de otra app se rechaza.
- Mantén `MAX_SECUNDARIAS` en `"0"` hasta que cada app primaria emita sub-tokens.

---

## 4.1 Autorizar un appId nuevo

`ALLOWED_APPS` es la lista de `appId` que el Worker acepta, tanto al importar un
lote como al canjear. Un lote acuñado para una app **no** se canjea en otra
(`CODE_UNAVAILABLE`), aunque el código sea válido. Para añadir una app:

1. Añade el identificador a `vars.ALLOWED_APPS` en `wrangler.jsonc` (separados por coma, sin espacios):
   ```jsonc
   "vars": { "ALLOWED_APPS": "DSHALEV,SHALEV-ANDROID,SHALEV-IOS", ... }
   ```
   Los identificadores deben cumplir `^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$` y coincidir **exactamente** con el `appId` que envía el cliente (mayúsculas incluidas). Este cambio **no** requiere tocar `MAX_SECUNDARIAS`, los secretos ni la base de datos.
2. Despliega el Worker: `npx wrangler deploy`. Es un cambio de configuración, no de esquema.
3. Comprueba el estado: `curl -fsS https://<tu-worker>/health` debe responder `ok` (o el JSON de salud que ya uses). Si el Worker no arranca, revisa que no falten `RL_IP`/`RL_DEVICE` ni secretos.
4. Acuña un lote **por app** desde GL (pantalla «Lotes» → Aplicación = la app nueva) y expórtalo. No reutilices un lote de otra app: la firma incluye `appId` y el canje compara contra el `appId` del código.
5. Impórtalo con el mismo `CODE_PEPPER` de producción:
   ```bash
   node src/cli.js sql-import --batch ./lote-shalev-android.json \
     --public-key "$GL_ISSUER_PUBLIC_KEY" --out ./import.sql
   npx wrangler d1 execute gl-redeem --remote --file=./import.sql
   ```
6. Prueba de humo con la app nueva:
   ```bash
   APP_ID=SHALEV-ANDROID node scripts/smoke.mjs "<un código del lote>"
   # esperado: OK ACTIVA <fecha> restored = false
   APP_ID=SHALEV-IOS node scripts/smoke.mjs "<el mismo código>"
   # esperado: CODE_UNAVAILABLE
   ```

Orden recomendado: primero el APK del operador (GL, para poder acuñar el lote de la app nueva), después publicar el cliente, y solo entonces autorizar el `appId` en el Worker. Así ningún cliente activo envía un `appId` que el Worker todavía rechaza.

---

## 5. Crear el esquema

```bash
npx wrangler d1 execute gl-redeem-staging --remote --file=migrations/0001_init.sql
npx wrangler d1 execute gl-redeem-staging --remote \
  --command "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name;"
```
Deben aparecer `codes`, `counters`, `redemptions`, `transfers`. Si el `PRAGMA foreign_keys = ON;` del principio diera error, elimina esa línea (D1 ya aplica claves foráneas).

---

## 6. Claves y secretos

El servicio necesita **cuatro secretos**:

| Secreto | Qué es | De dónde sale |
|---|---|---|
| `SERVICE_PRIVATE_KEY` | Clave privada que firma las licencias (PKCS8 Base64) | `gen-keys --role service` |
| `SERVICE_PUBLIC_KEY` | Su pública (SPKI Base64) | misma |
| `GL_ISSUER_PUBLIC_KEY` | Pública de firma de **GL** (la que verifica los lotes) | **Staging:** `gen-keys --role gl` (clave de prueba). **Producción:** la pestaña *Claves* de la app GL (firma, Base64) |
| `CODE_PEPPER` | Secreto de 32 bytes para el HMAC de los códigos | `openssl rand -base64 32` |

### 6.1 Staging (claves de prueba)

```bash
node src/cli.js gen-keys --role service --out ./secrets/service-staging.json
node src/cli.js gen-keys --role gl      --out ./secrets/gl-test.json
openssl rand -base64 32 > ./secrets/pepper-staging.txt

val() { node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"))[process.argv[2]])' "$1" "$2"; }

val ./secrets/service-staging.json privateKey | npx wrangler secret put SERVICE_PRIVATE_KEY
val ./secrets/service-staging.json publicKey  | npx wrangler secret put SERVICE_PUBLIC_KEY
val ./secrets/gl-test.json         publicKey  | npx wrangler secret put GL_ISSUER_PUBLIC_KEY
cat ./secrets/pepper-staging.txt              | npx wrangler secret put CODE_PEPPER
npx wrangler secret list
```

(Si `secret put` pide el valor por teclado, pégalo y pulsa Enter.)

### 6.2 Producción (claves reales)

- Solo al pasar a producción (paso 10), selecciona el **Worker y la base de producción**. Genera un nuevo par de servicio (`service-prod.json`) y un pepper independiente (`pepper-prod.txt`). No reutilices `gl-test.json`.
- `GL_ISSUER_PUBLIC_KEY` = clave de **firma** que muestra tu app GL (no la de cifrado). Pégala tal cual (Base64 SPKI) y comprueba la huella contra la pantalla.
- **Haz copia de `service-prod.json` y de `pepper-prod.txt` fuera del ordenador** (USB cifrado). Si pierdes el *pepper*, **ningún código importado volverá a funcionar**; si pierdes la clave del servicio, hay que rotar (sección 11).
- Nunca subas `secrets/` ni los pegues en chats o tickets.

---

## 7. Desplegar y comprobar

```bash
npx wrangler deploy
```
Anota la URL que imprime (`https://gl-redeem-staging.<subdominio>.workers.dev`).

```bash
read -rp 'URL HTTPS del Worker de staging: ' BASE_URL
export BASE_URL
curl -fsS "${BASE_URL%/}/health"
# {"ok":true,"service":"gl-redeem","v":1}
```
Si responde 500 en otras rutas: `npx wrangler secret list` (¿están los cuatro?) y revisa el ID de D1 y los dos bindings de limitación.

---

## 8. Acuñar e importar códigos

En producción los lotes los acuña **la app GL** (pantalla «Lotes») y exportas el JSON a tu PC por cable. En *staging* usa el CLI:

```bash
node src/cli.js mint-batch --key ./secrets/gl-test.json --app DSHALEV \
  --count 5 --type MENSUAL --out ./secrets/lote-staging.json
export GL_ISSUER_PUBLIC_KEY=$(val ./secrets/gl-test.json publicKey)
node src/cli.js verify-batch --batch ./secrets/lote-staging.json --public-key "$GL_ISSUER_PUBLIC_KEY"

export CODE_PEPPER=$(cat ./secrets/pepper-staging.txt)
node src/cli.js sql-import --batch ./secrets/lote-staging.json \
  --public-key "$GL_ISSUER_PUBLIC_KEY" --out ./secrets/import.sql
```

El CLI genera SQL compatible con D1, sin `BEGIN` ni `COMMIT`. Puedes reintentar el archivo: los códigos existentes no se sobrescriben.

```bash
npx wrangler d1 execute gl-redeem-staging --remote --file=./secrets/import.sql
npx wrangler d1 execute gl-redeem-staging --remote --command "SELECT COUNT(*) AS n FROM codes;"
rm ./secrets/import.sql
```

El lote JSON contiene **códigos en claro**: tras repartirlos, guárdalo cifrado o bórralo. El servidor solo guarda el HMAC.

---

## 9. Prueba de humo contra el Worker

El script ya está en el repositorio: `scripts/smoke.mjs`. Úsalo solo en staging: genera una clave efímera distinta en cada ejecución y sale con código 1 ante cualquier error.

```bash
# BASE_URL es la URL de staging comprobada en el paso 7.
export SERVICE_PUBLIC_KEY=$(val ./secrets/service-staging.json publicKey)
CODE=$(node -e 'console.log(JSON.parse(require("fs").readFileSync("./secrets/lote-staging.json","utf8")).entries[0].code)')
node scripts/smoke.mjs "$CODE"          # esperado: OK ACTIVA <fecha a 30 días> restored = false
node scripts/smoke.mjs "$CODE"          # otra clave de dispositivo: CODE_ALREADY_REDEEMED (correcto)
```

Pasar esta prueba valida: firma del lote, HMAC, canje, firma de licencia y verificación en cliente.

---

## 10. Pasar a producción

1. Cambia en `wrangler.jsonc`: `name` → `gl-redeem`, `database_name`/`database_id` → los de producción.
2. Repite pasos 5, 6.2 y 7 con la base y los secretos **de producción**.
3. Para el paso 8 usa el lote exportado por el teléfono, su `GL_ISSUER_PUBLIC_KEY` y el `CODE_PEPPER` de **producción**. Comprueba el nombre de la base de destino antes de `--remote`.
4. Fija en los clientes la clave del servicio **solo tras comparar la huella por un canal fiable**:
   ```bash
   node -e 'const c=require("crypto"),k=Buffer.from(process.argv[1],"base64");console.log("sha256:"+c.createHash("sha256").update(k).digest("hex").match(/.{4}/g).join(":"))' "$(val ./secrets/service-prod.json publicKey)"
   ```
   Publica esa clave en el **directorio de confianza** como `uso:"canje"` (ver [operación del directorio](OPERACION_DIRECTORIO.md)) y pon `REDEEM_BASE_URL` en los clientes.
5. Prueba una activación real con un código de ese lote en un dispositivo de pruebas **antes** de venderlo.

---

## 11. Operación diaria

| Tarea | Comando |
|---|---|
| Revocar una licencia | `node src/cli.js sql-revoke --license <licenseId> --out ./secrets/r.sql` → `npx wrangler d1 execute gl-redeem --remote --file=./secrets/r.sql` |
| Transferir a un equipo nuevo | El cliente te da su *thumbprint* (43 caracteres). `node src/cli.js sql-transfer --license <id> --to-thumbprint <thumb> --out ./secrets/t.sql` → ejecutar igual. El cliente nuevo canjea el mismo código y conserva el vencimiento restante. |
| Ver estado de un canje | `npx wrangler d1 execute gl-redeem --remote --command "SELECT license_id,status,active,expires_at FROM redemptions ORDER BY created_at DESC LIMIT 20;"` |
| Copia de seguridad | `npx wrangler d1 export gl-redeem --remote --output=./secrets/backup-$(date +%F).sql` (contiene hashes y claves públicas, sin códigos ni datos personales). Semanal. |
| Recuperación a un instante | `npx wrangler d1 time-travel info gl-redeem` (revisa la retención de tu plan) |
| Vigilar cuotas | Panel de Cloudflare → *D1* → *Metrics* (filas escritas) y *Workers* → *Metrics* (peticiones, CPU). Revisa si te acercas al 70 %. |
| Cambiar secretos | `npx wrangler secret put NOMBRE` y `npx wrangler deploy` |

**Rotar la clave del servicio:** genera un par nuevo con otro `kid` (`gl-redeem-v2`), súbelo (`SERVICE_KID` + secretos), publica la nueva clave en el directorio y mantén la anterior (`retirada`) hasta que expiren las licencias firmadas con ella. **No cambies el pepper ni reimportes los lotes con otro pepper:** producirías hashes distintos y perderías la asociación con el historial de canjes y revocaciones.

---

## 12. Desarrollo local sin Cloudflare

```bash
export GL_ISSUER_PUBLIC_KEY=$(val ./secrets/gl-test.json publicKey)
export SERVICE_PRIVATE_KEY=$(val ./secrets/service-staging.json privateKey)
export SERVICE_PUBLIC_KEY=$(val ./secrets/service-staging.json publicKey)
export CODE_PEPPER=$(cat ./secrets/pepper-staging.txt) ALLOWED_APPS=DSHALEV STORE_FILE=./secrets/dev.json
node src/cli.js import-local --batch ./secrets/lote-staging.json --store ./secrets/dev.json
node src/node-server.js          # http://localhost:8787 ; curl localhost:8787/health
```
`STORE_FILE` es solo para desarrollo (sin bloqueo). Para probar contra D1 local: `npx wrangler dev --local` y `npx wrangler d1 execute gl-redeem-staging --local --file=migrations/0001_init.sql`.

---

## 13. Problemas frecuentes

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| `cannot start a transaction within a transaction` | El SQL trae `BEGIN`/`COMMIT` | Regenerar el archivo con el CLI actual (paso 8) |
| 500 `SERVER_ERROR` en `/v1/redeem` | Falta algún secreto o las claves no forman par | `npx wrangler secret list`; recrea `SERVICE_*` desde el mismo `service.json` |
| `Authentication error` de wrangler | Sesión caducada | `npx wrangler login` |
| `CLOCK_SKEW` | Reloj del dispositivo a más de ±5 min | Consultar `error.serverTime` o `GET /v1/time` y activar la hora automática |
| `RATE_LIMITED` (429) | Demasiados intentos | Esperar 1 min; ajustar límites si es legítimo |
| `CODE_UNAVAILABLE` | Código no importado, de otra `appId`, revocado, o con transferencia pendiente a otro equipo | Verificar con `SELECT` en `codes`; revisar `ALLOWED_APPS` |
| `CODE_ALREADY_REDEEMED` | Ya canjeado en otro equipo | Procedimiento de transferencia (sección 11) |
| Errores de D1 por límite diario | Cuota gratuita agotada | Esperar a las 00:00 UTC o pasar a plan de pago; revisar que el limitador nativo esté activo |
| `CODE_EXPIRED` | Pasó `redeemBefore` del lote, o transferencia de licencia ya vencida | Emitir un código nuevo |

---

## 14. Lista de comprobación antes de vender

- [ ] `npm test` verde y prueba de humo OK en *staging*.
- [ ] Secretos de producción distintos de los de staging; copias offline de `service-prod.json` y `pepper-prod.txt`.
- [ ] `GL_ISSUER_PUBLIC_KEY` coincide con la huella de la pestaña *Claves* de GL.
- [ ] Limitador nativo activo y `/v1/time` disponible.
- [ ] Clave del servicio fijada en el directorio y huella comparada por otro canal.
- [ ] Un canje real completo en un dispositivo de pruebas, incluida revocación y transferencia.
- [ ] Copia de seguridad semanal programada y alerta propia al 70 % de la cuota.
- [ ] Proceso de soporte definido para transferencias (cómo te comunica el cliente su *thumbprint*).
