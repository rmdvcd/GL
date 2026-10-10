# Auditoría UI / UX / IX — estado actual del código

Alcance: Compose Material 3, modo oscuro, Generador / Registro / Detalle.  
Fuera de alcance: cripto, permisos, Hilt, Room, contratos.

## Qué ya mejoró

Componentes (`GlPrimaryButton`, `GlSecondaryButton`, banners, `EmptyState`, `CountdownText`, `MetaRow`, `ScreenTitle`), checkbox de pago a 52 dp, detalle con filas fusionadas, chip de "solicitud detectada del portapapeles" (`AnimatedVisibility` + `liveRegion`).

## Hallazgos abiertos

| ID | Dónde | Problema | Impacto | P |
|----|--------|----------|---------|---|
| F1-01 | Theme | Sin `LocalAbsoluteElevation` ni ripple token documentado | Consistencia | P1 |
| F1-02 | Strings | Literales sueltos en Compose (`contentDescription`, copy del chip) mezclados con `strings.xml` | A11y | P1 |
| F1-03 | Generador | El panel de solicitud válida aparece dentro de `AnimatedVisibility`; sin esqueleto de carga mientras `ValidarSolicitudUseCase` descifra | Claridad | P1 |
| F1-05 | Generador | El chip anuncia con `liveRegion`, pero el campo se rellena sin cambio de foco ni mensaje de error si el descifrado falla | IX | P1 |
| F1-06 | Generador | CI y teléfono visibles en claro tras validar — necesario para el emisor; `FLAG_SECURE` cubre recents | Confianza | OK |
| F1-07 | Registro | `ui.now` del VM sigue ticando aunque la lista no lo usa → trabajo inútil | Rendimiento | P1 |
| F1-08 | Registro | N filas = N corrutinas de countdown | Rendimiento | P1 |
| F1-09 | Detalle | Sin `contentDescription` por campo; nonce largo difícil en fuente grande | A11y | P1 |
| F1-10 | Nav | Detalle oculta bottom bar (bien) pero sin título en TopAppBar ni Up estándar | Consistencia | P2 |
| F1-11 | Motion | Solo fade de banners/panel; sin transiciones Nav | IX | P2 |
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

## Verificado en dispositivo (API 35)

Arranque al Generador, validación de envelope inválido en vivo (`La solicitud no es válida.`), navegación a Registro, roundtrip export→import `.glreg` con toast de éxito.
