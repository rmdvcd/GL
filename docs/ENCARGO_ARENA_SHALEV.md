# Encargo para Arena: completar el canje de licencias en Shalev

**Cómo usar este archivo:** abre una sesión de Agent Mode en el repositorio **`rmdvcd/Shalev`**, adjunta este `.md` y escribe: **«Implementa este encargo en la rama actual de Shalev. Empieza por DShalev, continúa con Android e iOS y deja producción intacta».** No hace falta adjuntar archivos del emisor ni del equipo donde está instalada la licencia.

## Mandato y límites

Entrega cambios de código, no otro plan. El objetivo es que una instalación pueda canjear o renovar con un código comercial de 16 caracteres, guardar **solo** la licencia verificada y consultar después su estado. La activación offline con `SPVI2:` sigue siendo independiente. La búsqueda de lotes y las marcas «Libre/Entregado» son funciones del operador **en GL**, no de los clientes Shalev.

- Trabaja solo en la rama asignada de **Shalev**. Si la sesión está en GL, detente y pide abrir la sesión correcta. Respeta los cambios locales; no reemplaces archivos de configuración completos. No hagas merge, release ni despliegue.
- No uses el código piloto, el teléfono emisor, la instalación real de Windows, Cloudflare ni D1. No importes lotes ni cambies `ALLOWED_APPS` o `MAX_SECUNDARIAS`. No solicites ni guardes códigos reales, identidades, claves privadas, peppers o passphrases. No registres cuerpos HTTP de licencias ni códigos en errores, URLs, capturas o telemetría.
- Esta entrega prioriza implementación: **no ejecutes suites ni pruebas en dispositivos o contra el Worker**. Revisa el diff y el cableado estáticamente. Informa qué no se ejecutó; no declares compilación, pruebas ni activación verificadas sin evidencia. Esto no sustituye la validación previa a una futura publicación.

## 1. Confirmar la base antes de editar

Lee `AGENTS.md`, `README.md`, `Android/README.md`, `Pruebas.md`, `SECURITY.md`, `LICENSE_CLIENT.md`, `IMPLEMENTACION_SHALEV.md` (en particular fases 1–5 y anexos), `docs/IMPLEMENTACION_NOTAS.md` y los README de escritorio e iOS. Como referencia **de solo lectura**, consulta en [GL](https://github.com/rmdvcd/GL) `docs/REDEEM_SERVICE.md`, `docs/DIRECTORIO_V1.md` y `services/redeem/src/{protocol.js,license.js,http.js,client/redeem-client.js}`. Los contratos desplegados mandan sobre un ejemplo antiguo.

Punto de partida observado en Shalev: `c18ec7cbd5ee2d60395bc0409e682fd113177de0`. **No vuelvas a ese commit**: compara con tu rama. Ya existen `ClienteGL`, clientes móviles `RedeemClient`, directorios bootstrap y lógica de licencia. Sigue cada flujo desde la pantalla hasta el almacén, incluidos los puntos de inyección reales; conserva lo resuelto. Si una regla de `AGENTS.md` para activación offline o acceso a red parece contradecir el plan de canje, limita la extensión al flujo HTTPS comercial explícito. Documenta cualquier contradicción no resoluble y continúa los pasos independientes.

| Cliente | ID comercial previsto | Estado operativo de partida |
|---|---|---|
| DShalev Windows (`Web-Desktop/`) | `DSHALEV` | Único ID autorizado en producción. El intento piloto se detuvo en `CLOCK_SKEW`; no se confirmó una activación. |
| Shalev Android (`Android/`) | `SHALEV-ANDROID` | Completar la integración sin presentar el servicio como disponible en producción. |
| IShalev (`iOS/`) | `SHALEV-IOS` | Igual que Android. |

Base pública autorizada: `https://gl-redeem.rmdvcd.workers.dev`. Para las marcas derivadas, localiza los *flavors*, esquemas y puntos de configuración reales; comparte el cliente, no la identidad ni el `appId` entre productos. No conviertas datos de demostración `MIPVentaX_Debug` en otro producto ni inventes IDs comerciales. Deja las variantes no confirmadas deshabilitadas y enuméralas como decisión del propietario.

## 2. Contrato común: implementar una vez por plataforma

1. **Configuración y confianza.** Resuelve `appId`, habilitación por build y base HTTPS en un solo lugar. Usa las raíces públicas A/B fijadas y el directorio firmado `SPVI-DIR1`; verifica bootstrap/caché y conserva el mayor `seq` válido. Para licencia y estado del Worker, selecciona por **`kid` exacto y `uso: canje`** (actual: `gl-redeem-v1`), con las reglas de vigencia y compromiso del directorio. `gl-sign-v1` es del emisor: no es un respaldo para canje. No confundas la firma cruda del directorio con las firmas DER del servicio. Si falta material **público** auténtico de producción, integra el flujo pero déjalo cerrado con un motivo legible. No generes raíces sustitutas ni empaquetes un bootstrap de pruebas en release.
2. **Solicitud y reintento.** Usa la clave P-256 persistente de la instalación. Normaliza el código con el contrato existente. `POST /v1/redeem` firma `v, appId, code, deviceKey, requestId, requestedAt, nonce`; `POST /v1/status` usa `licenseId` en vez de `code` y otro dominio de firma. Reutiliza la serialización canónica de GL, no el texto JSON. Si se pierde una respuesta, conserva el `requestId` de esa operación y la misma identidad; no dupliques días ni recalcules el vencimiento en el cliente.
3. **Hora UTC, también para estado.** Ante `CLOCK_SKEW`, lee `error.serverTime` de la respuesta HTTPS del servicio; como alternativa, `GET /v1/time` devuelve la hora en **`now`**. Valida origen y formato. Usa esa referencia temporal para construir **y firmar** el siguiente `requestedAt` UTC con milisegundos, nonce nuevo y mismo `requestId`. Haz como máximo un reintento automático; si falla, muestra el problema y conserva el estado previo. No basta con actualizar la marca de agua y volver a firmar con el reloj local. No cambies la hora del sistema, no codifiques un ajuste para Cuba y no relajes la protección offline contra retrocesos.
4. **Verificación antes del guardado.** Comprueba versión, algoritmo, `kid`, firma sobre los bytes del contrato, `appId`, identidad y coherencia de campos. La huella local es Base64url sin relleno de SHA-256 del SPKI DER. Si hay `devicePublicKey`, su huella debe coincidir. Para estado verifica también el `licenseId` esperado y `checkedAt`; no sobrescribas un estado más reciente con uno antiguo. Admite explícitamente `MENSUAL`, `SEMESTRAL`, `ANUAL`, `PERPETUA`. No transformes un tipo desconocido en mensual ni una fecha inválida en «ahora». Interpreta las fechas **después** de verificar los valores exactos firmados.
5. **Estado durable.** Guarda el sobre verificable de origen `CANJE`, no el código. No muestres éxito si falla el guardado. Revalida al arrancar; sin red conserva la licencia verificable dentro de su vigencia. Un estado firmado `REVOCADA` o `VENCIDA` impide usar **esa** licencia, incluso después de reiniciar; una respuesta ilegible o no firmada no la revoca. Aplica la regla de dominancia existente: una renovación válida puede ampliar el vencimiento; una respuesta anterior no degrada el derecho vigente. Evalúa `CANJE` y `SPVI2` por separado y usa el derecho válido de mayor duración (perpetua prevalece).

## 3. DShalev: primera entrega funcional

- **Configuración:** revisa `Web-Desktop/dshalev/licencia/config_licencia.py` y los consumidores de `KIDS_FIRMA`. Conserva la configuración pública válida existente; no supongas que los ajustes locales del propietario están en Git. Usa la base prevista y toma las claves de canje del directorio verificado.
- **Cliente:** en `cliente_gl.py`, `identidad.py`, `almacen.py` y el verificador existente, reutiliza la identidad DPAPI de la misma cuenta Windows (`DShalevData/licencia/identidad.bin`) para canje y estado. No borres ni regeneres esa identidad durante una actualización. Integra la recuperación UTC del paso 2 y evita envíos concurrentes que compitan por el mismo almacén.
- **Panel:** sigue la ruta `/licencia` en `Web-Desktop/dshalev/app.py` y `templates/license.html`. Conecta «Canjear / Renovar» y «Consultar estado» a acciones POST locales, con sesión autorizada y la protección anti-CSRF del proyecto. No publiques estas acciones en el listener LAN ni envíes la clave privada al navegador. Habilita el formulario solo con configuración e identidad válidas; muestra vencimiento, última consulta, huella copiable para transferencia y errores en español. Quitar `disabled` sin conectar el backend **no** completa la tarea.
- **Resultado esperado:** el panel invoca el cliente persistente; una respuesta válida actualiza el estado visible solo tras guardarse. No toca la base del negocio ni ofrece secundarias comerciales.

## 4. Android e iOS: cerrar las diferencias con escritorio

| Plataforma y puntos de entrada | Cambios específicos |
|---|---|
| **Android:** `Android/licencia/src/main/kotlin/cu/shalev/licencia/{redeem/RedeemClient.kt,redeem/RedeemContract.kt,TrustDirectory.kt,LicenseManager.kt}`; `Android/app/src/main/kotlin/cu/shalev/app/licencia/LicenciaForm.kt` | Sustituye en el camino de canje la selección `signKeysFor(...)` / `LicenseTrust.signSpkis()` por el resolvedor de `canje`. Conecta configuración de build, identidad Keystore, cliente, ViewModel y pantalla real. Que `consultarHora` influya en la petición firmada. Persiste el sobre y el resultado firmado de estado mediante el gestor existente. Mantén `FLAG_SECURE` y no añadas permisos. |
| **iOS:** `iOS/Sources/Domain/Redeem/{RedeemClient,RedeemContract}.swift`, `iOS/Sources/Domain/TrustDirectory.swift`, `iOS/Sources/Features/LicenseView.swift` | Sustituye `signKeys(at:)` / `GLTrust.signingPublicKeys` en canje. Inyecta endpoints e identidad persistente del Keychain/Secure Enclave desde la composición de la app; no generes una clave por vista. Acepta instantes UTC con fracciones tal como los emite GL. Quita los valores por defecto que ocultan fechas/tipos inválidos y el `try?` que silencia fallos al guardar una activación. |

En ambas pantallas ofrece **«Tengo un código» / «Renovar con código»** sin alterar la entrada `SPVI2:`. Evita dobles envíos, muestra progreso y consulta el estado al abrir la pantalla con una licencia de canje y red disponible. Distingue «no habilitado para esta edición», «sin conexión», «reloj no verificable» y «no se pudo canjear», sin revelar si un código existe. Verifica por inspección que el bootstrap público se empaquete realmente en cada destino: un JSON presente en el árbol no garantiza que esté en el build.

## 5. Secundarias y habilitación posterior (sin operaciones ahora)

Producción tiene `ALLOWED_APPS=DSHALEV` y `MAX_SECUNDARIAS=0`. Respeta una cuota firmada de cero en todas las pantallas y rutas de autorización; no confundas una secundaria con un cliente principal que debe consumir otro código. Preserva los permisos existentes de `SPVI2`. Para primarias de canje con cuota futura, revisa la cadena de verificación y los límites de slot/vencimiento descritos en `IMPLEMENTACION_SHALEV.md` §3.1; no otorgues derechos por una preferencia local ni ofrezcas más cupos de los firmados.

Deja en la documentación una lista **para el propietario, no para ejecutar en Arena**: confirmar material público de release → revisar el cliente DShalev → autorizar por separado el ID móvil listo en el Worker → habilitar esa edición → emitir lotes para ese mismo ID. No reutilizar el piloto `DSHALEV` en móviles. Cambiar cuotas o autorizar marcas derivadas requiere otra decisión explícita.

## 6. Cierre que debe entregar Arena

- Actualiza `docs/IMPLEMENTACION_NOTAS.md`, `LICENSE_CLIENT.md` y la documentación de usuario/historial que corresponda. Distingue lo implementado de lo habilitado en producción. No sustituyas este trabajo por copiar el encargo al repo destino.
- Revisa `git status --short` y `git diff --check`. Traza por plataforma **pantalla → cliente configurado → clave de instalación → verificación de canje/estado → guardado → lectura al reiniciar**. Si falta un eslabón, corrígelo o déjalo como bloqueo concreto; no marques el cliente como terminado.
- Entrega una tabla final con **plataforma, archivos modificados, flujo conectado, verificación realmente efectuada y pendiente del propietario**. Separa los datos públicos faltantes de los fallos de código. Indica expresamente: suites no ejecutadas, compilaciones no realizadas si corresponde, sin canje real, sin cambios en producción.
