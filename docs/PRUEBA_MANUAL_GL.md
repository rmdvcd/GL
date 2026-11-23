# Prueba manual en el teléfono emisor (GL)

Guion para ejecutar **a mano** en un dispositivo real. El agente no instala ni
teclea datos personales: esto lo corre el propietario.

## Preparación

1. Instala el APK de esta rama (o el que se publique desde ella):

   ```powershell
   adb install -r "app\build\outputs\apk\debug\app-debug.apk"
   ```

   > `pm clear` **regenera las claves del Keystore** y cambia las huellas que
   > haya fijado un cliente. No lo uses si el cliente ya confía en este
   > teléfono. Si lo haces, exporta las claves nuevas desde la pestaña **Claves**
   > **después** del último `pm clear`.

2. Confirma que arranca sin crash:

   ```powershell
   adb logcat -c
   adb shell am start -n dev.gl.license.debug/dev.gl.license.presentation.MainActivity
   Start-Sleep -Seconds 8
   adb shell pidof dev.gl.license.debug   # debe devolver un PID
   adb logcat -d -b crash                # debe estar vacío
   ```

## 1. Solicitud de prueba y precarga de transferencia

1. Genera una solicitud desde un cliente Shalev de prueba (o pide una al
   propietario del cliente). Debe incluir `"tx"` con un número inventado, por
   ejemplo `TX-PRUEBA-01`.
2. En GL → **Generador**, pega la solicitud en **Solicitud cifrada**.
3. Esperado:
   - Aparece **Transferencia declarada: TX-PRUEBA-01**.
   - El campo de transacción del operador queda precargado con ese número.
   - El aviso dice **Transferencia libre en el registro local.**
4. Completa los dos números de transacción y genera la licencia.
5. Esperado: se emite y el mensaje se ofrece para enviar.

## 2. Duplicado de transferencia

1. Vuelve a pegar la **misma** solicitud (o una nueva con el mismo `tx`).
2. Esperado: el aviso cambia a **Transferencia ya usada por la licencia …** con
   el ID de la licencia que la reservó.
3. Intenta emitir igualmente: debe rechazar por transacción duplicada.
4. En **Registro → detalle** de esa licencia, libera la reserva si quieres
   reutilizar el número en la prueba.

## 3. Reenviar mensaje

1. Con una licencia SPVI2 recién emitida, toca **Reenviar mensaje**.
2. Esperado: se abre el mismo canal de entrega con el mensaje reconstruido; no
   se emite una licencia nueva.

## 4. Exportar lista de revocadas

1. Marca una licencia como revocada (si el flujo lo permite) o usa una ya
   revocada.
2. En **Registro**, toca **Exportar lista de revocadas** y guarda
   `revocadas.json` con el selector de archivos.
3. Esperado: el fichero existe, es JSON con `datos` y `firma`, y no contiene
   datos personales.

## 5. Pantalla Lotes

1. Abre **Lotes**.
2. En **Aplicación** elige `SHALEV-ANDROID` y confirma que aparece el aviso
   **Este código solo sirve en la app elegida**.
3. Cantidad `2`, tipo `MENSUAL`, pulsa **Crear lote**.
4. Esperado: aparecen dos códigos `LIBRE` en el inventario y el lote queda
   seleccionado.
5. Pulsa **Exportar lote firmado** y guarda el JSON.
6. En una fila `LIBRE`, escribe una nota (p. ej. `prueba`) y pulsa
   **Marcar entregado**.
7. Esperado: la fila pasa a `ENTREGADO` con la nota. El contador
   **Libres / Entregados / Total** se actualiza.
8. Pulsa **Volver a copiar código** en la fila entregada: debe pedir
   confirmación antes de copiar.

## 6. Entrega directa a WhatsApp/SMS

1. Desde **Generador** o desde el detalle, elige **Enviar por WhatsApp** o
   **Enviar por SMS**.
2. Esperado: se abre la app externa con un borrador dirigido al número
   normalizado (`5xxxxxxx` → `+535xxxxxxx`). GL **no** envía en segundo plano.
3. Si el número es ambiguo, el cuerpo queda disponible para **Copiar mensaje**.

## 7. Contar partes del SMS

1. Envía la respuesta SPVI2 a tu propio número.
2. Cuenta los segmentos que muestra el teléfono o el operador.
3. Esperado: **3 segmentos GSM-7** (el código SPVI2 mide 210 caracteres y el
   texto de acompañamiento usa solo el alfabeto GSM-7 sin tabla extendida).
4. Si salen 4 o más, o aparecen caracteres raros (tildes convertidas, `€`,
   comillas tipográficas), anótalo: sería una regresión de `OperatorMessages`.

## Registro de resultados

| Paso | Esperado | Obtenido | Fecha |
|---|---|---|---|
| 1 | Precarga y aviso «libre» | | |
| 2 | Aviso «ya usada» y rechazo | | |
| 3 | Reenvío sin reemisión | | |
| 4 | `revocadas.json` exportado | | |
| 5 | Lote, entrega y recopia | | |
| 6 | Borrador en app externa | | |
| 7 | 3 segmentos GSM-7 | | |
