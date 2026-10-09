# ESTADO.md — VibeM3U: puesta al día para cualquier agente

> Léelo completo antes de trabajar, junto con `AGENTS.md` (cómo trabajar) y
> `Lista M3U/REGLAS.md` (reglas vigentes de ambos proyectos,
> https://github.com/SPxMM3R1/lista-m3u/blob/main/REGLAS.md). El catálogo, la EPG y el runner
> viven en `SPxMM3R1/lista-m3u` (su `ESTADO.md` los cubre). **Al terminar cualquier cambio,
> actualiza este archivo en el mismo commit**: la sección «Hoy» si cambió el estado y una
> línea nueva en «Bitácora».

Última actualización: **2026-10-09** (America/Santiago).

## Hoy, en una mirada

- **0.5.84/193 publicada y coordinada con Lista M3U**: cambio funcional `ab2f85b`, versión `fbea921`, tag `v0.5.84`; Android CI `37869646187` (incluidas pruebas instrumentadas) y Release `37869965389` success. APK pública `VibeM3U-v0.5.84.apk`, 2.722.183 bytes, SHA-256 `402ccd4ec714bccdb7683e66a44efc06ee8eab171cbbe238520658b14f79bd50`, certificado SHA-256 compatible `ae6457004fe9b72f077439ce882e3913b2429e96dc7285f9d38b435e0f4322b6`. Se retiró solo el soporte Nauta como respaldo directo; el resolutor normal Nauta y los respaldos HTTP se conservan. El auxiliar local consulta categorías/canales Nauta para el generador. Contraparte Lista M3U `08cf7e1` publicada: runner `37870337325`, editor `37870337366`, dirigido `37870337472` y Highfly `37870337314` success; 606 filas/353 Nauta/0 respaldos Nauta y dos HTTP conservados. EPG `37870440382` success, restringida a Lista 1 y sin Nauta en XML/pendientes; editor Pages con pestaña Nauta publicado. Sin prueba física de TV.

- **Historial, no estado actual — 0.5.83/192**: se publicó el respaldo Nauta base→HD (`390732a`, `v0.5.83`) y 55 vínculos en Lista M3U. Esa función fue retirada en 0.5.84; las cifras de la publicación anterior permanecen aquí como registro histórico y no describen la app/catálogo actuales.

- **0.5.81/190 publicada y verificada, Nauta**: funcional `e3d2ee2`, versión/tag `0ef0331` / `v0.5.81`. Android CI `37717793042` y Release `37718148346` success; APK no draft/prerelease, 2.720.775 bytes, SHA256 `dfb24e1a397aa4b029d41ba082414787beee0076ded5869e7c2c00258c1f2ca5`, descarga/firma compatible verificadas. Lista M3U contraparte `b33cebf` publica 533 filas en prueba/sin EPG al final (87–619), después del APK. Proyección real acepta todas, 594 visibles totales. No se tocó UI ni se reintrodujo CNCVerse. Prueba de contenido Java en ESPN/DSports, no APK en TV; ADB sin dispositivo y emulator ausente en SDK C:.

- **Publicación Nauta en curso**: funcional `e3d2ee2` publicado en `main`; preparada 0.5.81/190 sin cambio visual ni CNCVerse. CI completo, tag y APK Release se verifican antes de publicar las 533 referencias del repositorio hermano. No afirmar disponibilidad completa de Nauta ni prueba física.

- **Nauta preparado (2026-10-07)**: motor nuevo app + auxiliar local, sin UI/EPG ni altas autónomas. URI pública categoría/nombre exacto; metadatos/IDs opacos/URLs/headers solo RAM. HLS y segmento exigidos, plazo 20 s y renovación única. Lista M3U prepara 533 filas en prueba al final (snapshot de 536 recursos/18 categorías), sin renumerar existentes. Doce regresiones Nauta + catálogo integrado; APK 0.5.81 antes de publicar esas referencias. CI/Release/prueba física pendientes en esta entrada.

- **Versión publicada y verificada 0.5.74 (`versionCode` 183)**: reparación Meganoticias `a22cca4`; versión/tag `a3687ac`. Android CI completo `37542313890` y Release `37542728936` verdes. Release no draft: `VibeM3U-v0.5.74.apk`, 2.717.755 bytes, SHA256 `8c940150a223180f5f9e830d42b50321fd1b11bb3eae0b0ae2398e30d31f78f3`, descarga/firma compatible verificadas. APK exacta instalada y 007 con vídeo real 1080p30 en Android TV API36; TV física pendiente. Catálogo/EPG/diseño intactos; no se necesita commit hermano en Lista M3U. El cierre documental posterior no modifica el tag ni el binario.
- **Meganoticias (007), reparación entregada**: el código `3005401` corresponde a HTTP 401 al descargar vídeo HLS. Tokens recién emitidos también dieron rechazo de segmentos pese a master/variantes 200; más tarde el flujo anterior entregó vídeo sin modificarlo, evidencia de intermitencia que aún no permite atribuir la causa al proveedor, caché o expiración concretos. Ahora el resolver valida un segmento antes de devolver/cachear la fuente y renueva una vez ante 401/403, sin reiniciar el presupuesto de 12 s. La renovación/reconexión de `MainActivity` ya existía y se conserva. 340 tests debug, lint, assembleDebug y 39 tests del auxiliar verdes (nueve nuevos sin red); dos aperturas Java reales y fotograma decodificado a las 22:39:56 UTC / 19:39:56 Santiago. CI/Release verificados y APK exacta de GitHub 0.5.74/183 con Megatiempo PM real y dos fotogramas distintos a las 22:50–22:52 UTC (19:50–19:52 Santiago); OSD 1920×1080/30 FPS H.264/AAC/5,3 Mbps. Sin 401 ni recuperación en el registro filtrado de ese intervalo; no garantiza disponibilidad futura del proveedor ni equivale a TV física. Audio detectado, no audición validada. No se cambian UI, listas, logos ni EPG; Lista M3U contraparte `59e7816`, sin commit relacionado necesario.
- **Estado visual**: el usuario aprobó todo el estilo nuevo, pero la 0.5.42 y la 0.5.43 aún no
  se han visto en la TV. Pedir revisión antes de seguir iterando el diseño.
  - **Guía** (`EpgGuideView`), sobre el mismo negro puro del OSD (antes era un negro azulado):
    - bloque de arriba (hero) con el programa enfocado;
    - filtros por categoría (▲ desde el primer canal);
    - ventana de 150 min;
    - el velo cyan marca el programa enfocado y se mueve con ◀ ▶;
    - reloj con fecha en recuadro gris y sin atajos abajo.
  - **OSD**: pantalla completa con degradado negro puro y fuerte (`OsdScrimView`; desde la
    0.5.60 con curva suave smoothstep, sombra lateral de 400 dp e inferior de 190 dp, y una
    sombra elíptica difusa tras la hora), logo (sin categoría desde 2026-10-03), título
    grande, avance, «Después» (la descripción solo aparece con OK, en el mismo OSD, desde la 0.5.63;
    el detalle moderno ya no muestra «A continuación»); el número del
    canal va a la derecha a media altura, con sombra; abajo a la derecha la
    hora grande con la fecha larga y debajo los datos técnicos (ya no hay reloj aparte arriba
    en el moderno). El título que se desplaza se difumina en los bordes.
  - **Carga** (moderno): onda Material 3 sin pista recta y con los extremos difuminados
    (`WavyProgressView`); cada vuelta entra vacía por la izquierda y sale por la derecha, y
    el reloj se reinicia al aparecer (antes podía empezar a mitad de camino). Los pasos van
    sin «…» y entran con la transición enfatizada.
    Desde la 0.5.62 el OSD se ve igual con la pantalla negra de carga que con imagen: el
    degradado es negro puro, así que no enciende la atenuación local de teles mini-LED.
  - **Logos** (`LogoFit`, desde 0.5.59 un 30 % más grandes: superficie 97,5×29,9 dp, tope
    136,5×41,6 dp; bitmaps con mipmaps para que la TV los achique sin bordes dentados): se recorta el borde transparente y todos ocupan la misma superficie
    visual, en el OSD y en la Guía. Desde la 0.5.60 se corrige por tinta (fracción pintada,
    mediana 0,44 en 108 logos; escala (0,44 ÷ tinta)^¼ entre 0,8 y 1,25) y cada logo admite un
    ajuste a mano (50–160 %) desde Opciones › En reproducción › Tamaño del logo: vuelve al
    video con el OSD, ◀ ▶ de 5 en 5 % en vivo, ▼ automático, OK guarda, otra tecla cancela.
    Se guarda en la TV (`LogoScales`, preferencias `logo_scales`, clave = dirección del
    logo) y vale para OSD y Guía (en la Guía, sin salirse de su franja).
  - **Menús**: Opciones, selector de fuentes, detalle del programa, carga y MediaFlow usan
    filas tenues con foco cyan y píldoras.
  - **Diálogos «escena»** (2026-10-03, moderno): salir, actualización y Premium ocupan la
    pantalla sobre el video atenuado, con el degradado del OSD desde abajo y el contenido abajo
    a la izquierda (etiqueta con línea de color, título grande, detalle, píldoras con foco
    blanco). Al salir, el foco parte en «Salir». Reloj de Opciones sin caja, a la derecha del
    título.
  - **Opciones** (0.5.58, moderno): columna de 560 dp centrada arriba con «Opciones» a la
    izquierda y la hora a la derecha, pestañas centradas debajo y fondo radial
    `menu_backdrop_center` (negro al centro, el video asoma a los lados). Desde la 0.5.47, una sola columna
    de 560 dp que mide lo que su contenido necesita (sin relleno ni desplazamiento); fondo
    Espacios: 4 dp entre filas, 16 antes de cada sección y 6
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
  el estilo moderno. Probado con un token real en una TV (2026-10-04). El token
  queda guardado solo en la TV que se vinculó: cada TV se vincula por separado.
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

- **2026-10-09** (UTC): 0.5.87 (`versionCode` 196). Aviso de calidad superior reparado y robusto: antes solo se
  programaba si el primer cuadro llegaba junto con READY (casi nunca) y se abandonaba si a los 15 s el video
  cargaba. Ahora nace en `markPlaybackStarted`, busca a los 3 s, 2 min y 10 min, reintenta cada 5 s si el
  momento no sirve (hasta 12 veces); la búsqueda (`TvVooStreamResolver.findBetterThan`) no espera a las versiones que no contestan:
  corta al tiro con 1080p o más, o espera 2,5 s por una superior a la primera mejor (antes 18 s; ahora
  aviso ~10 s después del primer cuadro). Usa pocas conexiones y exige 8 s de búfer (en el emulador el
  proveedor cortó el video dos veces durante la búsqueda con 19 conexiones). Tras aceptar se busca de nuevo
  sobre la calidad nueva; si el cambio falla y vuelve, esa versión no se reofrece en la visita, no se repite si el usuario lo rechaza o lo deja pasar, y registra cada paso
  en logcat `VibeM3U-Quality`. Al aceptar, la versión queda en la caché del coordinador
  (`ResolverCoordinator.remember`) y fijada en `TvVooSourceHistory`. TvVoo abre con la primera versión de la lista
  (elegida en el selector o la del editor) apenas da NoFreeze; si tarda, espera 700 ms y abre la primera
  aceptada (ya no espera por mejor calidad; eso lo ofrece el aviso). Arranque: el último
  canal (`PlaybackPreferences.lastChannelSnapshot`, sin respaldos) se resuelve en paralelo con la lista, justo
  después de cargar del disco las versiones TvVoo y enlaces Highfly (si se adelanta, ignora la versión elegida); los vecinos directos abren conexión (DNS+TCP+TLS en el pool de `SharedHttpClient`) y se renuevan
  cada 4 min; recordatorios 10 s después del arranque; búfer inicial 1 s (`BUFFER_FOR_PLAYBACK_MS`). Precarga de
  video de vecinos (`DefaultPreloadManager`) evaluada y descartada: ver AGENTS.md.
  Publicada: tag `v0.5.87` sobre `37dd107`, `VibeM3U-v0.5.87.apk` 2.731.891 bytes, SHA-256
  `4a50a39fa8ee5a72f92aba02e12df7f1a9d454cbaf91d872863fa8f823a09598`, instalada encima en el emulador (versionCode 196).
  Medido en emulador: aviso 1080p en Eurosport 2 a 10–14 s del primer cuadro (antes ~21 s y casi nunca), aceptar →
  1080p en 4 s y nueva búsqueda sobre 1080p; la versión aceptada abre primero al reiniciar (3,8–4,7 s); directos
  1,3–2,0 s; TVN vecino con caché. Eurosport 2 720p (principal) tiene errores de segmento propios desde el 1.er segundo.
- **2026-10-09** (UTC): 0.5.86 (`versionCode` 195). Primera verificación en emulador propio (AVD `VibeTV`, Android TV 16,
  1080p, SDK en `%LOCALAPPDATA%/Android/Sdk`). La precarga de vecinos de la 0.5.85 nunca se disparaba: el primer
  cuadro suele llegar después de READY y `playbackHasStarted` lo marcaba el watchdog, donde no se programaba.
  Ahora ambos caminos pasan por `markPlaybackStarted`. Medido en el emulador con 0.5.85: arranque en frío TVN 4,7 s
  hasta el primer cuadro (2,7 s de resolución, token nuevo); directos 1,4–2,9 s; ráfaga de 5 y de 8 CH = 1 sola
  apertura; sin cierres.
- **2026-10-09** (UTC): 0.5.85 (`versionCode` 194), velocidad y fallas pedidas por el usuario tras el análisis completo.
  - Arranque instantáneo: `PublishedPlaybackCatalogRepository` guarda en `files/published_catalog/` el último
    layout, enlaces Highfly y variantes TvVoo válidos; la app abre con ellos y los renueva en segundo plano.
    Las tres descargas van en paralelo (`catalogExecutor`, antes en serie en un hilo). Sin red se conserva el
    último catálogo conocido (antes quedaba vacío toda la sesión). Un documento idéntico no rearma la lista.
  - Refresco: catálogo, enlaces y variantes cada 30 min (`catalogRefreshTicker`) y en `onStart` si tienen más
    de 15 min (antes solo en `onCreate`; con la app abierta días los enlaces Highfly vencían a las 24 h).
  - Zapeo: al mantener CH+/− o pulsar seguido solo se muestra el canal (`previewChannel`); abre 300 ms después
    de la última pulsación (`zapBy`). Una pulsación aislada abre al instante.
  - Precarga: con el canal estable 5 s, se resuelven en segundo plano el anterior y el siguiente (caché del
    coordinador); los directos solo adelantan el DNS. Guía (caché, descarga y mezcla) en hilo de baja prioridad.
  - TVN recuerda 6 h la página del reproductor descubierta (no baja tvn.cl/en-vivo en cada token nuevo).
    Nota: TVN y Meganoticias ya reutilizaban el token vigente (`cacheTtlSeconds` 0 = vigencia del token).
  - Fallas: `playChannel` usaba `player` antes de comprobar nulo; un enlace Highfly fallido se marcaba por URL
    y apagaba también a F1 UHD (mismo enlace): ahora por canal (`markFailed(catalogKey)`, prueba nueva).
  Sin cambios visuales. Pendiente: confirmar en la TV.
- **2026-10-08 / cierre de integración Nauta**: la Lista M3U hermana publicó `08cf7e1`; sus workflows de canales/editor/dirigido/Highfly finalizaron en éxito y la EPG `37870440382` también. La guía queda limitada a Lista 1, sin identidades Nauta en XML ni pendientes. Se conservaron base y HD Nauta como canales independientes; el resolutor de reproducción sigue intacto. Sin prueba física de TV.

- **2026-10-08 / 2026-10-09 UTC**: retirada la relación Nauta base→HD y publicada la fuente Nauta local. `backupm3u` acepta solo HTTP(S); IDs Nauta residuales no ocultan filas; el resolutor Nauta normal permanece intacto. `local-catalog` entrega categorías/nombres, sin IDs opacos/URLs/headers. Matriz local completa, Android CI `37869646187` y Release `37869965389` success; APK/tag/checksum/certificado verificados arriba. Falta publicar y verificar Lista M3U.
- **2026-10-08** (UTC): 0.5.82 (`versionCode` 191), mockup aprobado por el usuario: el selector «Fuentes y calidades» ya no muestra barra lateral. Si la lista tiene más de 5 filas, un degradado sobre la última fila y la pista «Mueve el control para ver más opciones» avisan que hay más; ambos se ocultan al llegar al final (`refreshSourceSelectorMoreHint`). El estilo clásico no cambia. Pendiente: confirmar en la TV.
- **2026-10-08**: `backupm3u` admite localizadores Nauta validados (`vibem3u://resolver/nauta/...`) además de HTTP(S); se rechazan esquemas internos desconocidos/localizadores inválidos. `PublishedPlaybackCatalog` oculta la fila respaldo y la adjunta al canal principal; la recuperación de `MainActivity` usa su resolutor normal. Funcional `390732a`, Android CI `37861730029` success; versión/tag/APK 0.5.83/192 en publicación. VibeM3U 0.5.82 ya está publicada, y el layout con 55 pares sigue sin subir hasta que se verifique la nueva APK.
- **2026-10-09 UTC, cierre de respaldos Nauta base→HD**: tag `v0.5.83`/192 y APK Release verificados; `PublishedPlaybackCatalogTest` y `TvVooBackupTest` pasan con la relación que oculta la fila base independiente. Contraparte Lista M3U `be35025` publicada; runner/editor correctos con 55 referencias base únicas aún presentes en M3U. EPG automática correcta, sigue restringida a Lista 1 y no agrega canales Nauta. Ningún cambio de UI ni de carga dinámica de resolutores; sin verificación física en TV.
- **2026-10-07, cierre Nauta**: APK GitHub 0.5.81/190 descargada, firma/versionado/checksum correctos; CI completo e instrumentado/Release correctos. Solo entonces se publicó Lista M3U `b33cebf` (533 filas en prueba/sin EPG). Proyección real de clases compiladas confirma 533 Nauta / 594 visibles, números únicos 87–619 y 0 descartes. Los orígenes de la placa siguen excluidos temporalmente, sin garantías de todas las señales ni TV física. Cierre documental sin cambios de funcionalidad/binario/tag.

- **2026-10-07, versión Nauta**: 0.5.80/189 → 0.5.81/190; funcional previo `e3d2ee2` subido y SHA remoto comprobado. Contraparte Lista M3U: snapshot público `contracts/nauta-trial-channels-20261007.json` (533 filas, números 87–619, en prueba/sin EPG), todavía local hasta verificar APK. Las pruebas/compilación completas locales pasan; el CI instrumentado y Release firmado siguen siendo compuertas de publicación.

- **2026-10-07, validación local Nauta**: 331 tests por variante debug/experimental/release, 34 del auxiliar (12 Nauta), lint de las tres variantes y los tres APK correctos. Parser real de la app + catálogo integrado reconoce las 533 referencias publicables, 0 incompatibles. Repetición Java del resolver confirma imagen ESPN/DSports y rechazo de los dos canales de la placa. La publicación requiere Android CI instrumentado y Release firmado del tag; la prueba física sigue pendiente.

- **2026-10-07, evidencia Nauta**: runner 301 Python (una comprobación de copia omitida por ruta, hashes de ambos contratos idénticos) y 41 JS; bundle temporal correcto, 46 canales en alcance EPG sin los nuevos. Java real + FFmpeg: ESPN 1 Chile fútbol real 640×360/24 fps, DSports 2 HD entrevista real 1280×720/60 fps, ambos con pista de audio (no audición validada). TV Pública y South Park decodificaron una placa de actualización: guard temporal del origen `tv.m3uts.xyz`, regresión que evita falsa aceptación. Muestra de cuatro, no validación de 533 ni estabilidad/TV física. Sin URLs/headers/IDs opacos en Git. Se preserva plazo de producción Meganoticias; ajuste únicamente de inicialización TLS en su fixture frío.

- **2026-10-07, Nauta**: autorizado resolver + catálogo completo y publicación. Se parte de `main` 0.5.80 (`0b7acc8`, CNCVerse retirado), copia aislada; checkout viejo con cambios CNC intacto. Resolver ejecutable exclusivamente aquí; Lista M3U publica solo nombres/identidades/localizadores y filas en prueba. No copiar exclusión browser `notWebReady` del laboratorio a Android. Validar primero runner, después app, CI completo y APK compatible antes de catálogo.

- **2026-10-07 UTC**: cierre de 0.5.79/188: CI funcional `37610139506`, versión `37610181615`, Release `37610708748` correctos. Tag `6894882`; APK exacta de GitHub (2.725.407 bytes), checksum `e00f215a8fb47244cbf6cee5e9194794d78d2aff12bc2bcbb33e0d4b32c1e8a9` y firma compatibles; instalada y con vídeo TNT3 1080p24/TSN5 1080p30, progresión confirmada a las 10:59–11:01 UTC. Debug también TNT4/TVN. TV física pendiente, sin cambios de Lista M3U, UI o EPG. Docs posteriores al tag, no reemplazan APK.
- **2026-10-07 UTC**: preparada 0.5.79/188 para `b57d128`, solo versión y documentación; publicada a main en el mismo flujo. CI/Release/APK pendientes de comprobación. Se conserva diseño, selección de señales y cambios hasta 0.5.78.
- **2026-10-07 UTC**: validación final local de optimización: 374 tests debug y 68 auxiliares, lint/compilación correctos; seis tests DNS incluyen reutilización de conexión entre contextos. Benchmark tras corregir pooling: TNT3 7,814 s frío, TNT4 2,777 s y TNT3 3,229 s calientes, TSN5 4,771 s (cinco/tres/tres/cuatro solicitudes). APK debug con contenido real TNT3 y TNT4, sin editar catálogo. Preparar CI y versión; no confundir debug con APK Release ni extrapolar Pentonic.
- **2026-10-07 UTC**: preparada 0.5.78/187 para `acfd0a2`, solo versión/documentación. CI completo del SHA de versión antes de etiquetar; APK/firma/checksum y prueba real antes de afirmar publicación. No mover tags anteriores ni editar el catálogo.
- **2026-10-07** (UTC): 0.5.77 (`versionCode` 186). El usuario reportó que en TVN el respaldo
  elegido volvía al principal. Causa: `directResolutionChannel` copiaba al respaldo HTTP el
  `tvg-id` 0104 y `x-resolver` del canal dueño, y el resolutor TVN (match por tvg-id) lo resolvía
  a la señal oficial. Ahora el respaldo HTTP lleva su propio tvg-id (`x-backup-source-id-N`, ya
  guardado para todos los respaldos) y ningún `x-resolver*`. Además: «Directo» en el selector de
  un canal con resolutor vuelve a resolver la señal oficial (su URL publicada es solo respaldo y
- **2026-10-06** (UTC): 0.5.76 (`versionCode` 185): la memoria del selector también cubre Highfly,
- **2026-10-06** (UTC): 0.5.75 (`versionCode` 184), pedidos del usuario.
  - Logo del OSD: al cambiar de canal ya no aparece el nombre unos cuadros antes del logo. Con
    logo, el recuadro queda vacío (INVISIBLE) hasta que llega; el nombre solo se ve si el canal
    no tiene logo o si falla la descarga sin caché (`showChannelLogoFallback`).
  - Memoria del selector de señales: `PlaybackPreferences.rememberSourceChoice` guarda por canal
    la señal elegida (principal, respaldo directo por huella SHA-256 de su URL —no la URL— o
    respaldo TvVoo) y `applyRememberedSourceChoice` abre ahí. Si falla, sigue con los demás
    respaldos y una vez vuelve a la principal (`rememberedBackupStart`/`backupReturnedToPrincipal`,
    índice pendiente -1 = principal). Versión TvVoo elegida: `TvVooSourceHistory.pinAlias` la
    prueba primero (carrera y resolución directa). Elegir otra señal reemplaza la memoria. La
    calidad ya se recordaba por canal.
  - Versiones TvVoo de otro país que la del editor se muestran con el país («EUROSPORT 1 · PT»):
    Lista M3U publica respaldos de otros países para Eurosport 1 y 2 (`backupCountries`).
  Pruebas nuevas: `SourceMemoryTest`. Pendiente: confirmar en la TV.
- **2026-10-06 UTC**: cierre de publicación 0.5.74/183: Android CI `37542313890` y Release `37542728936` correctos, tag `a3687ac`, APK descargada de GitHub con SHA256/firma compatible comprobadas. Instalación de esa APK (no debug): canal 007 con vídeo real entre 22:50–22:52 UTC, dos fotogramas distintos y OSD 1080p30 H.264/AAC. Sin 401/recuperación observados en ese intervalo. Documentación solamente; no mover el tag ni generar otro binario. Prueba física e intermitencia upstream siguen sin resolverse de manera concluyente.
- **2026-10-06 UTC**: preparada 0.5.74/183 tras publicar `a22cca4`. Solo incremento de versión y documentación. Exigir Android CI completo del SHA de versión antes de tag y Release; no presentar push como APK publicada. La reparación acota autorización/intermitencia, no garantiza funcionamiento ante caída general del proveedor.
- **2026-10-06 UTC**: reparación de falsos positivos de autorización de Meganoticias en app y auxiliar: master → variante → segmento, handoff solo del flujo aceptado y un reintento 401/403 acotado al plazo original. Nueve tests cubren renovación, rechazo persistente, no renovar 404/contenido inválido, cancelación, presupuesto, headers/handoff y expiración RAM. Runner/editor primero (312 Python, una copia omitida por ruta, 41 JS); 340 Android debug, lint/APK debug y 39 auxiliar verdes. Prueba Java y fotograma muestran vídeo real; la prueba previa también se recuperó sin cambios, así que el origen de la intermitencia 401 no está confirmado. Sin cambios editoriales ni visuales. Publicar corrección y preparar 0.5.74/183; CI/Release/TV física no se sustituyen por la validación JVM.
- **2026-10-06 UTC**: preparada versión 0.5.73/182 después de reparación `08a7943` publicada en `main`. Solo incremento de versión y puesta al día de documentación; sin diseño, EPG ni selección. Primer intento Gradle incluyó accidentalmente el test de proyección de app en el auxiliar (sus clases no existen allí); se retiró esa inclusión, configuración auxiliar final sin cambios, y la batería Android/lint/auxiliar pasó. 331 tests debug y 24 JVM de respaldo/proyector verdes. CI completo del commit de versión y Release/APK/checksum siguen siendo compuertas obligatorias; no confundir push con APK publicada.
- **2026-10-05** (UTC): 0.5.72 (`versionCode` 181): varios respaldos directos por canal. El layout
  admite `backupm3u` (lista ordenada de tvg-id de otras filas M3U de la misma señal, hasta 8).
  `PublishedPlaybackCatalog` oculta esas filas y las agrega al canal dueño; `TvVooBackup` guarda
  los respaldos como lista (`x-backup-stream`, uno por línea, solo en memoria) con
  `directBackupsOf`/`directResolutionChannel`. Orden: señal preferida → propia → `backupm3u`.
  `MainActivity` avanza al siguiente respaldo en cada falla (`directBackupIndex`) y el selector
  muestra Directo y «Respaldo 1…n». Primer uso: TVN (01) con 73 y 74; Canal 13 (004) con 85.
  Pendiente: confirmar en la TV.
- **2026-10-05** (UTC): 0.5.71 (`versionCode` 180), mockup aprobado por el usuario: las escenas
  Premium abiertas desde Opciones (vincular con QR y gestionar cuenta) se ven solas. Opciones es
  translúcido sobre el video y el velo de la escena (38 %) dejaba ver el menú detrás del QR.
  `SceneDialog.hideHostWhileShown` oculta `settings_root` mientras la escena está abierta (contador
  en el tag `scene_host_hidden_count` para escenas encadenadas) y lo devuelve al cerrarse. Texto,
  QR y botones sin cambios. Pendiente: confirmar en la TV.
- **2026-10-05** (UTC): 0.5.70 (`versionCode` 179): el desplazamiento de títulos largos se veía
  a saltos en la TV. `MarqueeSurfaceRenderer` ya no abre una capa (`saveLayer`) por cuadro: su
  Surface solo contiene el título, así que la máscara DST_IN de los bordes va directo sobre
  ella. El título y su separación se cachean como un `BitmapShader` repetido (una sola pasada
  por cuadro en vez de dos bitmaps; caché hasta 4096 px) y el hilo `VibeM3U-Marquee` corre con
  `THREAD_PRIORITY_DISPLAY`. Mismo aspecto y velocidad (40 dp/s). Pendiente: confirmar en la TV.
- **2026-10-05** (UTC): validada reparación `0473d5d`: 324 tests debug, lint y auxiliar locales; CI de versión `71e1505` (`37352876869`) completo verde, incluidos tests/lint debug-experimental-release y pruebas instrumentadas. APK corregido: TNT 123 con imagen real 1080p durante >5 min, sin ciclos de recuperación ni BEHIND_LIVE_WINDOW; rebuffer inicial aún posible. 13C 137 y vuelta al directo TVN 1 con vídeo. Emulador API 36: SwiftShader provocó dos crashes del proceso QEMU (Windows 0xc0000005), no AndroidRuntime/app; se repitió con `-gpu host -feature -Vulkan`. No atribuir ese fallo del entorno a la TV ni esconderlo como prueba de app estable. Instalado APK optimizado de CI 0.5.69; comprobar publicación final por tag/Release. Lista M3U intacta, contraparte `6787030`.
- **2026-10-05** (UTC): preparada versión 0.5.68 (`versionCode` 177) para Chile TV; push main → Android CI verde → tag `v0.5.68` → APK y checksum verificados. No publicar las 242 filas chilenas en Lista M3U antes de disponer del APK compatible.
- **2026-10-04**: Highfly Premium funcionó con un token real en una TV del usuario. Sky Sports
  F1 UHD y Main Event UHD (Highfly 4K) solo dan imagen con Premium: la API gratuita entrega
  «🔒 Upgrade to Premium». Sin Premium, F1 UHD usa la FHD gratuita publicada por el runner y
  Main Event UHD no tiene señal. El token es por TV: cada equipo se vincula aparte.
- **2026-10-03**: «Fuentes y calidades» aprobado en mockup: sin botón Cerrar en el moderno
  (Atrás cierra), anclado abajo al margen de las escenas (52 dp), lista que mide lo que ocupan
  sus filas (alto fijo 42 dp, hasta 5 visibles) y, en TvVoo, filas dibujadas al abrir con
  «Probando…» (`TvVooStreamResolver.plannedVersions`) que se completan en el mismo lugar; las
  versiones ya no se reordenan por calidad. La etiqueta «TU VERSIÓN» pasa a «PRINCIPAL».
- **2026-10-03**: los logos UHD de Sky se veían más bajos que su versión normal porque
  `LogoFit` iguala la superficie. Ahora un archivo `…-uhd.*` se dimensiona con el alto de su
  versión normal (factor de ancho 1,30, medido: 1101 px contra 846) y solo se alarga, en el OSD
  y en la Guía. Pruebas `LogoFitTest` (8) OK en JVM.
- **2026-10-03**: aplicado lo aprobado para TvVoo y diálogos:
  - Carrera rápida (`TvVooFastRace`) y calidad real del segmento (`VideoSampleInfo`, probado
    con las 6 variantes del stream de prueba de Apple y un SPS HEVC 1080p). Medición previa en
    Actions: enlaces vivos 0,3–1 s, muertos hasta 15 s; «TNT SPORTS 3 HD» es 576p y «TNT
    SPORTS 3» 1080p50.
  - «Fuentes y calidades» en estilo escena con una fila por versión; aviso de mejor calidad
    con vuelta automática; reglas de reconexión (Clean, ventana en vivo, sin internet, sin
    señal cada 60 s, versiones muertas 10 min).
  - Diálogos sin la línea de color; salir sin etiqueta; actualización con versión y peso.
  - Pruebas: `TvVooQualityTest` (9) más TvVoo, HLS, Highfly y Premium: 70 JVM locales OK.
  - Hecho desde una copia en C: porque el disco D: del usuario está lleno.
- **2026-10-03**: aplicado lo aprobado en mockups (OSD, diálogos «escena» y Premium):
  - OSD moderno sin categoría y número a la derecha a media altura.
  - Diálogos de salir y actualización como «escena» (`SceneDialog`, layouts generados desde el
    mockup aprobado con `scene_layouts.py` del scratchpad); foco inicial en «Salir».
  - Opciones centrado (0.5.58): sin `corner_scrim`; `SettingsActivity.updateSettingsPanelWidth`
    da 560 dp centrados al moderno y el ancho de pantalla al clásico. `activity_settings.xml` ya tiene ediciones a
    mano además del generador (`settings_compact_gen.py`): si se regenera, conservar el encabezado
    centrado y la sección Premium.
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
