# Anexo A — Directorio de confianza v1 (idéntico en ambos repos)

Archivo `directorio.json`, publicado como *asset* de la Release `revocaciones` del repositorio de Shalev:

```json
{ "datos": "<JSON como texto>", "firma": "<Base64url r‖s>", "raiz": "A" }
```

- `firma`: ECDSA P-256/SHA-256, formato crudo `r‖s` (64 B), de una **clave raíz** sobre `UTF-8("SPVI-DIR1|") ‖ UTF-8(datos)`. Se firma el texto exacto de `datos` (sin canonicalización JSON). `raiz` (`"A"`/`"B"`) es informativo; el cliente puede probar ambas.
- `datos`:

```json
{"v":1,"seq":7,"emitidaEn":1790000000,
 "claves":[{"kid":"gl-sign-v1","uso":"firma","spki":"<Base64 estándar SPKI P-256>",
            "huella":"sha256:xxxx:…","desde":1790000000,"hasta":null,"estado":"vigente"}]}
```

Validación del cliente: tamaño ≤ 64 KiB; `v == 1`; `seq` **estrictamente mayor** que el guardado (anti-retroceso; mismo `seq` con contenido idéntico es idempotente); cada `spki` es P-256 y `huella == SHA-256(DER)` en hex agrupado; a lo sumo una clave `ecdh` con `estado: "vigente"`; campos desconocidos se ignoran.

Semántica:
- `uso:"firma"`: valida licencias y listas con `emitidaEn` en `[desde, hasta]` (`hasta:null` = sin límite). `estado:"comprometida"` ⇒ se rechaza todo lo firmado por esa clave.
- `uso:"ecdh"`: la `vigente` cifra solicitudes nuevas; las `retirada` no se usan para cifrar.
- `uso:"canje"`: clave pública del servicio (`kid` p. ej. `gl-redeem-v1`).
- **Raíces:** dos claves públicas (A y B) fijadas en el build. **Bootstrap:** el build embebe un `directorio-bootstrap.json` firmado; en cada arranque se re-verifica y se usa el de mayor `seq` entre bootstrap y caché descargada.

Límites: no protege contra el robo de una raíz con su passphrase; una clave retirada sigue válida para licencias con `emitidaEn` dentro de su ventana (un atacante con esa clave podría fechar una licencia dentro de ella: se mitiga marcándola `comprometida`).
