# Notas de implementación

Baseline Node 22.22.3: 107 pasan, 0 fallan. Las 7 regresiones de cascada y concurrencia fallaron sobre el código original.
Fase 1: npm test — 117 pasan, 0 fallan (cascadas en memoria/D1, concurrencia 20 veces, binding, reloj y SQL de operador). No se probó Cloudflare real.

3.1: el ejemplo que pide emitir después de corregir tres días contradice el límite explícito de 24 h. Se bloquea a tres días y se prueba la monotonía para un desfase menor.
3.2: SPVI ya pasa y persiste txNumber en este commit base. Se conserva esa ruta; lo nuevo es reservar antes de compartir. Liberar un pago no libera el nonce ni invalida criptográficamente una licencia entregada.
