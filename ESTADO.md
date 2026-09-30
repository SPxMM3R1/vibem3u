# ESTADO.md — VibeM3U: puesta al día para cualquier agente

> Léelo completo antes de trabajar, junto con `AGENTS.md` (cómo trabajar) y
> `Lista M3U/REGLAS.md` (reglas vigentes de ambos proyectos,
> https://github.com/SPxMM3R1/lista-m3u/blob/main/REGLAS.md). El catálogo, la EPG y el runner
> viven en `SPxMM3R1/lista-m3u` (su `ESTADO.md` los cubre). **Al terminar cualquier cambio,
> actualiza este archivo en el mismo commit**: la sección «Hoy» si cambió el estado y una
> línea nueva en «Bitácora».

Última actualización: **2026-09-30**.

## Hoy, en una mirada

- **Versión publicada**: 0.5.43 (`versionCode` 152). Se publica con tag `vX.Y.Z` y el
  workflow «Publicar APK». No hay SDK Android local: compila «Android CI».
- **Estado visual**: el usuario aprobó todo el estilo nuevo, pero la 0.5.42 y la 0.5.43 aún no
  se han visto en la TV. Pedir revisión antes de seguir iterando el diseño.
  - **Guía** (`EpgGuideView`):
    - bloque de arriba (hero) con el programa enfocado;
    - filtros por categoría (▲ desde el primer canal);
    - ventana de 150 min;
    - el velo cyan marca el programa enfocado y se mueve con ◀ ▶;
    - reloj con fecha en recuadro gris y sin atajos abajo.
  - **OSD**: pantalla completa con degradado (`OsdScrimView`), logo y «025 · Categoría»,
    título grande, descripción, avance, «Después» y datos técnicos abajo a la derecha. El
    título que se desplaza se difumina en los bordes.
  - **Logos** (`LogoFit`): se recorta el borde transparente y todos ocupan la misma superficie
    visual, en el OSD y en la Guía.
  - **Menús**: Opciones, selector de fuentes, detalle del programa, diálogos, carga y
    MediaFlow usan filas tenues con foco cyan y píldoras.
- **Reproducción**:
  - Highfly abre con el enlace directo que publica Lista M3U (`PublishedHighflyLinks`) y usa
    el resolutor solo de respaldo.
  - Recuperación automática con 3 reintentos (2, 5 y 10 s; `PlaybackRecoveryBudget`).
  - TVN descubre su reproductor en vivo donde lo publique.
- **Recordatorios**: en la Guía se mantiene OK sobre un programa. El aviso llega con la app
  cerrada si tiene el permiso «Mostrar sobre otras apps». Se gestionan en Opciones › Interfaz.

## Pendientes y decisiones abiertas

- **Opciones**:
  - en el mockup aprobado, fecha y pestañas iban en una sola fila y el contenido en dos
    columnas; se aplicó en dos filas y a ancho completo, para no rehacer la pantalla;
  - faltan los mockups de las pestañas General y MediaFlow.
- **Pantalla de error** con ícono ámbar y «Reintentar»: aprobada en mockup, no implementada
  (hoy el error es texto en la pantalla de carga).
- **Deuda técnica**: `MainActivity.java` tiene ~4.000 líneas; conviene partirla en OSD,
  reproducción y navegación.
- **En pausa por decisión del usuario**: mascotas (Pixi y Ghosty) y animación de inicio.
- **`ResolverCatalog`**: si un host de `resolver_catalog.json` no está en la lista permitida,
  se rechaza el catálogo completo y se pierden todos los resolutores (incidente 0.5.37). El
  test `bundledCatalogLoadsWithEveryProvider` lo cubre: agregar el host nuevo en el mismo
  cambio.

## Cómo trabaja el usuario (válido para ambos proyectos)

- Responder siempre en **español de Chile, tuteando**. Nunca en inglés.
- **Diseño**: primero mockup (imagen), y solo con «aplícalo» se toca código, se compila o se
  publica. «Sí» a una pregunta no es «aplícalo».
- **No usar el PC del usuario para automatizar**: todo en GitHub Actions.
- Antes de afirmar que un canal «no funciona», comprobarlo más de una vez y decir «en este
  momento».
- Versiones: solo sube el último número (0.5.43 → 0.5.44) salvo que el usuario decida otra
  cosa.
- Nunca force-push; destructivo solo con confirmación; nunca publicar tokens ni URL firmadas.

## Entorno de trabajo

- Windows, repos en `D:\Users\SP4MM3R\Documents\Codex\`. `gh` CLI autenticado.
- **2026-09-30: el disco D: quedó lleno (0 GB libres)**. Si un commit falla por espacio,
  avisar al usuario; no borrar nada suyo.
- **Lint de CI**:
  - `MainActivity` está marcada como API inestable de Media3: otras clases no deben leer sus
    constantes (ponerlas en una clase propia, como `LogoFit`).
  - `ScheduleExactAlarm` exige el chequeo de permiso en línea.
- Tests Java locales sin Android: compilar contra los jars de
  `local-catalog/build/install/local-catalog/lib/`, más JUnit 4.13.2, hamcrest 1.3 y
  `json-20240303.jar` de la caché de Gradle; usar siempre `-encoding UTF-8`.
- Mockups con Edge headless vía PowerShell `Start-Process ... -Wait` y un perfil nuevo por
  captura.

## Bitácora (más reciente arriba)

- **2026-09-30**: `AGENTS.md` y este `ESTADO.md` pasan a ser la puesta al día obligatoria;
  punteros para cualquier proveedor de IA. Codex y OpenCode leen `AGENTS.md` directo; `opencode.json`
  hace que OpenCode cargue también `ESTADO.md`.
- **2026-09-29**:
  - 0.5.43: Guía con el foco en el programa, reloj en recuadro, sin atajos; bordes del título
    del OSD difuminados; todos los menús en el estilo nuevo.
  - 0.5.42: OSD con el estilo de la Guía y logos con tamaño óptico.
  - 0.5.41: rediseño de la Guía (hero, filtros por categoría, logos).
- **2026-09-28**:
  - 0.5.40: Highfly con el enlace directo del runner.
  - 0.5.39: se permite el host nuevo de TVN (la 0.5.37 no cargaba ningún resolutor).
  - 0.5.38: línea de la hora actual.
  - 0.5.37: TVN en su reproductor nuevo.
- **2026-09-27**:
  - Recordatorios.
  - Reconexión con 3 reintentos.
  - Guía completa (◀) y detalle del programa (segundo OK).
  - Menús planos.
  - Filas TvVoo con `countryKey`.
  - Se retiraron las 0.6.x publicadas por error (puente 0.6.2 → 0.5.33).
- **2026-09-26**: mini EPG; logo publicado en Highfly; hojas Highfly alternativas.
- **2026-09-23 al 25**: la app consume el layout web; la selección de proveedores sale de la
  app; tokens de TVN y Mega solo en RAM.
- **2026-09-17 al 22**: Highfly y TvVoo con renovación al reproducir, MediaFlow, catálogo y
  menús.
