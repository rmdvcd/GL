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
