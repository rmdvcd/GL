# Fase 2 — Design system GL

Oscuro forzado. Material 3. Sin librerías extra.

## Color

| Token | Hex | Uso |
|-------|-----|-----|
| background | `#0B0F14` | Canvas |
| surface | `#141A22` | Tarjetas |
| surfaceVariant | `#1C2530` | Elevación |
| onSurface | `#F2F4F7` | Texto (≥ AA sobre surface) |
| onSurfaceVariant | `#9AA4B2` | Secundario |
| primary | `#E0B13A` | Acciones, títulos (texto grande) |
| onPrimary | `#1A1404` | Texto sobre oro |
| error | `#FF8A80` | Errores |
| ok | `#5EE2A6` | Éxito (no M3 scheme; `GlColors.ok`) |

## Tipo

Sans por defecto del sistema (respeta fuente grande).  
Headline 28/34, Title 22/28 y 18/24, Body 16/24 y 14/20, Label 14/18.

## Espacio y táctil

`screen 20`, `gap 12`, **touch 52**, campo solicitud `140`.  
Radio: extraSmall 8, small 12 (campos), medium 16 (cards), large 20.

## Motion

150 / 220 / 300 ms, `FastOutSlowIn`. Banners: in 220, out 150.  
Sin transiciones de navegación aún (fase 6).

## Componentes

`GlPrimaryButton` / `GlSecondaryButton` (min 52, loading).  
`StatusBanner`, `GlCard` → `shapes.medium`, `EmptyState`, `CountdownText`, `MetaRow`, `ScreenTitle` (heading).

### Aplicación UI / UX / IX Pro Max

- **Jerarquía:** `ScreenHeader` aporta título y contexto; `SectionLabel` divide flujos largos en pasos claros. El generador siempre comunica «Solicitud → Confirmar pago → Respuesta».
- **Acciones:** una única acción primaria por bloque; las acciones auxiliares (pegar, añadir, registrar, copiar) usan el estilo secundario. No se oculta el estado deshabilitado que explica el siguiente paso.
- **Lectura y accesibilidad:** `MetaRow` reserva espacio al valor, permite saltos de línea con fuente grande y fusiona etiqueta + valor para TalkBack. Las alertas de éxito y error son regiones vivas educadas.
- **Navegación:** las cuatro pestañas mantienen icono **y** etiqueta. El cambio de pestaña restaura el estado y evita apilar destinos raíz.
- **Datos sensibles y largos:** huellas y claves usan monoespaciada, superficie secundaria y selección nativa; el contenido sigue protegido por `FLAG_SECURE`.
- **Rendimiento:** Registro usa un único ticker que solo corre cuando la pestaña está visible y hay licencias.

## Copy

Claves en `res/values/strings.xml`. Tests instrumentados dependen de `contentDescription = "Solicitud cifrada"` en el campo del Generador y de los textos `Generar licencia`, `Registrar`, `Generador`, `Registro`, `Sin licencias`.

## No hay

Tema claro, paleta dinámica Material You, iconos custom, háptica.
