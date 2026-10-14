# Informe de revisión — GL

**Actualización:** 2026-10-14. Esta nota resume las correcciones estáticas preparadas en la rama de auditoría. No sustituye una revisión de release ni acredita que el código compile.

## Estado de verificación de esta rama

| Ítem | Estado |
|---|---|
| `:app:assembleDebug` / `:app:assembleRelease` | No ejecutados: faltan Java, Gradle y Android SDK en esta sesión |
| `:app:testDebugUnitTest` | No ejecutado; el árbol contiene 167 métodos `@Test` unitarios según conteo estático |
| `:app:connectedDebugAndroidTest` | No ejecutado; `LicenseGenerationE2ETest` permanece en `@Ignore` |
| Lint / release minificado / firma | No ejecutados |
| Compatibilidad ECDH de Keystore en API 26–30 | Pendiente; no se elevó `minSdk` ni se afirma un fallback seguro |

## Correcciones aplicadas estáticamente

- El helper de solicitudes v1 usa el mismo IV para HKDF y AES-GCM, exige `devicePub` y valida EC P-256. Se añadió un round-trip JVM. El fixture E2E ahora incluye `devicePub` y transacciones coincidentes, pero continúa ignorado.
- `MASTER_KEY_CODE` se define en `BuildConfig` solo para debug; release lo recibe vacío. La tarjeta se oculta fuera de debug y `MasterKey.isMasterKey` falla cerrado por defecto.
- La frescura v1 y SPVI usa una ventana exacta de hasta 72 horas de antigüedad y 2 horas de adelanto. Un fallo al leer/parsear el reloj ya no se convierte en una omisión de esa validación.
- SPVI valida la solicitud antes de emitir, limita entrada/código y conserva `txNumber`. La consulta de duplicados es suspendida, case-insensitive y fail-closed; no se hace `runBlocking`.
- `.glreg` exporta formato local v2 con AAD versionada, limita el paquete a 32 MiB y 10 000 entradas, lee el archivo con límite, comprueba longitudes y bytes sobrantes y conserva `precioCobrado` fuera del JSON de licencia. El importador acepta paquetes v1 antiguos y no borra un precio local existente cuando falta el metadato.
- Las validaciones asíncronas descartan resultados obsoletos; se bloquean taps de generación/registro duplicados. Los errores de lectura del historial ya no se interpretan como historial vacío.
- `devicePub` es obligatorio en cifrado v1 y se valida como EC P-256. Se retiraron biometría/PIN residual, permisos asociados y dependencias directas sin uso. La copia individual de claves etiqueta correctamente la clave de firma.
- La documentación del AAD v1 se alineó con `CryptoEngine.canonical()` y el ejemplo de cliente requiere un `expectedLicenseId` externo que compara con el ID descifrado.

## Riesgos o decisiones aún pendientes

1. **Replay:** el nonce no se consume ni se guarda. La ventana temporal no impide reusar la solicitud dentro de ella; el reloj de pared puede retroceder.
2. **Deduplicación de pagos no atómica:** `txNumber` se comprueba contra licencias ya guardadas, pero no hay reserva transaccional ni índice único. El registro ocurre después de compartir la licencia y sigue existiendo una ventana de carrera. La coincidencia de entrada cliente/SMS es manual, no una verificación con una pasarela.
3. **`devicePub`:** GL cifra hacia la clave pública suministrada, pero no prueba posesión, attestation de hardware ni vínculo con `deviceId`.
4. **Android 26–30:** la política ECDH de Keystore no tiene una compatibilidad/fallback validado en esta sesión. Mantener `minSdk = 26` hasta decisión y pruebas explícitas.
5. **Sin gate de sesión:** quien tenga el dispositivo desbloqueado puede acceder al registro y a la pestaña Claves. SQLCipher y `FLAG_SECURE` no sustituyen autenticación.
6. **E2E y release:** falta validar el happy path con Keystore/Room en un runner Android compatible y un build release minificado/firmado reproducible.
7. **Deuda de datos:** `EvaluarSolicitudHistorialUseCase` todavía carga todas las licencias y filtra en memoria; `txNumber` no tiene índice, así que la consulta puede crecer con el registro.
8. **Claves públicas de referencia:** son por instalación y rotan al borrar datos/reinstalar. Verifica huellas en la pestaña Claves antes de fijarlas; no uses snapshots antiguos como claves universales.

## Historial

`AUDIT_2026-10-14.md` conserva los hallazgos de baseline y un estado de remediación separado. `docs/TESTING.md` documenta comandos, conteos estáticos y las limitaciones de verificación. Los resultados previos de otros entornos son históricos y no se presentan como pruebas de esta rama.
