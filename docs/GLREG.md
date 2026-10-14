# Formato `.glreg` v2 (importa v1)

Copia cifrada y firmada del registro de licencias. MIME sugerido: `application/octet-stream`. Extensión: `.glreg`.

Se escribe y lee solo con **Storage Access Framework** (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`). No hay permiso `READ/WRITE_EXTERNAL_STORAGE`.

## Layout binario

El layout de v1 se conserva. Las exportaciones nuevas usan el byte de versión `2`; se siguen importando archivos legacy con versión `1`.

```text
offset  size  campo
0       4     magia ASCII "GLRG"
4       1     versión de formato (2 al exportar; se acepta 1 al importar)
5       12    IV AES-GCM
17      16    tag GCM
33      4     ctLen (uint32 BE)
37      ctLen ciphertext
37+ct   4     sigLen (uint32 BE)
…       sigLen firma ECDSA P-256 SHA-256 DER
```

- **v1 legacy:** AAD UTF-8 `glreg-v1`; la firma cubre `iv || ct || tag`.
- **v2 actual:** AAD UTF-8 `glreg-v2`; la firma cubre `"GLRG" || 0x02 || iv || ct || tag`.
- Cifrado: AES-256-GCM.
- Clave AES: master Keystore (`gl.master.aes.v1`) del dispositivo emisor.
- Firma: clave privada Keystore `gl.sign.ec.p256.v1`.

El byte de formato de `.glreg` es independiente de `CONTRACT_VERSION` de los envelopes/licencias v1; no cambia su AAD ni su interoperabilidad.

## Payload JSON

Las exportaciones v2 contienen UTF-8 JSON con una lista de entradas `{ "license": <Licencia>, "precioCobrado": <Int|null> }`. El precio se conserva fuera del JSON de licencia firmado. El importador sigue aceptando archivos v1 cuyo payload era una lista directa de `Licencia`; en esos archivos el precio histórico no existe y queda `null` salvo que haya un valor local que preservar.

## Importación

1. Comprobar magia y versión (`1` o `2`).
2. Verificar ECDSA con la **pública local**, usando el transcript correspondiente a la versión.
3. Abrir GCM con el AAD de esa versión.
4. Parsear JSON legacy o actual.
5. Validar cada licencia (campos, fechas, perpetua sin `venceEn`).
6. Merge por `id`; se conserva `issuedAtIso` ≥ existente y un payload antiguo sin `precioCobrado` no borra el valor local ya guardado.

El archivo tiene un máximo de 32 MiB y 10 000 entradas; la lectura SAF se detiene al superar el máximo. `ctLen`/`sigLen` se comprueban contra el tamaño restante y se rechazan bytes sobrantes. Cualquier fallo → error genérico. No se escribe basura a medias de un archivo inválido más allá de lo que Room ya tuviera.

## Límites

- No es portable entre dispositivos: usa las claves Keystore locales.
- No es un formato inter-app de terceros.
- No está compactado.
- Futuros cambios incompatibles en el paquete deben incrementar su byte de formato, manteniendo el importador legacy cuando sea viable; no requieren cambiar el contrato de envelopes/licencias v1.
