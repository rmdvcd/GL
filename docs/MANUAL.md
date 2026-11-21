# Manual rápido — GL

GL sirve para **generar y anotar** licencias de tus propias apps. Todo queda en este teléfono.

## Primer uso

Abre GL. No hay PIN ni huella: la app entra directamente al **Generador**.

> **Ojo:** el registro no está protegido por una credencial. Cualquiera que tenga el teléfono desbloqueado puede ver y exportar tus licencias. Trátalo como un teléfono que no sueltas.

## Día a día

- No hay bloqueo por inactividad ni al cambiar de app.
- Los datos viven cifrados en SQLCipher, pero la app los descifra mientras está abierta.

## Generar una licencia

1. En tu app cliente, el usuario pide licencia; copias el texto cifrado (JSON envelope).
2. Abre GL → **Generador** (en primer plano).
3. El texto se pega solo si está en el portapapeles y parece válido. Si no, pégalo.
4. Revisa nombre, CI, vía, teléfono, dispositivo, tipo y fecha.
5. Marca **Pago realizado** solo cuando el cobro es real.
6. **Generar licencia** abre WhatsApp o SMS con el teléfono de la solicitud y el envelope de licencia.
7. Vuelve a GL y pulsa **Registrar**. Si no volviste del envío, el botón sigue apagado.

## Registro

- Lista: más reciente arriba. Cuenta atrás cada segundo. Las perpetuas dicen **Perpetua**.
- Toca una fila para ver todos los campos.
- **Exportar** crea `registro.glreg` donde el sistema te deje elegir (Drive, carpeta, etc.).
- **Importar** abre un `.glreg` **de este mismo teléfono**. Fusiona por id; gana la emisión más nueva. Un archivo de otro aparato **falla**.

## Qué no hace GL

No cobra, no habla con internet propio, no sincroniza la nube, no pide contraseña, no revoca en los clientes a distancia.

### Buscar una entrega en Lotes

En **Lotes → Inventario guardado**, selecciona el lote por aplicación, fecha e ID. Los contadores muestran el total del lote, no solo las filas filtradas. Selecciona **ENTREGADO** para buscar por nombre o número de transacción anotado al entregar; también puedes buscar por la terminación visible del código. Para reenviar uno ya entregado, comprueba la nota del destinatario en la confirmación antes de copiarlo. Volver a copiar no cambia su estado ni genera un código nuevo.

**LIBRE / ENTREGADO son estados locales de reparto**, no una consulta de activación al Worker. Marcar una entrega no acredita que el cliente haya canjeado el código.

### Enviar sin volver a generar la licencia

- **Código de canje:** en una fila **LIBRE** de Lotes, pulsa **Mensaje para el cliente**. El mensaje lleva el código agrupado y el tipo del lote. Elige Copiar, Compartir, WhatsApp o SMS. Comprueba el destinatario y el envío en la otra app. Al volver, anota el nombre y nº de transferencia y pulsa **Marcar entregado**. Abrir el borrador no cambia el inventario; ENTREGADO tampoco significa que el cliente haya canjeado.
- **Respuesta a una solicitud:** Generator prepara el borrador para el teléfono de la solicitud. Si no se abre el canal, usa **Copiar** o **Compartir** en el mensaje generado. Para reenviar una licencia SPVI2 no revocada, entra en su detalle del Registro y elige el canal; no hace falta emitir otra licencia. El detalle v1 no conserva el sobre original para reenviarlo.
- **Portapapeles:** copia solo si lo necesitas. GL marca la copia como sensible e intenta borrarla a los 60 segundos si sigue siendo la misma; si Android impide el borrado en segundo plano, bórrala manualmente.
