# Motor de licencias para Termux

Motor Python independiente y extensible para operar solicitudes de licencia desde Android sin root. No modifica el código Android de GL. Usa SQLite de la biblioteca estándar y no necesita paquetes Python externos.

> **Estado inicial seguro:** recuperación en revisión manual; no se marca ningún pago como verificado hasta validar y configurar remitente, importe, divisa y referencia SMS; GL se abre mediante Shizuku/rish, pero la generación, el envío y el registro siguen siendo acciones manuales. No se simula ni inventa una licencia.

## Alcance verificado

- El catálogo `app_id → AppProfile` está en `termux_license_engine/profiles.json`. El núcleo no contiene reglas de Shalev, GL ni paquetes de aplicaciones.
- El perfil de Shalev reconoce el mensaje completo generado por la app y exige el código cifrado `SPVIR1:`. Los campos visibles por sí solos nunca se aceptan como solicitud. El motor solo comprueba la presencia/estructura básica del token; GL realiza la validación criptográfica al abrir la solicitud.
- La tarifa sale exclusivamente del perfil: mensual 6 000 + 1 000 por secundaria; semestral 30 000 + 5 000; anual 50 000 + 9 000; perpetua 90 000 + 17 000 CUP.
- **4 500 CUP es solo una referencia de la captura, no una tarifa** y no aparece en la tabla de precios.
- Las recuperaciones se retienen en `recovery_manual`; este motor no las entrega a GL ni altera GL para admitirlas.
- El SMS se contrasta por `address` (no por el nombre de contacto). El perfil real aún no tiene reglas configuradas: ni `scan-sms` ni `review-payment` pueden marcar un pago de Shalev como verificado hasta validar remitente, importe, divisa y referencia.
- No se detectó una API de GL. El adaptador compartido pone solo el token cifrado en el portapapeles y abre `dev.gl.license`. No toca controles de pago, no pulsa **Generar** ni **Registrar**, y no envía WhatsApp/SMS. El operador revisa los datos descifrados y hace esas acciones en GL.

## Arquitectura

```text
mensaje completo → resolver perfil → exigir SPVIR1 → precio del perfil
                                      ↓
Termux SMS → parser por perfil → hash de transacción + coincidencia exacta
                                      ↓
SQLite (solo hashes/estado) → handoff manual a GL → envío/registro manual
```

- `models.py`, `parser.py`, `engine.py` y `storage.py`: núcleo genérico y persistencia idempotente.
- `profiles.json`: catálogo y datos de cada app (tarifas, marcadores, protocolo, reglas SMS opcionales).
- `payments.py`, `adapters.py`: integración Termux y proveedor compartido.
- El SQLite guarda hashes de solicitud/transacción/remitente, precio y estado; **no guarda** mensajes, nombre, carné, teléfono, cuerpo SMS, referencias de transacción en claro ni códigos de licencia. Las referencias de menor entropía se resumen con HMAC y una clave privada hermana (`state.sqlite3.hmac-key`, permisos 0600).
- Si haces un respaldo manual del estado, conserva juntos la base y su archivo `.hmac-key` en almacenamiento privado; sin la clave no se podrán comparar referencias existentes y el motor fallará de forma segura.
- La consola muestra el hash completo de solicitud (necesario para referenciarla), perfil, plan, secundarias, importe esperado y estado; nunca el token ni datos personales. Las notificaciones solo indican el tipo de evento. El cuerpo SMS se procesa en memoria y nunca se escribe en logs.
- Una solicitud repetida conserva su estado. Un handoff incierto bloquea nuevos intentos hasta que el operador compruebe el registro de GL y reconcilie explícitamente.

## Instalación en Android

1. Instala Termux desde F-Droid y los complementos de la misma fuente/firma. En Termux:

   ```sh
   pkg update
   pkg install python termux-api termux-services coreutils
   ```

2. Desde el checkout de este repositorio en el teléfono, ejecuta:

   ```sh
   sh tools/termux_license_engine/install-termux.sh
   ```

3. Inicializa los archivos locales si no existen:

   ```sh
   license-engine init
   license-engine profiles
   ```

   Se crean `~/.config/termux-license-engine/settings.json`, `profiles.json` y `~/.local/share/termux-license-engine/state.sqlite3`. El directorio de datos y la base se restringen al usuario Termux.

4. Para el handoff compartido a GL, instala y autoriza Shizuku y verifica que `rish` funcione en Termux. La configuración por defecto corresponde al build release comprobado (`dev.gl.license`, actividad `dev.gl.license.presentation.MainActivity`). Si usas el build debug, cambia el paquete en `settings.json`; no adivines el nombre instalado.

No hace falta root, `uiautomator2` ni una modificación de GL. Los selectores de interfaz no están validados en un teléfono, por eso la primera versión no automatiza taps.

## Operación manual segura

1. En WhatsApp, copia el **mensaje completo de Shalev**, incluida la línea `SPVIR1:`. En Termux:

   ```sh
   license-engine ingest --clipboard
   license-engine list
   ```

   `ingest` no guarda ni imprime el mensaje original. Si el perfil es ambiguo, proporciona `--app-id` explícitamente; nunca se escoge una app por defecto.

2. La solicitud oficial no incluye el número de transacción del cliente. Mientras falte validar/configurar el remitente y el formato del SMS, puedes revisar la bandeja de entrada, pero el motor **mantendrá el pago sin verificar** y bloqueará el handoff. Después de validar las cuatro reglas del perfil, la revisión manual se inicia con:

   ```sh
   license-engine review-payment <request_id>
   ```

   El comando pide sin eco las referencias del cliente y del SMS, el remitente y el importe. No guarda esas referencias en claro; exige remitente configurado exacto, igualdad de transacciones y tarifa del perfil.

3. Con el estado `payment_verified`, conserva en el portapapeles el mensaje completo original y ejecuta:

   ```sh
   license-engine handoff <request_id> --confirm
   ```

   El handoff comprueba que el mensaje del portapapeles corresponde al mismo hash de solicitud, copia a Android solo el token cifrado y abre GL. Si GL no lo carga automáticamente, pégalo manualmente en el campo de solicitud. Revisa en GL el tipo, las secundarias y el importe descifrados; introduce allí las dos referencias, marca el pago y pulsa **Generar licencia**. GL puede abrir el compositor elegido por el cliente: el envío sigue siendo manual. Regresa a GL y completa el registro manualmente.

4. Tras revisar el registro del proveedor y completar el envío/registro manual:

   ```sh
   license-engine reconcile <request_id> --generated --registry-checked
   license-engine complete <request_id> --sent --registered
   ```

   Si el resultado de GL es incierto, no repitas el handoff. Comprueba primero el registro de GL y luego usa, solo si confirmaste que no se generó:

   ```sh
   license-engine reconcile <request_id> --not-generated --registry-checked
   ```

   Eso permite un nuevo handoff deliberado; el motor nunca reintenta por sí solo.

Las recuperaciones se apartan para revisión manual y no pasan por `handoff`.

## Verificación automática de SMS (desactivada por defecto)

La lectura del inbox usa Termux:API (`termux-sms-list -l 50 -t inbox`) y el campo `address`. El perfil Shalev no tiene `sms_sender_address`, `sms_amount_pattern`, `sms_currency_pattern` ni `sms_transaction_pattern`, y `sms_rules_validated` permanece `false`: por ahora ningún flujo del motor (ni automático ni la aprobación manual) puede marcar esos pagos como verificados.

Solo después de confirmar en el teléfono el remitente exacto, la divisa CUP y la gramática de un SMS real, configura en `~/.config/termux-license-engine/profiles.json` los cuatro campos de Shalev y cambia `sms_rules_validated` a `true`. Las expresiones deben capturar explícitamente los grupos `amount` y `transaction`, y la regla de divisa debe coincidir; no copies cuerpos SMS reales a este repositorio, a tickets ni a ejemplos. Prueba primero con datos sintéticos y vuelve a revisar la salida antes de habilitar el servicio. La propiedad de validación explícita evita que añadir regexes sin revisar active el procesamiento. Una vez configuradas y validadas esas reglas, `review-payment` permite aprobación manual con confirmación explícita y `scan-sms` puede conciliar automáticamente una referencia previamente asociada.

```sh
license-engine scan-sms --limit 50
```

La conciliación automática requiere además asociar primero la referencia que el cliente facilitó por separado (`bind-customer-tx`). Si hay perfiles SMS ambiguos, importe diferente, referencia distinta o formato no reconocido, el pago queda sin verificar/para revisión. Aun cuando un SMS se verifica automáticamente, **el motor nunca genera ni envía la licencia**.

El servicio opcional únicamente observa SMS configurados y notifica estados; no emite licencias:

```sh
sv-enable license-engine
sv up license-engine
# después de editar los perfiles:
sv restart license-engine
```

Si los patrones continúan sin configurar, el servicio queda inactivo respecto al inbox. No lo habilites como sustituto de validar el remitente.

## Añadir otra app

1. Añade un objeto `AppProfile` a `profiles.json` con `app_id` único, marcadores/protocolo verificados, tarifas CUP propias, límites y política de recuperación.
2. Si usa otro formato SMS, añade regexes solo tras validar remitente y formato; si comparte remitente/reglas con otra app, el motor se abstiene ante ambigüedad.
3. El adaptador del proveedor compara la tarifa del perfil con la tabla de precios registrada por GL. No se altera GL; si difieren, el handoff queda bloqueado.
4. Para protocolo desconocido, no habilites emisión: la nueva app necesita pruebas/soporte del proveedor correspondiente.

El perfil `demo_synthetic_nonproduction` es ficticio, no tiene precios ni proveedor y está bloqueado para emisión. No es una licencia de prueba real.

## Pruebas locales

Desde el checkout:

```sh
PYTHONPATH=tools/termux_license_engine python -m unittest discover -s tools/termux_license_engine/tests -v
```

Las pruebas usan perfiles y valores de SMS inventados y no contienen datos reales de clientes. No requieren `pytest` ni dependencias externas.
