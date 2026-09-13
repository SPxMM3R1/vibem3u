# vibem3u — contexto actual

ultima actualización: 2026-09-12

Este archivo reemplaza al contexto anterior. Describe el estado real del
proyecto Android TV VibeM3U al momento de esta actualización. Las versiones,
el estado de git, las ejecuciones de GitHub Actions y la disponibilidad de los
proveedores son hechos que deben volver a verificarse antes de una publicación
futura.

## identidad del proyecto

- Proyecto: VibeM3U, reproductor IPTV/M3U para Android TV.
- Ruta de trabajo:
  D:/Users/SP4MM3R/Documents/Codex/VibeM3U
- Repositorio remoto:
  https://github.com/SPxMM3R1/vibem3u
- Rama de trabajo y publicación: main.
- Paquete de producción: cl.streambox.tv.
- Compatibilidad mínima actual: Android TV 10 o superior (minSdk = 29).
- compileSdk = 36 y targetSdk = 36.
- El proyecto VibeM3U es independiente de Lista M3U, VibeDLNA, VibeDLNA
  Player y los demás proyectos. No mezclar sus ramas, commits ni archivos.

## estado de git y versión

En la fecha de este contexto:

    rama: main
    head: 954603644297551eb5733607e0bf8250f2a7c947
    commit: Bump rollback release to 0.4.83
    tag estable: v0.4.83
    versionCode: 88
    versionName: 0.4.83
    origin/main: 954603644297551eb5733607e0bf8250f2a7c947

La versión v0.4.79 fue publicada con los siguientes artefactos verificados:

- Release:
  https://github.com/SPxMM3R1/vibem3u/releases/tag/v0.4.79
- APK:
  https://github.com/SPxMM3R1/vibem3u/releases/download/v0.4.79/VibeM3U-v0.4.79.apk
- Android CI exitoso: run 34548984634.
- Release exitoso: run 34548997746.
- Ambos runs correspondieron al mismo commit de main.

Al momento de crear este contexto, los elementos no rastreados conocidos eran:

- .codex-remote-attachments/: adjuntos locales del usuario; conservarlos.
- vibem3u_context.md: este contexto nuevo.

No se deben borrar, sobrescribir ni incorporar al commit los adjuntos del
usuario sin una solicitud explícita.

Estado funcional restaurado en la versión 0.4.83:

- se eliminó la reconstrucción de canales estables desde el catálogo Premium;
- la fuente pública M3U es ahora la única fuente de metadatos y membresía
  estable que consume la app;
- el catálogo protegido solo reconstruye los eventos temporales seleccionados;
- los eventos ya no requieren un atributo numérico ni muestran un nombre de
  lista; se presentan como eventos temporales;
- el almacenamiento persistente de la credencial y la renovación del token de
  reproducción no se modificaron;
- se actualizaron las pruebas y los textos de configuración para reflejar la
  separación.

La corrección elimina también la fuente estable Premium automática y su
interruptor de configuración. La pertenencia y los metadatos de los canales
estables dependen únicamente de las listas M3U normales; el token guardado
continúa disponible para que HighflyStreamResolver solicite la fuente
autorizada al abrir cada canal Premium. La app solo puede anexar al final los
eventos temporales seleccionados desde el catálogo protegido.

La compilación y publicación de esta versión se verifican en los workflows de
GitHub Actions asociados al commit y al tag; no se presenta una compilación
local porque el equipo no tiene Java configurado.

La versión 0.4.83 es una publicación de rollback: restaura el árbol funcional
de v0.4.81 y no incluye el OSD de recuperación de v0.4.82. Se usa un número de
versión superior para que Android pueda actualizar desde v0.4.82; los tags
publicados no se reutilizan ni se reescriben.

## reglas de compilación y publicación

La compilación y la publicación oficial se realizan mediante GitHub Actions.
En el equipo local no hay un Java funcional configurado, por lo que una
compilación local no debe presentarse como validación exitosa.

Workflows actuales:

- .github/workflows/android-ci.yml: se ejecuta en push a main, pull request
  hacia main o manualmente; ejecuta pruebas, lint y compila las variantes.
- .github/workflows/android-release.yml: se ejecuta al publicar un tag v*;
  compila y publica el APK firmado en GitHub Releases.

Una publicación correcta requiere comprobar, en este orden:

1. pruebas y lint de Android CI;
2. compilación de las variantes relevantes;
3. commit y tag remotos correctos;
4. workflow de release exitoso;
5. Release y APK accesibles con la versión esperada.

El APK de GitHub Actions es la fuente oficial. No afirmar que una versión está
publicada solo porque existe un commit local o porque una compilación aislada
terminó.

Dependencias relevantes de la versión actual:

- Media3/ExoPlayer 1.10.1;
- OkHttp 4.12.0;
- AndroidSVG 1.4;
- Java de compilación 17;
- versionCode = 84;
- versionName = 0.4.79.

El build experimental usa el paquete con sufijo .experimental, habilita
BuildConfig.ENABLE_EXPERIMENTAL_VAVOO = true y no debe confundirse con la
versión estable.

## arquitectura principal

El flujo central de reproducción es:

    PlaylistSource
        ↓
    PlaylistRepository / caché
        ↓
    M3uParser
        ↓
    Channel
        ↓
    ResolverCatalog / StreamResolverRegistry
        ↓
    StreamResolver
        ↓
    ResolvedPlaybackSource
        ↓
    Media3 / ExoPlayer

Clases y responsabilidades principales:

- MainActivity: ciclo de vida de la pantalla, carga de listas, orden de
  canales, OSD, ajustes, Media3, reproducción y recuperación.
- M3uParser, Channel, Playlist: parseo y representación de canales, atributos
  M3U y metadatos.
- PlaylistSource, PlaylistRepository: fuentes M3U, lectura de caché y descarga
  condicional.
- EpgParser, EpgRepository, EpgData, EpgSnapshotCache: parseo, unión y caché
  de XMLTV.
- ChannelLogoCache, HttpResourceCache: logos y recursos HTTP persistentes,
  con actualizaciones condicionales.
- ResolverCatalog, ResolverDefinition, ResolverCatalogRepository: catálogo
  declarativo de resolutores.
- StreamResolverRegistry, StreamResolver, ResolverCoordinator:
  identificación, selección, cancelación y coordinación de resolutores.
- ResolvedPlaybackSource: URL de reproducción, cabeceras, proveedor e
  identidad estable; no debe convertirse en almacenamiento permanente de una
  URL temporal.
- HlsStreamValidator, HlsCandidateRace: validación por etapas y selección de
  candidatos HLS.
- ManifestHandoffCache, ManifestHandoffDataSource: traspaso efímero de una
  fuente dinámica hacia Media3.
- PlaybackBufferManager, PlaybackLoadErrorPolicy, PlaybackRecoveryPolicy,
  PlaybackStallPolicy, PlaybackRecoveryEpisode: buffer y recuperación
  acotada.
- PlaybackDiagnosticsWorker, PlaybackBitrateMeter, PlaybackStartupAnalytics:
  observación de frames, audio, bitrate, FPS y arranque.
- IntermittentBufferingDetector, PlaybackAvSyncDetector: detección de carga
  intermitente y atascos de audio/vídeo.

## listas, orden y epg

La app puede trabajar con dos listas M3U externas configurables. Si ambas están
activas, primero se muestran todos los canales de la lista 1 y luego los de la
lista 2; la numeración visible se presenta como una sola lista correlativa.

El primer origen M3U viene cargado por defecto. Las fuentes se leen desde
caché y se muestran tan pronto como están disponibles; la descarga condicional
se ejecuta en segundo plano y reemplaza el contenido de manera transparente
cuando cambia la respuesta remota. No bloquear la UI esperando una descarga
si ya existe una caché válida.

La EPG se une por los identificadores estables del canal. La EPG de una fuente
puede complementar canales de otra fuente si los identificadores coinciden.
Los tvg-id no deben cambiar solo porque cambió la URL HLS.

Los logos y la EPG deben seguir la misma política de caché:

- mostrar primero la copia local válida;
- enviar ETag o Last-Modified cuando exista;
- descargar solo si el servidor informa cambios;
- actualizar en segundo plano;
- publicar el resultado nuevo sin vaciar innecesariamente lo que ya está
  visible.

Archivos de contrato con el proyecto de listas:

- CONTEXTO_LISTA_M3U_PARA_VIBEM3U.md;
- LISTA_M3U_RESOLVER_CONTRACT.md;
- LISTA_3_HIGHFLY_TRASPASO.md.

Lista M3U es otro proyecto. VibeM3U puede consumir su contrato y sus URLs,
pero no debe modificar ese repositorio durante una tarea limitada a la app.

## highfly premium: canales estables y eventos temporales

Los canales estables de Highfly los genera y mantiene el flujo normal de
Lista M3U. VibeM3U consume esa fuente pública como una fuente M3U ordinaria,
con sus slugs, logos, EPG y metadatos. La app no reconstruye una playlist
estable desde el catálogo Premium ni debe consultar el catálogo protegido para
fabricarla.

VibeM3U puede conservar la posición de un canal estable cuando encuentra la
misma identidad y puede renovar su fuente de reproducción con el token Premium.
No guardar una URL HLS firmada como si fuera el slug permanente.

Los eventos temporales sí son propios de la app:

- se consulta usando el catálogo Premium autenticado;
- el usuario selecciona los eventos que quiere mostrar;
- solo esos eventos se agregan al final de todas las otras fuentes;
- los eventos se representan con una identidad controlada y un placeholder,
  no con la URL firmada del proveedor;
- al reproducir, se pide una fuente actual;
- si la fuente deja de responder, se permiten aproximadamente tres intentos
  acotados de reconexión y luego se elimina el evento de la sesión;
- el usuario puede volver a consultar el catálogo para agregarlo otra vez.

Solo los eventos temporales son canales virtuales. Los canales estables llegan
desde la fuente M3U pública y no deben tratarse como eventos virtuales.

Clases Premium:

- HighflyPremiumCatalog;
- HighflyPremiumCatalogRepository;
- HighflyPremiumPayloadParser;
- HighflyPremiumPlaylistMerger;
- HighflyPremiumCredentialStore;
- HighflyPremiumPreferences;
- HighflyPremiumEventRecoveryPolicy;
- HighflyPremiumTokenRules;
- HighflyStreamResolver.

La credencial Premium se conserva en el almacenamiento privado previsto por la
app para no pedirla en cada apertura. El catálogo en memoria puede renovarse,
pero una URL firmada o un token de reproducción no debe persistirse. Nunca
imprimir la credencial, el token, la query de autorización ni la URL completa
firmada en logs, errores o analytics.

## resolutores actuales

La identificación debe usar metadatos explícitos primero. El orden esperado es:

1. x-resolver explícito de la M3U;
2. tvg-id exacto;
3. host conocido;
4. otros atributos estables;
5. URL directa como fallback.

No identificar un proveedor solo porque el nombre visible contiene una palabra
parecida.

### catálogo actualizable

ResolverCatalogRepository carga el catálogo seguro incluido en
app/src/main/assets/resolver_catalog.json y puede instalar una versión de
datos posterior desde:

https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/resolver-catalog.json

La actualización remota:

- se acepta solo si el esquema y la versión son válidos;
- se limita por tamaño y cantidad de proveedores/aliases;
- usa una lista blanca de hosts, motores y recetas;
- se instala de forma atómica;
- conserva el catálogo incluido como fallback;
- contiene datos declarativos, no código ejecutable.

Esto permite actualizar identificadores, aliases y configuración declarativa
sin distribuir un APK, pero los motores Java/Kotlin y las reglas de seguridad
siguen formando parte de la app. Un catálogo remoto no puede introducir lógica
ejecutable arbitraria.

La receta segura documentada es bounded-payload-v1; está explicada en
RESOLVER_RECIPE_V1.md. La validación de configuración y de HLS debe
mantenerse estricta.

### tvn

TvnStreamResolver se activa principalmente para tvg-id="0104". Al abrir el
canal consulta la página oficial, obtiene el identificador y el access_token,
construye la URL Media3 solo en memoria y aplica las cabeceras de origen y
referer requeridas. El enlace publicado en M3U es un fallback, no la fuente
canónica.

Debe generar una fuente nueva al abrir o renovar el canal. Las respuestas
dinámicas no se guardan en la caché persistente.

### meganoticias

MeganoticiasStreamResolver solo debe activarse mediante el ID o metadato
correspondiente, por ejemplo MeganoticiasAhora.cl; no debe aplicarse a
cualquier canal cuyo nombre contenga “Mega”.

El canal de producción puede traer una URL oficial directa
cdn1tlinkgo.tlink.cl. El resolver antiguo consulta la página de señal en vivo
y el endpoint de token de Mega cuando el ID explícito lo requiere. La URL y
las credenciales dinámicas son efímeras.

### tvvoo y vavoo

El catálogo puede seleccionar el motor tvvoo o vavoo. La app tiene
preferencias para activar:

- ambos motores;
- solo Vavoo directo;
- solo TvVoo externo.

Los grupos se controlan por el identificador del resolutor, no por el nombre
visible del canal.

TvVooStreamResolver consulta aliases estables declarados en M3U mediante
x-resolver-ids y el catálogo. VavooStreamResolver es el motor directo
experimental. Se debe:

- deduplicar aliases;
- probar candidatos de forma limitada;
- priorizar HTTPS;
- validar maestro, variante y segmento;
- cancelar solicitudes al cambiar de canal;
- renovar ante rechazo de autorización o fuente inválida;
- no almacenar la URL temporal final;
- no registrar tokens.

El catálogo experimental de Vavoo está en:

- app/src/main/assets/resolver_vavoo_experimental.json;
- app/src/experimental/assets/resolver_vavoo_experimental.json.

La variante experimental es la que habilita
BuildConfig.ENABLE_EXPERIMENTAL_VAVOO. No asumir que el motor Vavoo
experimental está activo en la APK estable.

### highfly

HighflyStreamResolver soporta:

- canales estables con slug o identidad estable;
- eventos Premium temporales consultados con la credencial;
- varias fuentes HLS declaradas por el proveedor.

Si llegan varios streams Premium, la preferencia actual es ordenar por calidad
y validar en ese orden para que el primer candidato aceptado sea el de mayor
calidad reproducible. No elegir simplemente el que responde primero si la
preferencia es HIGHEST_FIRST.

Las fuentes Premium se validan antes de entregarlas a Media3. Una fuente
temporal fallida no debe quedarse atascada en caché ni reutilizar una firma
vieja.

### 24 horas y pluto

El resolutor de 24 Horas fue retirado. El canal debe comportarse como fuente
directa si la lista actual entrega un enlace reproducible.

Los localizadores Pluto mediante jmp2.uk/plu-... siguen siendo fuentes
directas estables: se permiten redirecciones normales y no se debe convertir la
URL final redirigida en una fuente persistente.

## seguridad de tokens y caché

Reglas obligatorias:

- tokens de TVN, Mega, TvVoo, Vavoo y Highfly solo en memoria durante la
  reproducción;
- credencial Premium persistida únicamente mediante su almacén privado
  previsto, no en la M3U;
- nunca persistir query strings de autorización, firmas, server keys o URLs
  temporales;
- M3uCacheSanitizer debe limpiar fuentes dinámicas antes de guardar una lista;
- no incluir secretos en logs, reportes, analytics, excepciones visibles o
  archivos de diagnóstico;
- al cambiar de canal, cancelar la resolución anterior y descartar cualquier
  callback tardío;
- una respuesta HTTP 401/403/404 o un segmento inválido puede invalidar la
  fuente y activar una renovación limitada;
- nunca crear reintentos infinitos.

ManifestHandoffCache es un traspaso efímero para evitar que una fuente
dinámica sea reutilizada indebidamente. No es una caché permanente de tokens,
segmentos ni claves.

## reproducción, buffer y recuperación

Media3 recibe las cabeceras específicas mediante ResolvedPlaybackSource y los
DataSource configurados. El flujo de reproducción debe confirmar que la
generación, el canal y la identidad todavía son los activos antes de preparar
un nuevo MediaItem.

La app solicita audio focus para reproducción multimedia. La configuración de
Media3 usa atributos de audio de película y manejo de audio focus, de modo que
una app de música pueda pausar al comenzar la reproducción de VibeM3U.

El buffer es administrado por PlaybackBufferManager. La política actual usa
presupuesto adaptativo según memoria, con límites para no consumir toda la TV,
y arranque con aproximadamente tres segundos de contenido después de iniciar
o recuperar. El audit de reproducción documenta el objetivo histórico de
hasta 50 segundos y el presupuesto de bytes adaptativo; verificar el código
actual antes de modificar valores.

El watchdog de carga continua no debe reiniciar durante el arranque normal.
Después de que realmente comenzó la reproducción, una carga sostenida de
aproximadamente cinco segundos puede solicitar recuperación. La política tiene
cooldown y presupuesto para evitar ciclos de reinicio.

Desde el commit 9d49275 existen dos detectores independientes:

### carga intermitente

IntermittentBufferingDetector:

- cuenta transiciones reales reproduciendo → cargando → reproduciendo;
- usa una ventana móvil de cinco segundos;
- requiere cinco ciclos dentro de esa ventana;
- ignora callbacks duplicados, arranque, pausa manual, búsqueda y detención
  intencional;
- reinicia el contador tras reproducción estable;
- comparte la recuperación acotada y el cooldown.

Una pausa breve aislada o uno o dos episodios aislados no deben reiniciar el
canal.

### atasco o desincronización a/v

PlaybackAvSyncDetector observa frames de vídeo reales, avance del audio y
underruns. No depende solamente de currentPosition.

- considera un renderizador atascado tras varias mediciones y alrededor de dos
  segundos sin progreso real;
- ignora pausa, búsqueda, arranque y detención intencional;
- mientras Media3 informa BUFFERING, deja el caso al watchdog de carga;
- si audio y vídeo se detienen juntos por buffering, no lo clasifica como
  desincronización;
- ante un problema intenta primero una resincronización suave del mismo
  elemento y posición;
- si persiste, permite una sola recarga completa;
- aplica cooldown para impedir bucles.

PlaybackRecoveryEpisode limita el presupuesto de recuperación, incluyendo un
intento suave A/V y un intento de recarga completa. Después de unos segundos de
reproducción estable, el episodio puede restablecerse.

Las renovaciones deben responder a errores reales de Media3, pérdida de
segmentos, carga prolongada y atascos post-arranque. No renovar un canal sano
solo porque se recibió un callback repetido.

## diagnósticos y osd

El OSD muestra información compacta del canal, programa, hora y reproducción.
Los datos técnicos deben aparecer como guías cortas, por ejemplo:

    H.264 · AAC · 5.5 Mbps

El bitrate es una estimación reciente de contenido multimedia recibido, no la
velocidad bruta de red. En streams multiplexados se muestra el total para no
confundir video y audio. FPS se mide cuando hay datos reales y se normaliza
en grupos típicos como 24, 30, 50 y 60; antes de tener mediciones se muestra
--- junto con el resto de datos desconocidos.

La interfaz de carga usa una sola línea centrada sobre fondo negro. El texto
describe etapas cortas y técnicas, por ejemplo:

- preparando sesión;
- descargando catálogo;
- analizando coincidencias;
- probando alias;
- solicitando fuente;
- validando playlist;
- validando variante;
- validando primer segmento;
- preparando reproducción.

La línea permanece fija mientras solo cambia la fase de puntos suspensivos.
Una etapa repetida no debe reiniciar su animación.

## interfaz y navegación

La interfaz está diseñada para control remoto Android TV:

- arriba/abajo cambia de canal y soporta pulsación mantenida para desplazarse
  rápidamente;
- OK muestra la información del canal;
- mantener OK abre la guía ligera/programación;
- dentro de la guía, las flechas navegan la programación y no abren las
  opciones generales;
- izquierda abre la EPG ligera cuando corresponde;
- derecha no debe cambiar de pestaña desde un campo o control que aún tenga
  navegación interna;
- la configuración tiene pestañas y navegación por foco;
- dentro de una pestaña, arriba/abajo se mueve por las opciones; al llegar al
  límite superior se vuelve al control de pestañas;
- calidad y subtítulos se editan dentro de Reproducción y corresponden al
  canal actual;
- la calidad automática prioriza el bitrate más alto cuando se selecciona
  automáticamente; si solo existe una resolución, no se muestra una lista
  redundante;
- la opción de subtítulos solo debe mostrarse como disponible cuando se
  observó texto real del stream, no solo una bandera de pista;
- al abrir opciones con reproducción activa, el video debe continuar y solo
  reiniciarse si un cambio realmente lo exige;
- al confirmar salir, se detiene Media3, se liberan tareas, se cierran
  recursos propios y se termina el proceso para evitar que la app quede
  retenida en RAM.

La normalización de volumen procesa el PCM. Cuando está activa puede impedir
el passthrough digital puro; al desactivarla se debe conservar la ruta de
audio original tanto como Media3 y el dispositivo lo permitan.

Hay un icono normal y una variante redonda para launchers de Android TV. No
reemplazar el icono existente sin solicitud explícita.

## pruebas actuales y documentos de referencia

Las pruebas unitarias relevantes cubren:

- parseo M3U y atributos desconocidos;
- EPG y cachés condicionales;
- catálogo y registro de resolutores;
- parsers de payload y reglas Premium;
- Highfly estable y eventos;
- políticas de buffer, watchdog y recuperación;
- medición de bitrate y FPS;
- carga intermitente;
- desincronización A/V;
- texto seguro de diagnóstico.

Pruebas instrumentadas relevantes:

- HighflyPremiumCredentialStoreInstrumentedTest;
- PlaybackHttpInstrumentedTest.

Documentos que siguen formando parte del proyecto:

- README.md: orientación funcional; algunos textos de versión son históricos
  y no sustituyen a app/build.gradle.kts ni al Release actual.
- PLAYBACK_AUDIT.md: auditoría de rendimiento y buffer; úsala como evidencia
  histórica y contrástala con el código actual.
- OSD_THREADING.md: decisiones sobre OSD, scroll y trabajo fuera de la UI.
- RESOLVER_RECIPE_V1.md: contrato de recetas declarativas seguras.
- RESOLVER_VALIDATION_2026-08-24.md: validación anterior de resolutores.
- LISTA_M3U_RESOLVER_CONTRACT.md: contrato entre la app y Lista M3U.
- LISTA_3_HIGHFLY_TRASPASO.md: traspaso de Highfly y sus metadatos.
- CONTEXTO_LISTA_M3U_PARA_VIBEM3U.md: contexto de integración entre
  proyectos.

## procedimiento para continuar el desarrollo

Antes de cambiar algo:

1. confirmar que la tarea corresponde a
   D:/Users/SP4MM3R/Documents/Codex/VibeM3U;
2. revisar git status, rama, remoto, git log y versión real;
3. conservar cambios no rastreados del usuario;
4. identificar si el cambio afecta solo VibeM3U o también requiere una
   propuesta separada para Lista M3U;
5. revisar pruebas existentes y añadir una prueba determinista si el cambio
   toca resolutores, caché, foco, buffer o recuperación;
6. no confundir una lista visible, un catálogo, una URL temporal y una
   credencial;
7. no almacenar ni mostrar tokens;
8. si se solicita publicar, incrementar versión, ejecutar/verificar Actions y
   confirmar Release/APK; si no se solicita, dejar el trabajo sin publicar.

Nunca asumir que:

- una URL temporal de la M3U sigue viva;
- HTTP 200 del master significa que el HLS funciona;
- un logo o una entrada EPG prueban reproducción;
- un callback de Media3 demuestra que el renderizador avanza;
- un catálogo remoto puede ejecutar código;
- la APK está publicada solo porque existe un commit;
- la Lista M3U y VibeM3U comparten el mismo árbol de trabajo.
