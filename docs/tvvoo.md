# TvVoo en VibeM3U

TvVoo es una fuente optativa de canales. En `Opciones > Fuente`, activa
`TvVoo · usar canales seleccionados` y abre `Abrir catálogo TvVoo`. El catálogo
se carga por país desde el manifest público, conserva una copia local y permite
buscar, filtrar por categoría y marcar varios canales. `Aplicar` guarda el
orden; `Cancelar` abandona los cambios de esa pantalla. Si una actualización
de red falla, la selección y la última copia válida permanecen disponibles.

## Entrada M3U

El botón `Copiar M3U` copia entradas de identidad que pueden pegarse en una
lista existente:

```m3u
#EXTINF:-1 tvg-id="unitedkingdom|vavoo_SKY%201%7Cgroup%3Auk@TvVoo" tvg-logo="https://example.com/sky.png" x-resolver="tvvoo" x-resolver-id="vavoo_SKY%201%7Cgroup%3Auk" x-resolver-ids="vavoo_SKY%201%7Cgroup%3Auk" x-resolver-country="unitedkingdom" x-resolver-refresh="on_play",SKY 1
tvvoo://channel/unitedkingdom%7Cvavoo_SKY%25201%257Cgroup%253Auk
```

La URI `tvvoo://` sólo identifica el canal. VibeM3U renueva la fuente al
abrirlo; la entrada no contiene URL HLS temporal, token, contraseña ni una
fuente resuelta. `tvg-logo` es opcional y se conserva como metadato editable en
el M3U.

`tvg-id` usa `stableId@TvVoo`, donde `stableId` es
`countryKey|canonicalAlias`. `canonicalAlias` conserva el alias TvVoo con el
payload completo (`NOMBRE|group:país`) codificado para el endpoint. El path de
la URI codifica de nuevo el `stableId`, por lo que los signos `%` aparecen como
`%25`.

La posición del canal es la posición de la entrada en el archivo M3U. Al
combinar una lista M3U con la selección local, la entrada M3U es la autoridad
para nombre, logo y orden; una identidad duplicada no se agrega dos veces y
los canales seleccionados que no estén en el archivo se añaden al final con
numeración densa (`base + 1`).

La selección TvVoo y el motor resolutor son controles independientes. Activar
la fuente seleccionada mantiene habilitado el motor TvVoo para evitar una
lista vacía; desactivar la fuente conserva la selección para volver a usarla.

## MediaFlow Proxy

`Configurar MediaFlow Proxy` abre una pantalla independiente. Su switch puede
quedar desactivado; al desactivarlo, el origen y la contraseña cifrada se
conservan y la reproducción TvVoo vuelve al flujo directo. MediaFlow sólo se
aplica a canales TvVoo y requiere una instancia configurada por el usuario en
su propia red o servidor. El catálogo y el switch de fuente no guardan la
contraseña.

La implementación queda validada por pruebas estáticas y unitarias del
proyecto. La apertura real en Android TV y una sesión sostenida a través de un
servidor MediaFlow requieren probarse en el dispositivo y con la instancia
del usuario.


## Recuperación 0.6.59 — 2026-11-06

La instancia pública fue retirada. `TvVooStreamResolver.queryAlias` consulta ahora
`VavooSessionClient` para los alias cuyo endpoint es el antiguo host público.
Se conservan `TvVooFastRace`, las versiones publicadas, el foco, los alias elegidos,
la medición y la búsqueda de mejor calidad. El engine continúa siendo `tvvoo`.
No se añaden canales desde el catálogo interno del resolutor.

El protocolo sigue el código oficial de TvVoo (cliente 3.1.0, ping Vypn,
language de/region AT, UA diferentes para catálogo y resolución). Esos campos
son parámetros del protocolo: no se falsea la IP ni se reescribe la firma.
Las sesiones y los destinos se guardan solo en RAM. La URL HTTP publicada se
valida directamente; un 403/502 no provoca un intento TLS en ese puerto.
No se desactiva la comprobación de certificados HTTPS.

MediaFlow descubre los originales en el catálogo Vavoo y usa el extractor
configurado por el usuario. La contraseña del proxy nunca va al proveedor.
Validado con pruebas de protocolo; no había un MediaFlow real configurado para
una prueba de reproducción con proxy.

Contraparte Lista M3U: 08abe33 + ce96409. Conserva los seis IDs y la numeración;
las seis fuentes públicas son referencias tokenless. La salud identity_only
indica validez de identidad, nunca una señal comprobada.

Última prueba Android 2026-11-07 02:21–02:22 UTC: Main Event, TNT 3, TNT 4 y
Eurosport 1 superaron HLS/segmento en 4–5,3 s. DAZN F1 y Eurosport 2 fallan en
origen (502/403); no se sustituyen por señales de otro país ni se cambia la EPG.
TNT 3 y 4 mostraron video real de rugby/cricket en VibeTV. La referencia local
Main Event mostró Sky Sports News: un alias del proveedor no acredita el feed.
El servidor Node de comparación fue apagado y no es dependencia de la TV.

Pruebas: VavooProtocolTest, VavooSessionClientTest, TvVooVariantFallbackTest;
`VavooLiveInstrumentedTest` es optativo y exige una M3U en
`/data/local/tmp/vavoo-live-test.m3u` (opcional archivo de variantes al lado).
No registra ni guarda enlaces resueltos. Un test HLS no sustituye la inspección
visual del contenido.
