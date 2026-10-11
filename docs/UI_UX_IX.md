# UI / UX / IX — GL

## Fases 1–2: auditoría y design system

Ver `docs/UI_AUDIT_FASE1.md` y `docs/DESIGN_SYSTEM.md`. Tokens oscuros, `GlShapes`, `GlMotion` 150/220/300.

## Fase 3: retirada

La fase 3 (pantalla de bloqueo) se **revirtió**: el módulo de autenticación completo —PIN, biometría, `LockScreen`, `LockViewModel`, `SessionRules`, timeout de idle— se eliminó del código. La app abre directo al Generador. No hay copy de PIN ni `contentDescription` de PIN.

## Fase 4: Generador

Chip de portapapeles con `liveRegion` solo en primer plano y con foco de ventana. `clipboardCaptured` no es cripto. Campo con `contentDescription = "Solicitud cifrada"`, banner de error en vivo, panel de solicitud válida, checkbox de pago toggleable.

## Fase 5: Registro + Detalle

Contador local, `EmptyState`, TopAppBar atrás. Countdown en badge, solo con la pestaña visible.

## Fase 6: A11y / rendimiento

Ticker local de lista, `MetaRow` merge, banners `polite`, `heightIn`, fade en `AnimatedVisibility`.

## Fase 7: Tests

- `GeneratorViewModelTest`: portapapeles ignorado sin foco; flag solo en foreground.
- Instrumentados anclados a copy de **producto**, no de lock: `Solicitud cifrada`, `Generar licencia`, `Registrar`, `Generador`, `Registro`, `Sin licencias`.
- `docs/TESTING.md` y `AGENTS.md` documentan los gotchas de instrumentación.

## Fase 8: UI / UX / IX Pro Max

- Header + texto de contexto y secciones por paso para bajar la carga cognitiva.
- Navegación inferior con icono y etiqueta permanente; destinos raíz restauran estado y no se apilan.
- Una sola acción primaria por bloque; tarjetas, banners y filas responden a fuente grande y TalkBack.
- Registro con fechas `dd/MM/yyyy · HH:mm` en `America/Havana`, estado visible y un único ticker pausado fuera de pantalla o sin elementos.
- Detalle con loading/empty state y bloques de identidad/licencia; claves públicas en campos seleccionables.
- La maqueta `preview/gl-ui.html` sirve para revisar el sistema visual sin necesidad de un emulador.

## Pendiente (no hacer)

Filtro de registro, háptica, i18n más allá de `strings.xml` es-ES implícito.

## Cómo probar UI

```bash
gradle :app:testDebugUnitTest
adb shell pm clear dev.gl.license.debug
gradle :app:connectedDebugAndroidTest
```

Sin `gradlew` en el repo: ver `AGENTS.md`.

TalkBack: generador (chip, checkbox, panel válido), registro (tiempo restante, vacío), detalle (filas fusionadas).
