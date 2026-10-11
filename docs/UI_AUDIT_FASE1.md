# Auditoría UI / UX / IX — estado actual del código

Alcance: Compose Material 3, modo oscuro, Generador / Registro / Detalle.  
Fuera de alcance: cripto, permisos, Hilt, Room, contratos.

## Qué ya mejoró

Componentes (`GlPrimaryButton`, `GlSecondaryButton`, banners, `EmptyState`, `CountdownText`, `MetaRow`, `ScreenTitle`), checkbox de pago a 52 dp, detalle con filas fusionadas, chip de "solicitud detectada del portapapeles" (`AnimatedVisibility` + `liveRegion`).

## Hallazgos abiertos

| ID | Dónde | Problema | Impacto | P |
|----|--------|----------|---------|---|
| F1-01 | Theme | Sin `LocalAbsoluteElevation` ni ripple token documentado | Consistencia | P1 |
| F1-02 | Strings | Etiquetas reutilizadas y anuncios críticos ya viven en `strings.xml`; quedan literales internos no visibles | A11y | Mitigado |
| F1-03 | Generador | El panel de solicitud válida aparece dentro de `AnimatedVisibility`; sin esqueleto de carga mientras `ValidarSolicitudUseCase` descifra | Claridad | P1 |
| F1-05 | Generador | El chip anuncia con `liveRegion`, pero el campo se rellena sin cambio de foco ni mensaje de error si el descifrado falla | IX | P1 |
| F1-06 | Generador | CI y teléfono visibles en claro tras validar — necesario para el emisor; `FLAG_SECURE` cubre recents | Confianza | OK |
| F1-07 | Registro | Un único ticker local se pausa si la pestaña no está visible o la lista está vacía | Rendimiento | Resuelto |
| F1-08 | Registro | La lista comparte el ticker; no hay una corrutina por fila | Rendimiento | Resuelto |
| F1-09 | Detalle | `MetaRow` fusiona etiqueta/valor para TalkBack y permite valores largos con fuente grande | A11y | Resuelto |
| F1-10 | Nav | Detalle conserva TopAppBar con título y navegación Up; pestañas raíz visibles | Consistencia | Resuelto |
| F1-11 | Motion | Fade de banners/panel y navegación; transiciones complejas no son necesarias para este flujo operativo | IX | Mitigado |
| F1-12 | Contraste | Oro `#E0B13A` sobre `#0B0F14` ~ AA para texto grande; cuerpo usa `#F2F4F7` | A11y | OK |
| F1-13 | Filtro lista | No hay búsqueda | Eficiencia | P2 no hacer (negocio) |
| F1-14 | Háptica | No hay | IX | P2 no hacer (permisos) |

## Flujos (IX)

1. **Arranque:** directo al Generador. Ya no hay pantalla de lock ni PIN; se retiró el módulo completo.
2. **Generador:** pegar → validar → panel → pago → generar → share → volver → registrar. El paso "vuelve del envío" depende de `onReturnedFromShare()` vía `ON_RESUME`.
3. **Registro:** import/export SAF, lista, detalle. Vacío cubierto con `EmptyState`.

## Pendiente por el cambio de módulo

- La pantalla de Lock y sus hallazgos (loading real, fortaleza de PIN visual, biometría automática) **ya no aplican**: el código se eliminó.
- Los tests instrumentados ya no dependen de copy de PIN. Ahora se anclan a `contentDescription = "Solicitud cifrada"`, `Generar licencia`, `Registrar`, `Generador`, `Registro`, `Sin licencias`.

## Aplicación UI / UX / IX Pro Max

La revisión posterior prioriza el recorrido operativo —no añade funciones de negocio— y deja estos cambios verificables en código:

- Navegación raíz con etiquetas visibles, restauración de estado y sin back stack duplicado.
- Encabezados y pasos consistentes para que el flujo de emisión se pueda escanear de arriba abajo.
- Tarjetas con borde sutil, feedback de éxito/error anunciado y filas de metadatos que no truncarán valores largos ni con fuente grande.
- Registro con fecha local legible (`America/Havana`), jerarquía de estado/cuenta atrás y ticker suspendido si la pestaña no está visible o está vacía.
- Detalle con estados de carga/ausencia y secciones de identidad/licencia; claves públicas seleccionables para no forzar transcripción manual.
- Maqueta `preview/gl-ui.html` actualizada: sin la pantalla de PIN retirada y con las cuatro pestañas reales.

Pendiente deliberado: añadir una señal de progreso durante el descifrado de solicitudes requeriría mover el acceso a Keystore fuera del hilo principal con un dispatcher inyectable; no se hace aquí para no alterar el contrato ni los tests síncronos del ViewModel.

## Verificado en dispositivo (API 35)

Arranque al Generador, validación de envelope inválido en vivo (`La solicitud no es válida.`), navegación a Registro, roundtrip export→import `.glreg` con toast de éxito.
