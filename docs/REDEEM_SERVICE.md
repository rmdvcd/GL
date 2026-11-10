# Canje comercial de códigos 4×4

Documento de estado y contrato resumido. La implementación y el runbook están
en [`services/redeem/README.md`](../services/redeem/README.md).

## Qué existe

| Pieza | Ubicación | Estado |
|---|---|---|
| Servicio de canje serverless (WebCrypto, sin dependencias) | `services/redeem/src/` | Implementado y probado en Node |
| Esquema D1 y adaptador | `migrations/0001_init.sql`, `src/d1-store.js` | Ejecutado sobre `node:sqlite`; **no** contra D1 real |
| Cliente de referencia | `services/redeem/src/client/redeem-client.js` | Implementado y probado |
| Acuñación de lotes en GL | `domain/model/RedeemBatch.kt`, `domain/usecase/CrearLoteCanjeUseCase.kt` | Escrito; **no compilado** en este entorno |
| Pruebas JS | `services/redeem/test/` | 107 pruebas, 0 fallos |
| Pruebas Kotlin del lote | `app/src/test/.../RedeemBatchTest.kt` | Escritas; **no ejecutadas** (sin JDK/Android SDK) |
| Integración DShalev | — | **No realizada**: exportación Repomix revisada; acceso directo al repositorio pendiente |

## Decisión de diseño

El servicio es una **autoridad de firma nueva** (`gl-redeem-v1`). GL conserva la
autoridad offline sobre la **acuñación** de códigos (`gl-sign-v1`) y el servicio
sobre la **emisión** de licencias de canje. Así:

- el teléfono emisor no necesita estar online para vender;
- filtrar el servicio no permite acuñar códigos;
- filtrar el teléfono no permite emitir licencias de canje;
- el cliente fija solo la pública del servicio.

El código de 16 caracteres es un **portador**: quien lo tiene puede canjearlo en
la primera instalación que se autentique. La prueba de posesión impide que un
tercero que intercepte la respuesta se quede la licencia, y el vínculo al
thumbprint impide usarla en otro equipo. No convierte el código en un secreto
intransferible: la entrega del código al comprador sigue siendo el punto crítico.

## Contrato resumido

- **Lote**: JSON firmado con `gl-sign-v1` sobre bytes canónicos con
  length-prefixing de 4 bytes. Incluye `appId`, tipo, secundarias, `batchId`,
  `createdAt` y, opcionalmente, `redeemBefore`.
- **Canje**: `POST /v1/redeem`. La prueba ECDSA del dispositivo firma
  `GL-REDEEM-REQUEST1` + app + código normalizado + SPKI + `requestId` +
  `requestedAt` + `nonce`.
- **Licencia**: JSON firmado con `gl-redeem-v1`, sin PII ni código, con
  `licenseId`, `appId`, tipo, estado, fechas, `deviceThumbprint`,
  `devicePublicKey`, `redemptionId` y `secundarias`. `devicePublicKey` es la
  SPKI de la clave primaria y permite verificar sub-tokens; `secundarias` es el
  cupo. Para DShalev, `MAX_SECUNDARIAS=0` por defecto hasta que la app primaria
  emita permisos de verdad.
- **Sub-token**: `GL-REDEEM-SUB1|licenseId|thumbprintSecundaria|slot|allowed|venceEn`,
  firmado por la clave primaria (`GL-REDEEM-SUB-P256-SHA256-v1`). La secundaria
  lo verifica offline contra `devicePublicKey`, su propio thumbprint y la fecha;
  `allowed`/`venceEn` deben coincidir con la licencia primaria. No hay estado
  online de la secundaria: la revocación de la primaria no se propaga offline.
- **Estado**: `POST /v1/status`, documento firmado para revocación/vencimiento.
- **Idempotencia**: `requestId` único y un solo canje activo por código. Reintento
  con la misma clave devuelve la misma licencia; con otra clave devuelve
  `CODE_ALREADY_REDEEMED`.
- **Renovación**: al canjear un código nuevo, el vencimiento se calcula desde el
  vencimiento de la licencia activa más reciente de esa instalación/app
  (rollover); si ya venció, desde ahora. No se acumula por reintentos.
- **Transferencia**: autorización del operador hacia un thumbprint concreto;
  conserva el vencimiento restante y revoca la licencia anterior.
- **Offline**: la licencia vale hasta su vencimiento; la revocación solo se
  aprende al consultar el estado o al reintentar el canje.

## Compatibilidad

- El contrato v1 (`ECIES-P256-AES256GCM-v1`), `.glreg` y el flujo SPVI **no se
  tocan**.
- `SPVI2:` sigue siendo exactamente el documento de Shalev: 210 caracteres
  Base64url tras el prefijo, firma `gl-sign-v1` sobre `"SPVI-L2|" || epk || ct ||
  tag`, HKDF con info `SPVI-L2`, AAD `"SPVI-L2|" || epk` y huella
  `SHA-256("SPVI-L2|" + deviceId + "|" || devicePub)[0..16]`. Este servicio no
  reutiliza ese parser ni esa huella.
- Los dominios de firma son distintos; una firma de lote no sirve como licencia
  y una licencia de canje no verifica como `SPVI2`.
- DShalev es el cliente Windows; Shalev Android conserva su panel y su
  verificación actuales. La activación comercial es una vía **paralela**, no un
  reemplazo del panel existente.
- Una cadena con formato 4×4 válido **no activa**: el cliente exige canje y
  licencia firmada.
- El cliente de referencia deja un punto de extensión `verifyLegacy` para el
  un verificador SPVI2 si una aplicación ya lo tiene; el código exportado de DShalev no lo implementa.

## Privacidad

El servicio no guarda códigos, tokens, nonces, IPs ni datos personales. Guarda
`HMAC-SHA256(pepper, código)`, el thumbprint y la SPKI pública de la clave del
dispositivo, y metadatos de la licencia. El logger solo admite una lista blanca de campos.

## Por qué no se integra DShalev todavía

Los tres documentos ya están copiados en `docs/` (`LICENSE_CLIENT.md`,
`GL_ESPECIFICACION.md`, `DSHALEV_GL_SERVICIO_PROPUESTA.md`). El repositorio
`rmdvcd/Shalev` sigue devolviendo `403 Resource not accessible by integration`
para el token de esta sesión, incluso después de que el usuario dijera que daría
acceso; hay que confirmar la instalación de la GitHub App o reconectar GitHub en
Arena.

Se recibió y revisó una exportación Repomix de DShalev. Es Python/FastAPI,
con panel informativo en `dshalev/templates/license.html`, ruta en `app.py`,
y una prueba local para secundarias en `sync_server.py`; no es un cliente .NET.
La exportación puede omitir archivos, no acredita el estado actual del repositorio
y no permite modificarlo aquí. La integración sigue pendiente. El prompt
[`PROMPT_DSHALEV.md`](PROMPT_DSHALEV.md) describe los cambios para otra sesión.

## Pendiente antes de uso comercial

1. Acceso efectivo al repositorio DShalev y revisión de su estado actual.
2. Integrar el cliente en DShalev y ejecutar sus pruebas.
3. Compilar GL con JDK 17 y ejecutar `:app:testDebugUnitTest` (incluye
   `RedeemBatchTest`). Aquí no hay JDK, Android SDK ni Maven Central.
4. Exportar un lote desde el GL real y canjearlo con un build real de DShalev.
5. Desplegar Cloudflare Workers + D1 y probar el flujo sobre HTTPS real.
6. Decidir custodia/rotación de `gl-redeem-v1` y plan de compromiso.
7. Añadir la pantalla de operador en GL para acuñar y exportar lotes.
8. Definir el canal de soporte para transferencias y restauraciones.
9. Pruebas de carga, abuso y límites del borde.
10. Si se quiere revocación efectiva sin conexión, cambiar la política offline
    (hoy es «válida hasta el vencimiento»).
11. Resolver renovaciones simultáneas de códigos distintos para la misma
    instalación: el rollover actual lee antes de insertar y no serializa esas
    renovaciones; pueden perderse días. No vender esta función hasta resolverlo.
12. Resolver reutilización de slots y compatibilidad de sub-tokens con las
    secundarias reales antes de elevar `MAX_SECUNDARIAS` sobre cero.

## Cómo verificar lo implementado

```bash
cd services/redeem
npm test
```

Resultado de esta sesión: **107 pruebas, 0 fallos** (Node 22.22.3).
Las pruebas cubren criptografía, lotes, canje, idempotencia, concurrencia,
manipulación, red, offline, transferencia, rollover, sub-tokens de secundarias,
HTTP, privacidad y el esquema D1 sobre `node:sqlite`.

## Integración en DShalev

**Documentación recibida y revisada. Integración en código: no iniciada.**

Se recibieron `LICENSE_CLIENT.md`, `docs/GL_ESPECIFICACION.md` y
`docs/DSHALEV_GL_SERVICIO_PROPUESTA.md`. De ellos se extrae:

- DShalev es el cliente **Windows** de la familia Shalev; el documento de
  `LICENSE_CLIENT.md` describe el cliente **Android** y su protocolo `SPVI2:`.
- El nuevo canje **no debe llamarse `SPVI2`** ni reutilizar su parser/AAD/huella;
  nuestra licencia usa un formato y dominios distintos.
- El protocolo existente `SPVI2:` (210 caracteres, firma `gl-sign-v1`,
  `SPVI-L2|`, huella `SPVI-L2|deviceId|devicePub`) **no se toca**.
- La propuesta de DShalev exige identidad persistente protegida por el SO
  (menciona DPAPI), claves fijadas, verificación antes de guardar, política
  offline/revocación/transferencia explícita y no registrar códigos ni PII.
- La app DShalev **todavía no emite sub-tokens** de apps secundarias; el
  cliente JS de referencia ya los implementa. El campo `secundarias` no debe
  venderse como derecho efectivo hasta que DShalev tenga la pantalla que emite
  permisos.

La exportación revisada muestra Python/FastAPI y un panel informativo sin
verificador GL. `database.py` usa DPAPI para la clave de la base; eso no es una
identidad de licencia. `sync_server.py` envía una prueba provisional de siete
días. Los respaldos pueden restaurarse en otro equipo y los locales tienen datos
separados: ambos necesitan una política explícita antes de producción. El
repositorio directo no fue accesible en esta sesión; no se modificó DShalev.

### Qué debe implementar DShalev cuando se dé acceso

1. **Identidad persistente:** generar una clave P-256 una vez y guardarla
   protegida por el SO (DPAPI/CNG en Windows). Exportar la SPKI para el canje y
   el thumbprint; nunca regenerarla en cada arranque.
2. **HTTP fijado:** URL base de producción en configuración, TLS con validación
   de certificado y nombre, timeout y reintento con el mismo `requestId`.
3. **Prueba de canje:** firmar los bytes canónicos de `redeemProofBytes`
   (`GL-REDEEM-REQUEST1`) con la clave persistente; incluir código normalizado,
   `appId`, `deviceKey`, `requestId`, `requestedAt` y `nonce`.
4. **Verificación previa a guardar:** firma del servicio con la clave fijada,
   `kid`, `alg`, `appId`, `deviceThumbprint` y que
   `SHA-256(devicePublicKey) == deviceThumbprint`. Repetirla en cada uso.
5. **Almacén:** sobre firmado, `lastSeenMs`, estado y (si es secundaria) el
   paquete primario + sub-token, cifrado con DPAPI. No guardar el código.
6. **Estados:** mapear `ACTIVA`/`VENCIDA`/`REVOCADA`/`PERPETUA` a su panel sin
   alterar el contrato Android `SPVI2:`; un fallo de red o respuesta inválida no
   borra la licencia previa.
7. **Sub-tokens:** la primaria firma permisos con su clave; la secundaria los
   verifica offline. Si DShalev aún no tiene esta pantalla, mantener
   `MAX_SECUNDARIAS=0`.
8. **Transferencia/restauración:** conservar la clave persistente; si se pierde,
   no hay recuperación automática. La transferencia la autoriza el operador con
   el thumbprint del equipo nuevo.

### Decisiones tomadas

1. **Modelo de activación:** **una instalación activa por código**; la
   transferencia es manual y la autoriza el operador. Si DShalev vende por local
   con varios equipos, el cambio de modelo es una decisión de producto posterior.
2. **Renovación:** **rollover**. Un código nuevo suma su periodo al vencimiento
   de la licencia activa más reciente de esa instalación/app; si ya venció,
   empieza ahora. Los reintentos no duplican días.
3. **Secundarias:** **sub-token firmado** (`GL-REDEEM-SUB1`) emitido por la
   instalación primaria y verificado offline por la secundaria. El servicio
   sigue con `MAX_SECUNDARIAS=0` por defecto; se sube solo cuando DShalev tenga
   la pantalla que emite permisos.
4. **Rotación de claves:** `kid` es único; no hay lista de rotación ni fecha de
   caducidad. Sigue abierto.
5. **Almacén Windows:** DShalev debe persistir la clave P-256 con DPAPI/CNG y no
   generar una identidad nueva en cada arranque. El cliente JS de referencia ya
   exige claves persistidas; `createEphemeral` es solo para pruebas.
6. **Revocación offline:** la política sigue siendo válida hasta el vencimiento;
   una secundaria no consulta el estado de la primaria y no recibe revocaciones.

Para implementar, trabajar sobre el repositorio DShalev real y contrastarlo con la exportación recibida.

### Entradas necesarias

- Repositorio Shalev/DShalev y los tres documentos:
  `LICENSE_CLIENT.md`, `docs/GL_ESPECIFICACION.md`,
  `docs/DSHALEV_GL_SERVICIO_PROPUESTA.md`.
- `appId` exacto que acuñará GL en el lote (hoy configurado como `DSHALEV` solo
  como ejemplo).
- Almacenamiento local disponible en Windows (DPAPI/CNG, archivo cifrado) y
  versión de Python y empaquetado Windows soportados.
- Conservar el contrato SPVI2 de Android; no presumir un verificador SPVI2 en DShalev.
- Cómo se muestra el código al usuario y cómo se comunica el thumbprint al
  operador para una transferencia.

### Pasos en el cliente

1. Generar o recuperar una clave **ECDSA P-256 por instalación** en Windows
   (DPAPI/CNG). Exportar solo la SPKI y calcular el thumbprint.
2. Fijar en el build la pública `gl-redeem-v1` y su `kid`, verificando la huella
   por un canal confiable.
3. En activación: normalizar el código (mayúsculas, sin guiones/espacios,
   alfabeto sin `I`/`O`), generar `requestId` y `nonce`, firmar el transcript
   `GL-REDEEM-REQUEST1`, enviar `POST /v1/redeem`.
4. Antes de guardar: verificar firma con la clave fijada, `alg`, `kid`,
   `appId`, `deviceThumbprint == SHA-256(SPKI)` y
   `SHA-256(devicePublicKey) == deviceThumbprint`; comprobar que no está vencida.
5. Guardar el sobre firmado y una marca de agua monótona. No guardar el código
   ni datos personales.
6. En cada arranque: verificar el sobre offline, aplicar
   `max(reloj, marca de agua)` y detectar retrocesos. Licencia válida hasta
   `expiresAt`.
7. Reintentos: conservar el `requestId` si no se sabe si el servidor procesó.
   Si se perdió, un nuevo `requestId` desde la misma clave devuelve la misma
   licencia.
8. Restauración: conservar la identidad protegida por Windows; con el mismo código el
   servicio devuelve la misma licencia. Si se borra la clave, no hay
   recuperación automática.
9. Revocación: llamar a `/v1/status` cuando haya red (o al abrir la pantalla de
   licencia) y respetar `REVOCADA`. Sin conexión no se puede garantizar.
10. No modificar el contrato Android SPVI2 ni confundirlo con la licencia de canje.

### Prohibiciones

- No activar por la validez del formato 4×4.
- No incluir claves privadas de GL ni del servicio en el cliente.
- No aceptar una licencia sin verificar la firma o con otro `deviceThumbprint`.
- No usar el reloj del servidor como única fuente si no hay conexión; y no
  confiar en el reloj local como prueba fuerte.
- No registrar códigos, tokens ni datos personales.

### Matriz de pruebas que deberá aportar DShalev

| Caso | Resultado esperado |
|---|---|
| Código válido, primera activación | Licencia ACTIVA y usable offline |
| Código ya canjeado en otro equipo | Error genérico, sin filtrar datos |
| Respuesta manipulada | Rechazo; no se guarda |
| Sin red al activar | Error con `requestId`; reintento seguro |
| Red perdida tras canje | Reintento devuelve la misma licencia |
| Reinstalación con identidad protegida intacta | Misma licencia |
| Reloj retrasado | `CLOCK_ROLLBACK` o vencimiento por marca de agua |
| Licencia vencida | Bloqueo local |
| Revocación online | Bloqueo tras `/v1/status` |
| SPVI2 existente | Sigue verificándose igual que antes |
