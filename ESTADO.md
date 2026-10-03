# ESTADO.md — VibeM3U: puesta al día para cualquier agente

> Léelo completo antes de trabajar, junto con `AGENTS.md` (cómo trabajar) y
> `Lista M3U/REGLAS.md` (reglas vigentes de ambos proyectos,
> https://github.com/SPxMM3R1/lista-m3u/blob/main/REGLAS.md). El catálogo, la EPG y el runner
> viven en `SPxMM3R1/lista-m3u` (su `ESTADO.md` los cubre). **Al terminar cualquier cambio,
> actualiza este archivo en el mismo commit**: la sección «Hoy» si cambió el estado y una
> línea nueva en «Bitácora».

Última actualización: **2026-10-03**.

## Hoy, en una mirada

- **Versión del código**: 0.5.53 (`versionCode` 162); en curso la 0.5.54 con diálogos «escena»,
  OSD sin categoría y Highfly Premium por QR.
  Verificar su publicación en Releases antes de dar la entrega por concluida. Se publica con tag `vX.Y.Z` y el
  workflow «Publicar APK». No hay SDK Android local: compila «Android CI».
- **Estado visual**: el usuario aprobó todo el estilo nuevo, pero la 0.5.42 y la 0.5.43 aún no
  se han visto en la TV. Pedir revisión antes de seguir iterando el diseño.
  - **Guía** (`EpgGuideView`), sobre el mismo negro puro del OSD (antes era un negro azulado):
    - bloque de arriba (hero) con el programa enfocado;
    - filtros por categoría (▲ desde el primer canal);
    - ventana de 150 min;
    - el velo cyan marca el programa enfocado y se mueve con ◀ ▶;
    - reloj con fecha en recuadro gris y sin atajos abajo.
  - **OSD**: pantalla completa con degradado negro puro y fuerte (`OsdScrimView`), logo (sin
    categoría desde 2026-10-03), título grande, descripción, avance, «Después»; el número del
    canal va a la derecha a media altura, con sombra; abajo a la derecha la
    hora grande con la fecha larga y debajo los datos técnicos (ya no hay reloj aparte arriba
    en el moderno). El título que se desplaza se difumina en los bordes.
  - **Carga** (moderno): onda Material 3 sin pista recta y con los extremos difuminados
    (`WavyProgressView`); cada vuelta entra vacía por la izquierda y sale por la derecha, y
    el reloj se reinicia al aparecer (antes podía empezar a mitad de camino). Los pasos van
    sin «…» y entran con la transición enfatizada.
    Mientras la pantalla está negra, el OSD no dibuja su degradado y sus textos bajan al 70 %
    (no enciende la atenuación local de teles mini-LED); al aparecer la imagen vuelve suave.
  - **Logos** (`LogoFit`): se recorta el borde transparente y todos ocupan la misma superficie
    visual, en el OSD y en la Guía.
  - **Menús**: Opciones, selector de fuentes, detalle del programa, carga y MediaFlow usan
    filas tenues con foco cyan y píldoras.
  - **Diálogos «escena»** (2026-10-03, moderno): salir, actualización y Premium ocupan la
    pantalla sobre el video atenuado, con el degradado del OSD desde abajo y el contenido abajo
    a la izquierda (etiqueta con línea de color, título grande, detalle, píldoras con foco
    blanco). Al salir, el foco parte en «Salir». Reloj de Opciones sin caja, sobre una sombra
    difusa en la esquina.
  - **Opciones** (0.5.47, moderno): encabezado idéntico al de la Guía y una sola columna
    de 560 dp que mide lo que su contenido necesita (sin relleno ni desplazamiento); fondo
    `menu_backdrop` en negro puro. Espacios: 4 dp entre filas, 16 antes de cada sección y 6
    después; filas con título 12sp y descripción 9sp; calidades y Moderno|Clásico en línea;
    datos de solo lectura en rejilla. El layout sale de un generador (ver Bitácora); el
    clásico conserva la versión 0.5.46.
  - **Opciones** (0.5.46), 6 pestañas: En reproducción (calidad, subtítulos, fuente de la
    señal, información de la señal) · Video y audio (nivelación de volumen, reconexión
    automática) · Interfaz (estilo Moderno|Clásico, botones de canal Estándar|Invertidos,
    atajos del control) · Recordatorios · Canales (listas, Guardar/Cancelar, avanzado:
    proveedores, catálogo Highfly, MediaFlow) · Sistema (actualización, estado del servicio,
    almacenamiento, información). Los interruptores se guardan al instante; Atrás aplica y
    sale; Cancelar deshace lo escrito en las listas. Volver de Opciones solo recarga canales
    si cambiaron las listas, los proveedores o la nivelación.
- **Dos estilos** elegibles en Opciones › Interfaz › Estilo: moderno (por defecto) y clásico (Guía, OSD, detalle, menús y diálogos de la 0.5.40). Al cambiarlo, la app se recarga.
- **Reproducción**:
  - Highfly abre con el enlace directo que publica Lista M3U (`PublishedHighflyLinks`) y usa
    el resolutor solo de respaldo.
  - Selector manual Highfly (▶, «Fuentes y calidades»): consulta y valida las alternativas
    actuales del proveedor. Ya no corta la consulta tras 250 ms sin resultados; espera hasta
    completar las validaciones o agotar el plazo real. Conserva las fuentes válidas y cancela
    las pendientes al vencer. No guarda una preferencia de fuente ni cambia el arranque del runner.
  - Recuperación automática con 3 reintentos (2, 5 y 10 s; `PlaybackRecoveryBudget`).
  - TVN descubre su reproductor en vivo donde lo publique.
- **Highfly Premium** (2026-10-03): Opciones › Canales › «Highfly Premium» › Vincular. La TV
  muestra un QR y un código de 4 dígitos; el teléfono (misma red) abre la página que sirve la
  TV, pega el token o el enlace de premium.highfly.to y la TV lo verifica y lo guarda cifrado.
  Con Premium vinculado, los canales Highfly prueban primero su fuente Premium y, si no
  responde, la gratuita. Si Highfly rechaza el token, aparece la escena «Tu token venció» con
  «Vincular de nuevo» o «Ver señal gratuita». Región elegible (Automática por defecto). Solo en
  el estilo moderno; sin prueba en TV ni con un token real todavía.
- **Recordatorios**: en la Guía se mantiene OK sobre un programa. El aviso llega con la app
  cerrada si tiene el permiso «Mostrar sobre otras apps». Se gestionan en Opciones › Recordatorios.

## Pendientes y decisiones abiertas

- **Opciones**: el diseño compacto (0.5.47) aún no se ha visto en la TV; pedir revisión.
  Regla del usuario: no agregar contenido de relleno para ocupar espacio. La reconexión automática apagada muestra el error de inmediato (OK reintenta).
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

- **2026-10-03**: aplicado lo aprobado en mockups (OSD, diálogos «escena» y Premium):
  - OSD moderno sin categoría y número a la derecha a media altura.
  - Diálogos de salir y actualización como «escena» (`SceneDialog`, layouts generados desde el
    mockup aprobado con `scene_layouts.py` del scratchpad); foco inicial en «Salir».
  - Reloj de Opciones sin recuadro (`corner_scrim`). `activity_settings.xml` ya tiene ediciones a
    mano además del generador (`settings_compact_gen.py`): si se regenera, conservar el reloj, la
    sombra y la sección Premium.
  - Highfly Premium restaurado para el dominio `.to`: token cifrado, vinculación por QR con
    servidor local (`PremiumPairingServer`, zxing core 3.5.3 para el QR), fuente Premium primero
    en Highfly y escena de token vencido. Se quitó la limpieza que borraba claves `highfly_*` y
    alias `vibem3u_highfly*` al abrir Opciones. Pruebas: `HighflyPremiumTest` (12) y
    `HighflyStreamResolverTest` (13) pasan en JVM local; CI Android pendiente.
- **2026-10-03**: corregido el timeout prematuro del selector Highfly en
  `HighflyStreamResolver.java`: un sondeo vacío de 250 ms continúa la espera en lugar de
  tirar error o devolver una lista incompleta. Se reprodujo el fallo con 4 regresiones antes
  del arreglo; después, `HighflyStreamResolverTest` pasa 13/13 (6 casos nuevos: primera fuente
  lenta, alternativa lenta, rechazo previo, plazo con resultado parcial, plazo sin resultados
  y cancelación). Batería JVM de Highfly y carrera HLS: 26/26; selector repetido: 13/13.
  Compilación Android/lint/instrumentadas se verifican
  en el CI de la publicación. Pendiente comprobar interacción con el control en TV física.
  No cambia Lista M3U, el contrato de identidad, la caché ni la persistencia de preferencias.
- **2026-10-02**: las URL «Clean» de TvVoo que llegan como IP con puerto
  (`http://109.205.187.150:8008/sunshine/…`) se descartaban siempre: la app las «subía» a
  HTTPS y ese puerto no lo habla. Ahora `/sunshine/` sobre IP se prueba primero por HTTP.
- **2026-10-02**: Opciones con proporciones corregidas y diálogo de salir moderno (mockup
  aprobado). Columna de 560 dp (antes 452) y letra un punto más grande (título 13sp,
  descripción 10sp). Las calidades van en su propia línea bajo el título, de a 5 por línea:
  antes compartían fila y aplastaban «Calidad de video». El diálogo de salir moderno ya no es
  la barra antigua: tarjeta centrada en negro con «Seguir viendo» (foco por defecto) y «Salir».
- **2026-10-01**: respaldo real entre versiones del mismo canal TvVoo. Lista M3U publica
  `data/tvvoo-variantes.json` (hermanas por canal y país: HD, FHD, BACKUP…; SPORT ≠ SPORTS).
  `PublishedTvVooVariants` lo lee junto a los enlaces Highfly y `TvVooStreamResolver` prueba
  primero la versión elegida y luego sus hermanas, con la validación HLS + segmento de siempre.
  Sin archivo (o con uno de más de 7 días) funciona como antes. Prueba: `TvVooVariantFallbackTest`.
- **2026-10-01**: TvVoo más fiable y sin falso «Memoria baja».
  - Causa principal medida: el addon responde a veces `streams: []` (4 de 45 consultas; 4 de
    15 en Sky Sports F1 DE) y cada fila TvVoo tiene un solo alias, así que el canal fallaba
    y al volver a entrar funcionaba. Ahora la consulta se reintenta hasta 3 veces (400/800 ms)
    y el presupuesto de resolución sube de 8 a 12 s.
  - El CDN de Vavoo cambió de dominio (`*.fu8oefd4v2dvlmaarur6crfp.com`) y sus nodos dan
    certificado vencido por HTTPS: el respaldo por HTTP se reconoce por la forma del dominio
    y la ruta `/sunshine/`, ya no por un dominio fijo.
  - La URL «Clean» del CDN se guarda 8 min (vence a los ~20); NoFreeze (`tvvoo.hayd.uk/live/`)
    sigue con los 25 min del catálogo. Un canal TvVoo que falla pide fuente nueva desde el
    primer reintento.
  - «Memoria baja» salía solo con TvVoo porque el búfer llegaba a su tope en bytes (normal en
    1080p50 con segmentos de 10–16 s). Ahora el búfer lleno solo avisa si el heap pasa el 75 %.
- **2026-10-01**: al abrir, la lista ya no cambia de números un segundo después. La app mostraba primero la lista M3U guardada y luego el catálogo publicado agregaba los canales de proveedor (Sky 21–23), corriendo todos los siguientes. Ahora el primer arranque espera el catálogo (máx. 6 s; sin red, muestra lo que hay) y la carga sigue en pantalla mientras tanto.
- **2026-10-01**: 0.5.47. Opciones compacto con el sistema de espacios de la Guía (tras tres
  rondas de mockups: el usuario rechazó tarjetas estiradas y contenido de relleno); Guía y
  fondo de menús en negro puro; la onda de carga ya no parte desde el medio.
- **2026-09-30**: 0.5.46 aplica los mockups aprobados: Opciones en 6 pestañas con nombres
  profesionales (En reproducción, Video y audio, Interfaz, Recordatorios, Canales, Sistema),
  reconexión automática opcional, estado del servicio; OSD con degradado negro más fuerte y
  hora/fecha dentro del OSD; carga con onda Material 3 sin «…» y OSD atenuado sobre negro.
- **2026-09-30**: 0.5.45 corrige la 0.5.44, que no abría tras elegir la interfaz clásica (el estilo clásico usaba vistas del selector antes de buscarlas). Red de seguridad en `UiStyle`: si el arranque en clásico no llega a 5 s, el siguiente inicio vuelve al moderno.
- **2026-09-30**: estilo clásico elegible (diseño 0.5.40) junto al moderno, pedido por el usuario; recursos `classic_*`, `EpgGuideClassicView`, `UiStyle`.
- **2026-09-30 (estabilidad)**: el editor local no compilaba desde la 0.5.40 (faltaba `PublishedHighflyLinks` en `local-catalog/build.gradle.kts`); arreglado y el CI ahora compila y prueba `local-catalog`. Hallazgo de una revisión externa (Sol).
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
