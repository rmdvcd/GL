# Formato `.glreg` v1

Copia cifrada y firmada del registro de licencias. MIME sugerido: `application/octet-stream`. Extensión: `.glreg`.

Se escribe y lee solo con **Storage Access Framework** (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`). No hay permiso `READ/WRITE_EXTERNAL_STORAGE`.

## Layout binario (big-endian)

```
offset  size  campo
0       4     magia ASCII "GLRG"
4       1     versión de contrato (1)
5       12    IV AES-GCM
17      16    tag GCM
33      4     ctLen (uint32 BE)
37      ctLen ciphertext
37+ct   4     sigLen (uint32 BE)
…       sigLen ECDSA P-256 SHA-256 DER de (iv || ct || tag)
```

- Cifrado: AES-256-GCM, AAD = UTF-8 `glreg-v1`.
- Clave AES: master Keystore (`gl.master.aes.v1`) del dispositivo emisor.
- Firma: clave privada Keystore `gl.sign.ec.p256.v1`.

## Plaintext

UTF-8 JSON: array kotlinx.serialization de `Licencia` (mismos `@SerialName` que el contrato: `v`, `id`, `nombre`, `apellidos`, `ci`, `via`, `telefono`, `deviceId`, `appName`, `tipo`, `solicitadaEn`, `emitidaEn`, `venceEn`, `estado`, `nonce`).

## Importación

1. Magia y versión.
2. Verificar ECDSA con la **pública local**.
3. Abrir GCM.
4. Parsear JSON.
5. Validar cada licencia (campos, fechas, perpetua sin `venceEn`).
6. Merge por `id`; se conserva `issuedAtIso` ≥ existente.

Cualquier fallo → error genérico. No se escribe basura a medias de un archivo inválido más allá de lo que Room ya tuviera.

## No es

- Un formato inter-dispositivo.
- Un formato inter-app de terceros.
- Compactado ni versionado más allá del byte `v=1`.

Cambio de AAD, magia o curva ⇒ subir versión y rechazar v1.
