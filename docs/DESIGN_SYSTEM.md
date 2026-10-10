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

## Copy

Claves en `res/values/strings.xml`. Tests instrumentados dependen de `contentDescription = "Solicitud cifrada"` en el campo del Generador y de los textos `Generar licencia`, `Registrar`, `Generador`, `Registro`, `Sin licencias`.

## No hay

Tema claro, paleta dinámica Material You, iconos custom, háptica.
