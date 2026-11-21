# Servicio de canje GL (activación comercial DShalev)

Servicio serverless que canjea códigos de **16 caracteres** (`XXXX-XXXX-XXXX-XXXX`),
acuñados por GL, por una **licencia firmada y ligada a una instalación**.

> **Estado:** núcleo, adaptador SQLite y cliente de referencia probados en Node. Shalev incluye un cliente Python en `Web-Desktop/dshalev/licencia/`; el canje comercial está deshabilitado hasta desplegar y fijar claves y URL. No se probó en Cloudflare ni con los builds reales.

## Decisiones de arquitectura

| Decisión | Elección |
|---|---|
| Autoridad de licencia | El **servicio** firma con su par P-256 (`gl-redeem-v1`). GL acuña los códigos y firma el lote con `gl-sign-v1`; su clave privada nunca sale del Keystore. |
| Alojamiento | **Serverless** (Cloudflare Workers + D1). El núcleo es agnóstico de runtime y usa WebCrypto. |
| Política sin conexión | **Offline hasta el vencimiento.** La licencia se verifica localmente cada arranque; la revocación solo se aplica al consultar el estado online o al reintentar un canje. |
| Identidad del dispositivo | Thumbprint `SHA-256(SPKI)` de una clave ECDSA P-256 generada en la instalación. **No** se afirma attestation ni clave hardware-validada. |
| Transferencia | Manual, autorizada por el operador hacia el thumbprint de la nueva instalación. Conserva el vencimiento restante. |
| Apps secundarias | **0 por defecto** (`MAX_SECUNDARIAS=0`). El cliente de referencia ya emite **sub-tokens firmados**; DShalev aún no tiene la pantalla que los emite, así que vender `secundarias > 0` sigue bloqueado hasta integrarla. |
| Restauración | Misma clave + mismo código → el servicio devuelve la misma licencia (`licenseId` y vencimiento intactos). |
| Renovación | Un código nuevo conserva los días restantes de la licencia activa de la misma instalación/app (rollover); si ya venció, empieza ahora. El INSERT calcula el vencimiento dentro de la transacción (`8c3dbe6`). |

## Flujo

```
GL (offline)                         Servicio (serverless)                 DShalev
────────────                         ─────────────────────                 ───────
1. Acuña lote de códigos
   firmado con gl-sign-v1
2. Exporta JSON ───────────────► 3. Verifica firma del lote
                                     guarda HMAC(código), no el código
                                                                            4. Pide código al usuario
                                                                            5. Genera clave P-256
                                                                            6. Firma la petición ──►
                                  7. Verifica prueba de posesión
                                     canje atómico + idempotencia
                                  8. Firma licencia ◄──────────────────────
                                                                            9. Verifica firma,
                                                                               app y thumbprint
                                                                           10. Guarda y usa offline
                                                                               hasta vencimiento
```

### Por qué dos claves

- `gl-sign-v1` (Keystore de GL) autentica **lotes**. Si se filtra el servicio,
  un atacante no puede acuñar códigos nuevos.
- `gl-redeem-v1` (secreto del servicio) firma **licencias**. Si se filtra el
  teléfono de GL, un atacante no puede emitir licencias de canje.
- El cliente fija **solo** `gl-redeem-v1`. Una firma de lote no verifica como
  licencia y viceversa (dominios `GL-BATCH1` vs `GL-REDEEM-LICENSE1`).

## Contrato

### Lote (GL → servicio, fuera de banda)

```json
{
  "v": 1,
  "alg": "GL-BATCH-P256-SHA256-v1",
  "batchId": "11111111-1111-4111-8111-111111111111",
  "issuerKid": "gl-sign-v1",
  "createdAt": "2026-11-10T12:00:00.000Z",
  "appId": "DSHALEV",
  "entries": [
    {
      "code": "4K7N-9X2M-B8PQ-3T5V",
      "type": "MENSUAL",
      "secundarias": 1,
      "redeemBefore": "2027-01-01T00:00:00.000Z"
    }
  ],
  "sig": "<ECDSA P-256 SHA-256 DER, Base64>"
}
```

Bytes firmados (no el JSON): `canonical([...])` con el mismo *length-prefixing*
de 4 bytes big-endian que `CryptoEngine.canonical`. Se incluye el SHA-256 en
Base64url de cada entrada. Vector dorado cruzado Kotlin↔JS en
`app/src/test/java/dev/gl/license/RedeemBatchTest.kt` y `test/batch.test.js`.

El lote contiene los códigos en claro: es material secreto de operador. El
servicio los convierte a `HMAC-SHA256(pepper, "GL-CODE1|" + código)` y descarta
el claro. Los códigos generados excluyen `I` y `O` (34 símbolos).

### Canje (cliente → servicio)

`POST /v1/redeem`

```json
{
  "v": 1,
  "appId": "DSHALEV",
  "code": "4K7N-9X2M-B8PQ-3T5V",
  "deviceKey": "<SPKI P-256, Base64>",
  "requestId": "<uuid>",
  "requestedAt": "2026-11-10T12:00:00.000Z",
  "nonce": "<16 B Base64>",
  "proof": "<ECDSA DER Base64>"
}
```

`proof` firma `canonical(["GL-REDEEM-REQUEST1", "1", appId, códigoNormalizado,
deviceKey, requestId, requestedAt, nonce])`. El código normalizado va dentro de
la firma: cambiar una letra invalida la prueba.

Respuesta:

```json
{
  "v": 1,
  "ok": true,
  "restored": false,
  "license": {
    "v": 1,
    "alg": "GL-REDEEM-LICENSE-P256-SHA256-v1",
    "kid": "gl-redeem-v1",
    "license": {
      "v": 1,
      "licenseId": "<uuid>",
      "appId": "DSHALEV",
      "type": "MENSUAL",
      "status": "ACTIVA",
      "issuedAt": "2026-11-10T12:00:00.000Z",
      "expiresAt": "2026-12-10T12:00:00.000Z",
      "deviceThumbprint": "<SHA-256(SPKI) Base64url>",
      "devicePublicKey": "<SPKI P-256 Base64 de la instalación primaria>",
      "redemptionId": "<uuid>",
      "transferredFrom": null,
      "secundarias": 1
    },
    "sig": "<ECDSA DER Base64>"
  }
}
```

La licencia **no contiene** código, nombre, teléfono, CI, IP ni `deviceId`. La
identidad es el thumbprint. El vencimiento lo calcula el servidor (30/180/365
días; `PERPETUA` → `null`); nunca se acepta una fecha del cliente.

### Sub-tokens (apps secundarias)

La licencia primaria incluye `devicePublicKey` (SPKI de la clave que la
instalación usa para firmar) y `secundarias` (cupo, 0–10). Con cupo > 0, la
instalación primaria puede emitir un permiso firmado para otra instalación:

```text
GL-REDEEM-SUB1|<licenseId>|<thumbprintSecundaria>|<slot>|<allowed>|<venceEn|PERPETUA>
```

La firma es ECDSA P-256/SHA-256 (DER Base64) de la **clave del dispositivo
primario**; la secundaria la verifica con `devicePublicKey`. `allowed` y
`venceEn` deben coincidir exactamente con `secundarias` y `expiresAt` de la
licencia primaria, y `slot` debe estar en `1..allowed`. El paquete offline es:

```json
{ "v": 1, "primaryLicense": { "v": 1, "alg": "...", "kid": "...", "license": { ... }, "sig": "..." },
  "subToken": { "v": 1, "alg": "GL-REDEEM-SUB-P256-SHA256-v1", "licenseId": "...",
                "deviceThumbprint": "<secundaria>", "slot": 1, "allowed": 2,
                "expiresAt": "...", "sig": "..." } }
```

La secundaria verifica la licencia primaria, el permiso, su propio thumbprint y
la fecha, y funciona **sin conexión** hasta el vencimiento. Limitaciones: no
consulta `/v1/status` de la primaria (no posee su clave) y una revocación de la
primaria no llega a la secundaria hasta que renueve/reciba un paquete nuevo.
`MAX_SECUNDARIAS=0` (por defecto) impide vender este derecho hasta que la app
primaria lo emita de verdad.

### Estado (revocación / vencimiento)

`POST /v1/status` con `licenseId` en lugar de `code`, y `proof` sobre
`GL-REDEEM-STATUS-REQUEST1`. Devuelve un documento firmado con
`GL-REDEEM-STATUS-P256-SHA256-v1` y `checkedAt`. Sin esta llamada, una licencia
offline sigue valiendo hasta su vencimiento.

### Errores

| `code` | HTTP | Cuándo |
|---|---|---|
| `INVALID_REQUEST` | 400 | JSON, campos inválidos |
| `CLOCK_SKEW` | 400 | Reloj fuera de ±5 min; `error.serverTime` indica la hora UTC. `GET /v1/time` permite consultarla sin autenticación (limitado por IP, no-store). |
| `PAYLOAD_TOO_LARGE` | 413 | Cuerpo de más de 16 KiB |
| `INVALID_PROOF` | 401 | Firma del dispositivo ausente o inválida |
| `CODE_UNAVAILABLE` | 404 | Código inexistente, revocado, de otra app o sin transferencia autorizada |
| `CODE_ALREADY_REDEEMED` | 409 | Canje activo en otra instalación |
| `CODE_EXPIRED` | 410 | `redeemBefore` vencido, o transferencia de licencia vencida |
| `LICENSE_NOT_FOUND` | 404 | `/status` de una licencia que no es de esta clave |
| `RATE_LIMITED` | 429 | Límite por IP o por instalación |
| `IDEMPOTENCY_CONFLICT` | 409 | `requestId` reutilizado con otro código o clave |
| `SERVER_ERROR` | 500 | Fallo interno sin detalles |

`CODE_UNAVAILABLE` unifica inexistente, revocado y app equivocada para no dar un
oráculo. `CODE_ALREADY_REDEEMED` solo se devuelve a quien ya tiene el código.

Tras transferir un rollover, restaura con el código más reciente: los períodos absorbidos quedan revocados. `sql-revoke --license <id>` revoca por instalación; `sql-revoke --code <código>` revoca solo ese código y sus licencias. Usa una terminal privada sin historial para esta última opción.

## Comportamiento ante incidentes

| Situación | Comportamiento |
|---|---|
| **Vencimiento** | El cliente compara `expiresAt` con `max(reloj, marca de agua)`. Al vencer, bloquea; no renueva solo. |
| **Revocación** | Se aplica en `/v1/status` y en el siguiente canje. Sin conexión, la licencia sigue activa hasta vencer (limitación explícita de la política elegida). |
| **Falta de conexión al activar** | No se guarda nada. El error incluye `requestId` para reintentar. |
| **Pérdida de respuesta tras el canje** | El reintento con el mismo `requestId` o desde la misma clave devuelve la misma licencia; no se emite otra ni se consume otro código. |
| **Respuesta manipulada** | El cliente verifica ECDSA con la clave fijada, `alg`, `kid`, `appId` y `deviceThumbprint` antes de persistir. |
| **Restauración** | Reinstalar la app conservando la identidad protegida del dispositivo devuelve la misma licencia. Borrar la clave la pierde: no hay recuperación automática. |
| **Transferencia** | Solo el operador, indicando el thumbprint de destino. La licencia anterior queda `REVOCADA` y la nueva hereda el vencimiento restante. Una licencia ya vencida no se transfiere. Si el destino se copió mal, el operador puede volver a autorizar la misma licencia hacia otro thumbprint mientras la transferencia siga pendiente. |
| **Reloj retrasado** | La marca de agua local detecta retrocesos de más de 2 h y devuelve `CLOCK_ROLLBACK`. Es detección de mejor esfuerzo; el almacén local puede alterarse. |

## Privacidad

- No se guardan códigos en claro, pruebas de posesión, claves, nonces, IPs ni
  datos personales. Solo `HMAC(código)` y el thumbprint (pseudónimo).
- El logger tiene **lista blanca** de campos; cualquier otro se descarta.
- Los contadores de rate limit usan HMAC con el pepper del servicio.
- El cliente no envía nombre, CI ni teléfono: este flujo es de activación, no
  de emisión. Si DShalev necesita vincular la compra a una persona, debe
  decidirse y documentarse antes de añadirlo (no se ha inventado).

## Despliegue (Cloudflare Workers + D1)

Configura `ALLOWED_APPS` como lista separada por comas (propuestos: `DSHALEV`, `SHALEV-ANDROID`, `SHALEV-IOS`; confirmar antes del despliegue). Copia `examples/wrangler.jsonc` a `wrangler.jsonc` antes de ejecutar Wrangler. Los bindings `RL_IP` y `RL_DEVICE` son obligatorios y deniegan si fallan. No escriben contadores D1. El adaptador para pruebas con contadores limpia ventanas de más de un día de forma probabilística. Consulta la [guía de despliegue en Cloudflare Workers + D1](../../docs/GUIA_CLOUDFLARE_WORKERS_D1.md) para preparar staging, probar el canje y pasar a producción.
El SQL de importación es reintentable (INSERT OR IGNORE); no añadas BEGIN/COMMIT al archivo para D1.

1. Crear D1, copiar el `database_id` al archivo de configuración local y aplicar el esquema:

   ```bash
   wrangler d1 create gl-redeem
   wrangler d1 execute gl-redeem --remote --file migrations/0001_init.sql
   ```

2. Generar claves y fijar secretos (nunca en el repositorio):

   ```bash
   node src/cli.js gen-keys --role gl --out ./secrets/gl-issuer.json
   node src/cli.js gen-keys --role service --out ./secrets/service.json
   openssl rand -base64 32   # CODE_PEPPER

   wrangler secret put GL_ISSUER_PUBLIC_KEY   # SPKI Base64 de gl-issuer.json
   wrangler secret put SERVICE_PRIVATE_KEY    # PKCS8 Base64 de service.json
   wrangler secret put SERVICE_PUBLIC_KEY     # SPKI Base64 de service.json
   wrangler secret put CODE_PEPPER            # Base64 de 32 bytes
   ```

3. Configurar `wrangler.jsonc` (ver `examples/wrangler.jsonc`). Para DShalev,
   `MAX_SECUNDARIAS = "0"` hasta que su pantalla primaria emita sub-tokens;
   `ALLOWED_APPS = "DSHALEV"`. Desplegar:

   ```bash
   wrangler deploy
   ```

4. Fijar `SERVICE_PUBLIC_KEY` y `kid` en DShalev. La clave pública no es
   secreta, pero **debe** compararse por un canal confiable antes de fijarla.

5. Acuñar e importar lotes:

   ```bash
   node src/cli.js mint-batch --key ./secrets/gl-issuer.json \
     --app DSHALEV --count 100 --type MENSUAL --out ./secrets/lote.json
   node src/cli.js verify-batch --batch ./secrets/lote.json \
     --public-key "$GL_ISSUER_PUBLIC_KEY"
   CODE_PEPPER=... node src/cli.js sql-import --batch ./secrets/lote.json \
     --public-key "$GL_ISSUER_PUBLIC_KEY" --out ./secrets/import.sql
   wrangler d1 execute gl-redeem --remote --file ./secrets/import.sql
   rm ./secrets/import.sql
   ```

   En producción, `mint-batch` debe ejecutarse **desde GL** (caso de uso Kotlin
   `CrearLoteCanjeUseCase`), no con una clave suelta en un portátil. El comando
   CLI existe para pruebas y contingencia.

6. Operación: `sql-revoke` y `sql-transfer` generan SQL para D1. La
   transferencia exige `--to-thumbprint` (43 caracteres Base64url).

### Desarrollo local

```bash
export GL_ISSUER_PUBLIC_KEY=... SERVICE_PRIVATE_KEY=... SERVICE_PUBLIC_KEY=...
export CODE_PEPPER=... STORE_FILE=./data/dev.json ALLOWED_APPS=DSHALEV
node src/cli.js import-local --batch ./secrets/lote.json --store ./data/dev.json
node src/node-server.js   # 0.0.0.0:8787
```

`STORE_FILE` usa un JSON sin bloqueo: solo desarrollo. En producción, D1.

## Pruebas

Requisito: Node **22.5 o superior** (las pruebas D1 usan `node:sqlite`; el
servicio en sí no depende de esa característica).

```bash
cd services/redeem
npm test
```

119 pruebas con `node:test` (sin dependencias):

- criptografía y canonicalización, DER↔P1363;
- lote: firma, tamper, duplicados, app, límites, deadline;
- canje: feliz, perpetua, caducado, revocado, app incorrecta, prueba inválida;
- idempotencia y concurrencia (mismo `requestId`, mismo código, carreras);
- seguridad: licencia manipulada, clave ajena, otro dispositivo, `kid`
  desconocido, separación de dominios, almacén manipulado;
- red: respuesta perdida, servidor caído, timeout, respuesta no JSON, reintento;
- offline: vencimiento, perpetua, marca de agua, rollback, revocación;
- transferencia, restauración y revocación;
- rollover de días restantes y sub-tokens de secundarias;
- HTTP: rutas, métodos, tamaños, 413, errores uniformes, CORS, rate limit;
- privacidad: lista blanca de logs, sin códigos/IPs/thumbprints;
- D1: el esquema y el adaptador se ejecutan sobre `node:sqlite` (emulación de la
  API D1), incluyendo el índice único parcial y el `batch` transaccional.

El cliente de referencia (`src/client/redeem-client.js`) es el que se prueba de
extremo a extremo. Las pruebas aquí no ejecutan el cliente Python de Shalev.

Además de las pruebas, en esta sesión se ejecutó el flujo operativo real:
`gen-keys` → `mint-batch` → `import-local` → servidor Node en `0.0.0.0:8787` →
cliente por HTTP real → activación → carga offline → `/v1/status` → rechazo de
un segundo equipo. El log del servidor no contenía el código, la clave ni el
thumbprint del dispositivo.

## Compatibilidad con lo existente

- No cambian `Spvi23`, `HybridBox` ni los formatos existentes.
- `SPVI2:` sigue siendo un formato distinto: el cliente de canje lo rechaza y
  expone `verifyLegacy` para el verificador existente.
- La mera validez del formato 4×4 no activa nada: `activate()` siempre exige
  canje online y licencia firmada.

## Pendiente para uso comercial

1. **DShalev**: configurar y ejecutar las pruebas del cliente Python real.
2. **GL real**: exportar un lote desde el teléfono emisor y canjearlo con un
   build de DShalev. El caso de uso Kotlin **no se ha compilado** aquí (no hay
   JDK/Android SDK/Maven). El vector cruzado reduce el riesgo, no lo elimina.
3. **Cloudflare**: desplegar Workers + D1 reales, probar latencia, límites de
   tasa del borde y copias de seguridad.
4. **Claves**: definir custodia, rotación y procedimiento de compromiso. Hoy
   `kid` es único y no hay rotación.
5. **UI de operador en GL**: validar la nueva pantalla Lotes en un dispositivo.
6. **Transferencia**: definir el canal por el que el cliente comunica su
   thumbprint al operador (correo, WhatsApp, etc.) sin registrar datos
   personales innecesarios.
7. **Clave del lote**: si se borra el Keystore de GL o se cambia de teléfono,
   cambia `gl-sign-v1` y el servicio deja de aceptar lotes nuevos. Hay que
   reexportar la pública, fijarla en el servicio y decidir qué hacer con los
   lotes antiguos. No hay rotación automática.
8. **Precios/pagos**: este servicio no cobra ni verifica pagos. El canje asume
   que el código se vendió por un canal externo.
9. **Pruebas de carga y abuso**: no se ejecutaron.
10. **Política offline**: elegida «hasta el vencimiento». Si se quiere revocación
    efectiva sin conexión, hace falta otra decisión (y probablemente hardware).
11. **Importación D1**: `importBatch` no es transaccional por lote en el
    adaptador D1 (sí lo es cada canje). Un fallo a mitad deja códigos ya
    importados; reejecutar el SQL continúa y los duplicados se omiten.

### Interoperabilidad con DShalev

El vector público de prueba `test/fixtures/dshalev-protocol-v1.json` contiene
peticiones, licencia, estado y sub-token firmados, SPKI públicas y bytes canónicos.
No incluye claves privadas ni representa claves de producción. El test
`test/dshalev-vector.test.js` comprueba que el contrato siga coincidiendo.
El prompt para la implementación Python está en `docs/PROMPT_DSHALEV.md`.
