# ESTADO.md — VibeM3U: puesta al día para cualquier agente

> Léelo completo antes de trabajar, junto con `AGENTS.md` (cómo trabajar) y
> `Lista M3U/REGLAS.md` (reglas vigentes de ambos proyectos,
> https://github.com/SPxMM3R1/lista-m3u/blob/main/REGLAS.md). El catálogo, la EPG y el runner
> viven en `SPxMM3R1/lista-m3u` (su `ESTADO.md` los cubre). **Al terminar cualquier cambio,
> actualiza este archivo en el mismo commit**: la sección «Hoy» si cambió el estado y una
> línea nueva en «Bitácora».

Última actualización: **2026-10-06** (UTC).

## Hoy, en una mirada

- **Versión publicada y verificada 0.5.74 (`versionCode` 183)**: reparación Meganoticias `a22cca4`; versión/tag `a3687ac`. Android CI completo `37542313890` y Release `37542728936` verdes. Release no draft: `VibeM3U-v0.5.74.apk`, 2.717.755 bytes, SHA256 `8c940150a223180f5f9e830d42b50321fd1b11bb3eae0b0ae2398e30d31f78f3`, descarga/firma compatible verificadas. APK exacta instalada y 007 con vídeo real 1080p30 en Android TV API36; TV física pendiente. Catálogo/EPG/diseño intactos; no se necesita commit hermano en Lista M3U. El cierre documental posterior no modifica el tag ni el binario.
- **Meganoticias (007), reparación entregada**: el código `3005401` corresponde a HTTP 401 al descargar vídeo HLS. Tokens recién emitidos también dieron rechazo de segmentos pese a master/variantes 200; más tarde el flujo anterior entregó vídeo sin modificarlo, evidencia de intermitencia que aún no permite atribuir la causa al proveedor, caché o expiración concretos. Ahora el resolver valida un segmento antes de devolver/cachear la fuente y renueva una vez ante 401/403, sin reiniciar el presupuesto de 12 s. La renovación/reconexión de `MainActivity` ya existía y se conserva. 340 tests debug, lint, assembleDebug y 39 tests del auxiliar verdes (nueve nuevos sin red); dos aperturas Java reales y fotograma decodificado a las 22:39:56 UTC / 19:39:56 Santiago. CI/Release verificados y APK exacta de GitHub 0.5.74/183 con Megatiempo PM real y dos fotogramas distintos a las 22:50–22:52 UTC (19:50–19:52 Santiago); OSD 1920×1080/30 FPS H.264/AAC/5,3 Mbps. Sin 401 ni recuperación en el registro filtrado de ese intervalo; no garantiza disponibilidad futura del proveedor ni equivale a TV física. Audio detectado, no audición validada. No se cambian UI, listas, logos ni EPG; Lista M3U contraparte `59e7816`, sin commit relacionado necesario.
- **Versión preparada: 0.5.73 (`versionCode` 182)**, compatibilidad de respaldos CNCVerse (`08a7943`). 331 tests Android debug, lint debug, auxiliar y 24 JVM de proyección/respaldos correctos. Publicación: exigir Android CI completo del commit de versión, tag `v0.5.73`, Release no draft y APK/firma/checksum verificados antes del layout T13 ← 217. No se afirma prueba en TV física.
- **Nueva compatibilidad para 0.5.73**: el usuario pidió el 217 como respaldo de T13 (9). La 0.5.72 rechaza ese respaldo porque su URI es `vibem3u://resolver/cncverse/…`, no HTTP. Reparado en `TvVooBackup`: referencia CNC validada y tvg-id real del respaldo solo RAM; canal principal conserva su identidad/9/EPG/logo. La elección manual también resuelve antes de Media3, y un motor deshabilitado no pasa una URI interna al player. Cinco regresiones nuevas; integración con catálogo real: 16 CNC conservados, 217 dentro de T13 (no separado), 78 visibles sin renumerar. Lista M3U retira 24 números más (349 ya ausente) y publica el layout solo después del APK compatible. No cambios visuales ni selección Highfly/TvVoo; comprobación física de TV y estabilidad de esa señal pendientes. SDK confirmado con `android-cli`, trabajo en C: por D: lleno; verificar CI/Release/checksum antes de afirmar entrega.
- **Estabilidad CNCVerse, corrección 0.5.69**: el APK real 0.5.68 reprodujo en emulador Android TV reinicios de TNT Sports 3 activados por el watchdog a los 5 s, poco búfer y error 1002 (fuera de ventana live); no cierre del proceso ni prueba de avería física del decoder. Perfil CNCVerse 8 s de inicio/reanudación y objetivo live 30 s, sin subir el presupuesto de RAM. El watchdog considera progreso de descargas, espera 25 s sin progreso en BUFFERING / 15 s en READY y conserva techo 45 s. Otros motores mantienen umbrales y errores fatales mantienen reconexión. Runner/editor: 292 Python + 39 JS verdes; app: 324 tests debug, lint debug y auxiliar verdes; CI completo `37352876869` verde (tres variantes/lint e instrumentadas). Con APK corregido, TNT Sports 3 mostró 1080p durante más de 5 min sin recuperaciones ni error 1002; hubo rebuffer inicial, no se promete ausencia total de pausas. 13C y vuelta a TVN también mostraron vídeo. APK release 0.5.69 (versionCode 178) instalado desde artefacto CI para contraste. TV física/Pentonic y el resto de las 258 señales pendientes; verificar tag/Release y checksum antes de afirmar publicación.
- **CNCVerse Chile (entrega 0.5.68)**: soporte compartido app/auxiliar para `chiletv|nombre exacto|auto`, que resuelve solo variantes de una entrada única del catálogo CHILE TV. SPORTS WORLD conserva su selección exacta y proxy HTTPS. DNS/IP pública, HLS y segmento validados antes de devolver la fuente; sin DRM crudo, sin guardar IDs opacos ni enlaces. Los 242 nombres de Chile TV y TSN 5 se incorporan editorialmente en Lista M3U, no automáticamente por la app. Local: 22 tests CNCVerse + 7 del auxiliar verdes. Prueba real Java de 13C: 2,91 s, fotograma con logo 13 Cultura; no equivale a TV física ni a validar las 242 señales.
- **CNCVerse**: nuevo motor HTTP independiente en app y `local-catalog`, preparado para 0.5.67. Referencias exactas sin claves; ID opaco y enlaces proxy solo RAM, TTL 120 s, máximo 20 s. Preserva master/audio y MIME HLS; anuncios HTTP del Bridge se convierten a HTTPS. Prueba real Java TNT Sports 3: resolución 3,79 s y fotograma decodificado con logo TNT SPORTS 3/snooker. Falta prueba de reproducción en TV física. No hay alta automática de canales ni cambios de interfaz. El usuario confirma Highfly Premium vinculado: no atribuir F1 UHD→FHD a falta de token; la ruta real de esa TV sigue sin comprobarse y este cambio no repara Premium.
- **Versión de esta entrega**: 0.5.69 (`versionCode` 178), estabilidad de reproducción CNCVerse (cambio `0473d5d`), CI `37352876869` verde y APK probado en emulador. Comprobar Release `v0.5.69` y APK/checksum en GitHub; TV física pendiente. La 0.5.68 añadió Chile TV. La 0.5.67 incorporó el motor CNCVerse deportivo. La 0.5.66: en un canal con señal preferida (p. ej.
  Canal 13), «Fuentes y calidades» muestra «Directo» (la preferida) y «Respaldo» (la propia) y,
  debajo, las calidades; elegir el respaldo lo deja fijo en las reconexiones. La 0.5.65: señal preferida entre dos directos: con
  `preferredM3u` en el layout, el canal abre primero esa otra fila M3U (que la app ya no muestra
  sola) y deja su propia señal como respaldo (`x-backup-stream`, `TvVooBackup`). La 0.5.64: un canal directo puede traer un respaldo
  TvVoo de la misma señal (`backupTvVoo` en el layout, `TvVooBackup`): abre el directo y, si no se
  recupera, pasa solo a TvVoo; «Fuentes y calidades» muestra «Directo» (principal) y las versiones
  TvVoo. Opciones con difuminado parejo a pantalla completa. La 0.5.63: OK abre la descripción en el mismo OSD
  (logo, título, descripción y avance; sin «Ahora» ni «Después», sin tocar la hora); sin
  descripción OK no hace nada. La 0.5.62: el OSD moderno se ve igual cargando y con
  imagen (degradado siempre visible y textos a pleno brillo): como el degradado es negro puro,
  ocultarlo durante la carga ya no servía y llegaba tarde. La 0.5.60 trajo el difuminado del OSD con curva suave y
  sombra difusa tras la hora; descripción solo en el detalle (OK) y detalle sin «A
  continuación»; logos corregidos por tinta y tamaño ajustable en vivo por logo. La 0.5.59
  agrandó los logos 30 % (`LogoFit.LOGO_SCALE`) con mipmaps. La 0.5.58 centró Opciones. La 0.5.57 ancló «Fuentes y calidades». La 0.5.56 igualó el alto de los logos UHD con su versión normal. La 0.5.55 trajo la carrera rápida de TvVoo, calidad real, «Fuentes y
  calidades» por versión, aviso de mejor calidad y reglas de reconexión.
  Verificar su publicación en Releases antes de dar la entrega por concluida. Se publica con tag `vX.Y.Z` y el
  workflow «Publicar APK». Hay SDK Android local 35/36: compilar en C: por falta de espacio en D:; CI completo sigue siendo obligatorio.
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

- **2026-10-06 UTC**: cierre de publicación 0.5.74/183: Android CI `37542313890` y Release `37542728936` correctos, tag `a3687ac`, APK descargada de GitHub con SHA256/firma compatible comprobadas. Instalación de esa APK (no debug): canal 007 con vídeo real entre 22:50–22:52 UTC, dos fotogramas distintos y OSD 1080p30 H.264/AAC. Sin 401/recuperación observados en ese intervalo. Documentación solamente; no mover el tag ni generar otro binario. Prueba física e intermitencia upstream siguen sin resolverse de manera concluyente.
- **2026-10-06 UTC**: preparada 0.5.74/183 tras publicar `a22cca4`. Solo incremento de versión y documentación. Exigir Android CI completo del SHA de versión antes de tag y Release; no presentar push como APK publicada. La reparación acota autorización/intermitencia, no garantiza funcionamiento ante caída general del proveedor.
- **2026-10-06 UTC**: reparación de falsos positivos de autorización de Meganoticias en app y auxiliar: master → variante → segmento, handoff solo del flujo aceptado y un reintento 401/403 acotado al plazo original. Nueve tests cubren renovación, rechazo persistente, no renovar 404/contenido inválido, cancelación, presupuesto, headers/handoff y expiración RAM. Runner/editor primero (312 Python, una copia omitida por ruta, 41 JS); 340 Android debug, lint/APK debug y 39 auxiliar verdes. Prueba Java y fotograma muestran vídeo real; la prueba previa también se recuperó sin cambios, así que el origen de la intermitencia 401 no está confirmado. Sin cambios editoriales ni visuales. Publicar corrección y preparar 0.5.74/183; CI/Release/TV física no se sustituyen por la validación JVM.
- **2026-10-06 UTC**: preparada versión 0.5.73/182 después de reparación `08a7943` publicada en `main`. Solo incremento de versión y puesta al día de documentación; sin diseño, EPG ni selección. Primer intento Gradle incluyó accidentalmente el test de proyección de app en el auxiliar (sus clases no existen allí); se retiró esa inclusión, configuración auxiliar final sin cambios, y la batería Android/lint/auxiliar pasó. 331 tests debug y 24 JVM de respaldo/proyector verdes. CI completo del commit de versión y Release/APK/checksum siguen siendo compuertas obligatorias; no confundir push con APK publicada.
- **2026-10-06 UTC**: compatibilidad de `backupm3u` con CNCVerse para T13 9 ← 217. Corregir el catálogo solo no bastaba: `TvVooBackup.httpUri` omitía resolutores internos. Referencia validada, identidad de caché del respaldo independiente y metadatos de resolución reconstruidos desde la URI; cambios acotados a `TvVooBackup`/`MainActivity` y cinco tests. Selector manual invoca resolución en vez de Media3 directo; motor apagado produce fallo controlado. Regresión de proyección real con 78 visibles / 16 CNC (uno como respaldo); 24 JVM de respaldos y proyector correctos. Runner/editor primero: 298 Python (una comparación de copia omitida por ruta), 41 JS, contrato y bundle temporal. Preparar APK 0.5.73 antes del commit hermano de catálogo; conservar app 0.5.72 como histórico. No editar checkout de D: ni otros trabajos.
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
- **2026-10-05** (UTC): preparada 0.5.69 (`versionCode` 178) con reparación CNCVerse `0473d5d` ya en main remoto. Publicar solo tras CI completo verde y verificar Release/APK/firma/checksum; no reutilizar 0.5.68. Contraparte Lista M3U `6787030` sin modificación del catálogo.
- **2026-10-05** (UTC): reparación CNCVerse autorizada tras diagnóstico con APK publicado 0.5.68. Cambios acotados a `PlaybackStallPolicy`, `PlaybackBufferManager` y uso en `MainActivity`; ocho regresiones nuevas (10 en política), clasificación de carga sin falsa etiqueta de decoder, búfer en tiempo CNCVerse sin aumentar bytes, separación del borde live. No tocar listas/EPG/OSD ni persistir URLs. Compilación aislada en C: (D: <1 GB libre): 324 tests debug, lint y auxiliar verdes; prueba de APK y CI completo antes del release. Contraparte Lista M3U sin cambios (catálogo compatible `6787030`).
- **2026-10-05** (UTC): la integración real de las 242 filas detectó que 15 nombres `[Not 24/7]` eran descartados por el validador genérico de URI (el resolutor por sí solo sí los aceptaba). `DynamicSourceReference` valida la excepción literal con el contrato CNCVerse Chile, sin habilitar barras arbitrarias. Nueva regresión parser/cache; 23 tests CNCVerse y 7 del auxiliar. Se comprueba la proyección completa de las 258 pruebas (121–378) antes de publicar la membresía.
- **2026-10-05** (UTC): preparada versión 0.5.68 (`versionCode` 177) para Chile TV; push main → Android CI verde → tag `v0.5.68` → APK y checksum verificados. No publicar las 242 filas chilenas en Lista M3U antes de disponer del APK compatible.
- **2026-10-05** (UTC): ampliado CNCVerse a CHILE TV a petición del usuario (242 entradas y TSN 5 al final de Lista 1). Modo cerrado por nombre exacto, sin fallback a otro metadata; admite HLS externo público solo en Chile TV, mantiene restricción deportiva y no interpreta ClearKeys/DASH. Seis regresiones nuevas (22 total) ejecutadas también en el auxiliar; 29 tests locales verdes, distribución auxiliar recompilada offline. SDK Android 35/36 sí existe (`android info sdk`), aunque la compuerta Android completa sigue en CI. Sin diseño/OSD, Highfly, TvVoo ni creación de canales en la app. Contrato hermano pendiente de publicación tras APK compatible.
- **2026-10-05** (UTC): primer CI de 0.5.67 detectó `JSONException` checked en los helpers del test CNCVerse, invisible con org.json JVM puro. Helpers corregidos para compilar también contra las firmas Android; mantener la compuerta de CI antes del tag/APK. Sin cambios al resolutor de producción.
- **2026-10-05** (UTC): preparada entrega 0.5.67 (`versionCode` 176), cambio funcional `cf106e8` (CNCVerse). Publicar main, exigir Android CI verde y después tag `v0.5.67`/workflow «Publicar APK»; verificar asset y SHA-256. No se agregan canales ni se cambia Premium.
- **2026-10-05** (UTC): `CncVerseStreamResolver`, registro, catálogo, referencia interna, redacción ClearKey y previsualización local. Contrato coordinado con Lista M3U; sin copiar código propietario Bridge/Cloudstream ni nuevas dependencias. Runner/editor primero; 37 tests JVM de lógica y 7 del auxiliar, más Gradle `--offline :local-catalog:test :local-catalog:installDist` en verde (distribución local recompilada). Video TNT Sports 3 obtenido con el cliente Java real. APK completa/lint/registros Android se validan en CI; reproducción TV y alta de canales pendientes.
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
