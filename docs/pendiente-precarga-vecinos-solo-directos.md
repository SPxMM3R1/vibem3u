# Pendiente: preparar solo los vecinos directos

Estado: **por implementar** (instrucciones escritas el 2026-11-24, sobre `main` = 0.6.73).
Decisión del usuario: «Por las características debería ser solo para directos». Este documento
es la especificación completa; quien lo implemente no necesita la conversación original.

## Por qué

Cuando un canal ya se está viendo, `MainPlayback.prefetchNeighborChannels()` prepara el canal
anterior y el siguiente. Así funciona hoy (0.6.73):

- **Vecino directo** (sin resolutor): `warmConnection(neighbor)` pide 1 KB con `Range` para que
  OkHttp deje la conexión abierta. Es barato y útil.
- **Vecino con resolutor y caché** (`cacheResolvedSource() && cacheTtlMillis() > 0`: TvVoo/Vavoo
  con 1500 s, MediaFlow TvVoo, Meganoticias y otros): `resolverCoordinator.resolve(...)` completo
  en segundo plano. Para Vavoo eso significa firma o índice live2, resolución y validación de la
  lista y de un segmento HLS. Son varios segundos de red y CPU por cada vecino.
- **Highfly**: no se prepara (`cacheResolvedSource() == false`).

Problemas de preparar vecinos con resolutor:

1. Compite con el canal que se está viendo en una TV de 2 GB (Hisense): red, CPU y GC.
2. Cada canal Vavoo que se mira dispara dos resoluciones Vavoo más desde la misma IP, con riesgo
   de límites por IP o sesión.
3. Casi siempre se descarta: la mayoría de las veces el usuario no va al vecino.
4. La 0.6.73 (`9524f59`) intentó arreglar la acumulación cancelando la preparación al cambiar de
   canal, y eso trajo dos errores nuevos:
   - `playChannel()` llama a `cancelNeighborPrefetch()`. Si el usuario hace CH+ al vecino que
     todavía se está resolviendo, la apertura se une a esa resolución (`ResolverCoordinator`,
     mapa `inFlight` → `await(existing)`), y al cancelarse el contexto recibe «Solicitud
     cancelada» o empieza de cero. La preparación termina estorbando justo cuando sirve.
   - Los dos vecinos comparten un solo `ResolutionContext(12_000L)` creado al programar. Como
     `prefetchExecutor` tiene un solo hilo, el segundo vecino empieza cuando termina el primero y
     puede quedarse sin plazo.

Lo que se pierde al quitarlo es poco: CH+ entre dos canales Vavoo seguidos tardará lo normal
(~4 s con el índice y la sesión ya en memoria) en vez de abrir al instante.

## Qué hacer

Todo está en `app/src/main/java/cl/streambox/tv/`.

### 1. `MainPlayback.prefetchNeighborChannels()`: solo directos

Dejarlo así (manteniendo las condiciones de salida que ya tiene):

```java
/**
 * Precarga de vecinos (0.5.85; solo directos desde 0.6.7x): con el canal estable deja abierta
 * la conexión del anterior y del siguiente. Los canales con resolutor (TvVoo/Vavoo, MediaFlow,
 * Meganoticias…) no se resuelven por adelantado: cada uno son varios segundos de red y CPU que
 * competían con el canal en pantalla, casi siempre se descartaban, y en Vavoo sumaban pedidos
 * desde la misma IP. Pedido del usuario (2026-11-24): «debería ser solo para directos».
 */
void prefetchNeighborChannels() {
    if (channels.size() < 2 || player == null || !playbackHasStarted || settingsOpen
            || exiting || resourcesReleased || streamResolverRegistry == null) return;
    for (int delta : new int[] {1, -1}) {
        int index = ((channelIndex + delta) % channels.size() + channels.size()) % channels.size();
        Channel neighbor = channels.get(index);
        if (neighbor == null || neighbor == playbackChannel) continue;
        if (streamResolverRegistry.find(neighbor) != null) continue;
        prefetchExecutor.submit(() -> warmConnection(neighbor));
    }
    // El pool cierra conexiones inactivas a los 5 min: se renuevan mientras se mira el canal.
    mainHandler.postDelayed(prefetchNeighbors, NEIGHBOR_REWARM_MS);
}
```

- Un vecino es «directo» cuando `streamResolverRegistry.find(neighbor) == null`. Es el mismo
  criterio que usa hoy la rama `else`. No inventar otro.
- `warmConnection` no cambia (1 KB, `callTimeout` 6 s, errores ignorados).

### 2. Quitar el corte de la 0.6.73, que ya no hace falta

Sin resoluciones de vecinos no hay nada que cortar:

- `MainState`: borrar los campos `neighborPrefetchContext` y `neighborPrefetchGeneration`, con su
  comentario.
- `MainPlayback`: borrar el método `cancelNeighborPrefetch()` y sus llamadas en
  `scheduleNeighborPrefetch()`, `playChannel()` y en la pausa por pérdida de foco (cerca de
  `restartPlaybackAfterFocusLoss = true; cancelPlaybackResolution();`).
- `MainActivity`: borrar la llamada `cancelNeighborPrefetch();` en el cierre (junto a
  `cancelPlaybackResolution(); resolverCoordinator.clear();`).
- **Conservar** `mainHandler.removeCallbacks(prefetchNeighbors)` en `scheduleNeighborPrefetch()`
  y `playChannel()`: evita que se programe dos veces. Conservar también `scheduleNeighborPrefetch()`
  en `markPlaybackStarted()` y al cerrar la configuración (`MainActivity`, «Con la configuración
  abierta la precarga de vecinos se detiene»).
- Comprobar con `grep -rn "neighborPrefetch\|cancelNeighborPrefetch" app/src` que no queda nada.

### 3. No olvidar las versiones Vavoo caídas por presión de memoria (recomendado)

`MainOverlays.handleMemoryPressure()` llama a `TvVooDeadVersions.clear()` desde la 0.6.73. Ese
mapa es diminuto (versión → hasta cuándo está caída) y vaciarlo hace que la app vuelva a probar
versiones que ya sabía muertas, lo que alarga la apertura. Quitar esa línea y el método `clear()`
de `TvVooDeadVersions` si queda sin uso (`resetForTests()` se queda). **Conservar**
`VavooSessionClient.clearMemoryCaches()`: el índice live2 sí pesa varios MB.

### 4. Lo que NO se toca

- `warmLastChannel()` (arranque): sigue resolviendo el último canal visto aunque tenga resolutor.
  Es el canal que se va a abrir y la apertura comparte esa resolución. No es preparar un vecino.
- Las mejoras de índice de la 0.6.73 en `VavooSessionClient` (`parseLive2` por objetos,
  `CatalogEntry` con campos precalculados, `rankedMatches` sin `normalizedName`/`countryKey`
  por entrada). Están bien; no revertirlas.
- `ResolverCoordinator`, la caché de 1500 s de TvVoo y el zapeo continuo (`zapBy` y
  `commitZap` solo abren el canal final).
- No agregar precarga de video (`DefaultPreloadManager`): ver AGENTS.md, «Velocidad (0.5.87)».

## Pruebas

### Unitarias

Para poder probarlo, sacar la elección a una función pura en `MainPlayback` (o en una clase chica
nueva, p. ej. `NeighborPrefetch`):

```java
/** Vecinos (siguiente y anterior) que se precalientan: solo directos, sin repetir ni el actual. */
static List<Channel> directNeighbors(List<Channel> channels, int channelIndex,
        Channel playing, java.util.function.Predicate<Channel> hasResolver)
```

`prefetchNeighborChannels()` la usa con `ch -> streamResolverRegistry.find(ch) != null`. Prueba
nueva (`app/src/test/java/cl/streambox/tv/NeighborPrefetchTest.java`):

- Lista [directo A, Vavoo B, directo C] viendo B → devuelve [C, A] (siguiente y luego anterior).
- Viendo A con vecinos Vavoo → lista vacía.
- Lista de 2 canales → el mismo vecino una sola vez (con 2 canales, +1 y −1 son el mismo).
- Lista de 1 canal o vacía → vacía.
- Índice al borde (primero y último) → vuelve al otro extremo, como hoy.

Correr `./gradlew :app:testDebugUnitTest :app:testExperimentalUnitTest :local-catalog:test lint`
(el CI corre `testExperimentalUnitTest`).

Ojo: `TvVooVariantFallbackTest.siblingPlaysWhenTheChosenVersionHasNoSource` falló una vez en el
CI (corrida 45505052650, commit `32ed850`, que solo cambió documentación) y pasó en las otras 29
corridas. Si vuelve a fallar, revisar el estado estático compartido entre pruebas (`LIVE2_LOCK`,
`signedDownUntil` de `VavooSessionClient`, `TvVooDeadVersions`) antes de culpar a este cambio, y
no desactivar la prueba.

### Emulador VibeTV (obligatorio antes de publicar; cerrarlo al terminar)

1. Instalar el APK debug y abrir un canal **directo** con vecinos directos (p. ej. los
   Nacionales). Esperar más de 5 s (`NEIGHBOR_PREFETCH_DELAY_MS`). CH+ y CH− abren rápido, igual
   que antes.
2. Ver un canal **Vavoo** de Lista 1 (Sky Sports Main Event, TNT Sports 3/4, DAZN F1, Eurosport 2
   están seguidos en Deportes). La app no registra las resoluciones en logcat: para comprobarlo,
   agregar **temporalmente** un `android.util.Log.d("TMPPREFETCH", ...)` en
   `prefetchNeighborChannels()` (vecino elegido) y al inicio de `ResolverCoordinator.resolve`
   (canal pedido). Durante 20 s sin zapear, solo deben aparecer vecinos directos y ninguna
   resolución de otro canal. **Quitar esos logs antes del commit** (`grep -rn TMPPREFETCH app/src`).
3. Desde ese canal Vavoo, CH+ al vecino Vavoo: tiene que abrir sin «Solicitud cancelada» y en
   ~4 s. Repetir CH+, CH−, CH+ rápido (zapeo continuo): abre solo el canal final.
4. Zapear de un Vavoo a un directo y volver: sin errores ni pantallas de fallo.
5. HOME y volver a la app: el último canal abre (sigue `warmLastChannel`).
6. Cerrar el emulador.

## Documentación (mismo commit)

- `ESTADO.md`: entrada en «Hoy» con versión y motivo (pedido del usuario y los dos errores de la
  0.6.73), y una línea en «Bitácora». Al terminar, **borrar este archivo** o marcarlo como hecho
  y apuntar a la entrada de ESTADO.md.
- `AGENTS.md`: en «Velocidad (0.5.85)», cambiar «los vecinos se precargan con el canal estable»
  por «solo los vecinos directos se precalientan (conexión abierta) con el canal estable; los de
  resolutor no se resuelven por adelantado (0.6.7x)». En «Velocidad (0.5.87)» mantener la regla de
  no precargar video. En la línea de la 0.6.71/0.6.73 de Vavoo, dejar intacta la regla del índice.
- Versión: el número lo elige el usuario; preguntarle antes de subir `versionCode`/`versionName`
  en `app/build.gradle.kts`.

## Publicación

Seguir las reglas del repo: CI verde, prueba en el emulador VibeTV, tag y release con el APK. Si
falta algo, decirlo en vez de publicar.
