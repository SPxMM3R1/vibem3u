# Contexto de Lista M3U para VibeM3U

Documento de transferencia entre los dos proyectos locales. Resume los cambios
realizados en el repositorio de Lista M3U que afectan directamente a VibeM3U y
define el contrato que la aplicación debe respetar.

Última revisión del estado local: 2026-08-25.

## Regla principal

Lista M3U y VibeM3U siguen siendo proyectos y repositorios independientes.

- Lista M3U publica datos, metadatos, EPG, logos y automatización.
- VibeM3U consume esos datos y contiene la lógica ejecutable de reproducción y
  de los resolutores.
- Una URL HLS publicada debajo de `#EXTINF` puede ser un respaldo temporal para
  reproductores externos. No es necesariamente la fuente canónica de VibeM3U.
- Los tokens, claves, URLs de sesión y respuestas temporales no deben guardarse
  en la caché persistente de VibeM3U ni convertirse en identificadores de canal.

## Repositorio y URLs públicas

Repositorio local de Lista M3U:

```text
D:\Users\SP4MM3R\Documents\Codex\Lista M3U
```

Repositorio remoto:

```text
https://github.com/SPxMM3R1/lista-m3u
```

Archivos públicos consumidos por VibeM3U:

```text
M3U:
https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/m3u.m3u

EPG XMLTV:
https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/epg.xml

Catálogo declarativo de resolutores:
https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/resolver-catalog.json
```

Estado revisado:

- Rama: `main`.
- Commit actual: `8c1171c11f88b342740809fc5bf56827ac03beff`.
- Mensaje: `Agrega pruebas Vavoo por país con EPG real`.
- La M3U contiene 176 canales.
- 121 canales tienen `x-resolver` explícito.
- Distribución actual de resolutores en la M3U:
  - `tvvoo`: 112 canales.
  - `highfly`: 7 canales.
  - `tvn`: 1 canal.
  - `24horas`: 1 canal.
  - `meganoticias`: 0 canales de producción actualmente; el catálogo conserva
    el soporte para el antiguo ID dinámico.

El repositorio local de Lista M3U también contiene archivos no versionados de
contexto y adjuntos. No deben agregarse accidentalmente a commits de producción.

## Cambio principal: contrato de resolutores

Commit principal:

```text
346fef0 Publica contrato de resolutores para VibeM3U
```

Se añadió un contrato declarativo entre la M3U y VibeM3U. La M3U puede incluir
estos atributos dentro de `#EXTINF`:

```text
x-resolver
x-resolver-endpoint
x-resolver-ids
x-resolver-id
x-resolver-manifest
x-resolver-refresh
```

Ejemplo conceptual, sin URL temporal real:

```m3u
#EXTINF:-1 tvg-id="Canal.uk@TvVoo" tvg-name="Canal" x-resolver="tvvoo" x-resolver-endpoint="https://tvvoo.hayd.uk/stream/tv" x-resolver-ids="alias-estable-1;alias-estable-2" x-resolver-refresh="on_play",Canal
https://url-de-respaldo.m3u8
```

Significado:

- `x-resolver` identifica el proveedor lógico.
- `x-resolver-endpoint` indica el endpoint permitido cuando el motor lo
  necesita.
- `x-resolver-ids` contiene aliases estables separados por `;`.
- `x-resolver-id` contiene un identificador lógico único cuando corresponde.
- `x-resolver-manifest` apunta al manifiesto configurado de Highfly, no a la
  página HTML de configuración.
- `x-resolver-refresh="on_play"` indica que el enlace debe renovarse al abrir
  el canal.

Los aliases estables no son tokens. Los tokens y URLs de sesión que devuelva un
proveedor solo deben vivir en memoria durante la resolución y reproducción.

## Catálogo `resolver-catalog.json`

El catálogo actual usa:

```text
schemaVersion: 1
catalogVersion: 2026.08.25.6
```

Proveedores declarados actualmente:

| ID | Motor | Identificación principal | TTL configurado |
|---|---|---|---:|
| `tvn` | `tvn` | `tvg-id="0104"` | 0 |
| `meganoticias` | `meganoticias` | `tvg-id="MeganoticiasAhora.cl"` | 0 |
| `24horas` | `24horas` | `tvg-id="0201"` | 0 |
| `tvvoo` | `tvvoo` | sufijo `@TvVoo` y algunos IDs explícitos | 900 s |
| `highfly` | `highfly` | host `leaf.highfly.dev` y metadatos explícitos | 300 s |

El catálogo es de datos, no de código. Permite actualizar aliases, endpoints,
matches y parámetros que ya entiende un motor incluido en VibeM3U. No permite
descargar ni ejecutar clases Java, DEX, JAR o código arbitrario.

La validación de Lista M3U rechaza o detecta, entre otras cosas:

- proveedores desconocidos;
- aliases TvVoo faltantes o fuera de orden;
- manifiestos Highfly que apunten a `/configure`;
- hosts no permitidos;
- configuraciones incompatibles con el esquema;
- canales Pluto que hayan recibido un resolutor por error;
- diferencias entre el mapa de aliases del actualizador y el catálogo público.

## Cómo debe usarlo VibeM3U

El flujo esperado es:

```text
M3U
  ↓
atributos x-resolver-* y tvg-id
  ↓
resolver-catalog.json
  ↓
StreamResolverRegistry
  ↓
motor ejecutable de VibeM3U
  ↓
validación HLS
  ↓
Media3 / ExoPlayer
```

Prioridad de identificación:

1. `x-resolver` explícito.
2. `tvg-id` exacto.
3. sufijo o patrón de `tvg-id` declarado por el catálogo.
4. host permitido.
5. nombre del canal únicamente como último recurso.
6. reproducción directa como fallback.

Cuando existe `x-resolver`, VibeM3U debe preferir la resolución dinámica antes
de usar la URL debajo de `#EXTINF`.

## TVN, Meganoticias y 24 Horas

### TVN

Identificación estable:

```text
tvg-id="0104"
x-resolver="tvn"
```

La URL publicada en la M3U se considera respaldo. El motor de VibeM3U debe
consultar la fuente oficial, obtener la autorización actual en memoria y crear
la fuente Media3 con sus cabeceras correspondientes.

### Meganoticias

La producción actual utiliza un canal directo con:

```text
tvg-id="Meganoticias.cl"
```

Ese canal no debe recibir automáticamente el resolutor dinámico antiguo solo
porque el nombre contenga “Mega”.

El catálogo conserva el resolutor para:

```text
tvg-id="MeganoticiasAhora.cl"
```

La activación debe depender del ID explícito o de metadatos del resolutor, no de
una coincidencia amplia por nombre.

### 24 Horas

Identificación estable:

```text
tvg-id="0201"
x-resolver="24horas"
```

El ID del stream se obtiene desde la página oficial cuando es posible. El valor
histórico publicado en el catálogo es solamente un valor predeterminado de
compatibilidad.

## TvVoo y Vavoo

### Qué se implementó en Lista M3U

Los commits relacionados fueron:

```text
47e6672 feat: agrega Premier Sports con renovacion TvVoo
d55082f feat: incorpora TvVoo con EPG real
fa69dd4 feat: incorpora todas las senales TvVoo verificadas
508dc5d restore removed channels and renew Vavoo fallbacks
cad201e Add TvVoo Sky Eurosport and DAZN validation channels
346fef0 Publica contrato de resolutores para VibeM3U
1aa8a0a Agrega canales Vavoo europeos validados
8c1171c Agrega pruebas Vavoo por país con EPG real
```

Sí se agregaron canales Vavoo al proyecto Lista M3U. La aclaración importante
es que el contrato público de la M3U los declara actualmente como
`x-resolver="tvvoo"`, porque el endpoint externo publicado para el catálogo es
TvVoo y porque se mantiene compatibilidad con reproductores externos. Los
aliases utilizados para esas señales son aliases de catálogo Vavoo.

VibeM3U puede resolver esas mismas entradas mediante dos implementaciones:

```text
entrada M3U: x-resolver="tvvoo"
                 |
                 +-- motor TvVoo externo
                 |
                 +-- motor Vavoo propio de VibeM3U
```

Por eso no se publicaron entradas duplicadas como `Canal TvVoo` y `Canal Vavoo`.
El canal lógico, su `tvg-id`, su logo y su EPG permanecen únicos; solamente
cambia el motor que VibeM3U utiliza según la opción seleccionada por el usuario.

El actualizador puede consultar el endpoint público de TvVoo, obtener
candidatos basados en aliases Vavoo, validarlos y publicar una URL HLS de
respaldo para reproductores externos.

El endpoint base declarado es:

```text
https://tvvoo.hayd.uk/stream/tv
```

La URL final devuelta por el proveedor no debe copiarse al catálogo como
identificador permanente. El catálogo conserva solamente aliases, reglas y
configuración.

### Canales incorporados o ampliados

La revisión del 25 de agosto dejó 47 señales Vavoo adicionales publicadas bajo
el contrato `tvvoo`, con `tvg-id`, logos, aliases y respaldo HLS validado.
Se distribuyen en Reino Unido, Italia, Francia, Alemania, Portugal, España,
Polonia, Países Bajos, Turquía, Balcanes, Rusia, Rumanía, Bulgaria, Albania y
MENA.

Las familias añadidas incluyen BBC, Sky Sports, TNT Sports, Bloomberg,
Eurosport, DAZN, RT, XITE, Stingray, Sport TV, Eleven Sports, ESPN, CNN,
Arena Sport, SuperSport, Digi Sport, Max Sport y beIN Sports.

La tanda anterior incorporó y validó familias como:

- CNN.
- MTV Hits.
- M6 Music.
- Trace Urban.
- Sky Sports Main Event.
- Sky Sports+ / alias Arena.
- TNT Sports 3.
- ESPN 3.
- Eurosport 1.
- RMC Sport 1 y 2.
- DAZN 2.
- Sport TV 1 y 2.
- Eleven Sports 1 y 2.
- RT France.
- DAZN FAST+.
- varias señales Sky, DAZN y Eurosport por país.

La lista no duplica una misma señal por cada país o alias. El `tvg-id` debe
seguir identificando la señal lógica y la EPG debe usar ese mismo ID.

En términos de contenido, el proyecto Lista M3U quedó con un grupo de 112
canales TvVoo/Vavoo. “TvVoo” identifica el contrato y el endpoint externo de
publicación; “Vavoo” identifica la fuente de aliases y el motor propio que puede
utilizar VibeM3U. No son 112 canales de TvVoo más otros 112 canales Vavoo: es un
solo grupo lógico de canales con dos posibles motores de resolución.

## Modo TvVoo externo y Vavoo propio en VibeM3U

La M3U usa actualmente `x-resolver="tvvoo"` para este grupo de canales. La
versión actual de VibeM3U puede elegir en la aplicación entre:

- automático;
- Vavoo propio;
- TvVoo externo.

Esto no requiere duplicar las entradas en la M3U. La preferencia decide qué
motor ejecutable utiliza VibeM3U para el grupo.

El catálogo de Lista M3U sigue describiendo el contrato público `tvvoo`. La
configuración Vavoo propia es una implementación del reproductor y puede
aparecer como overlay experimental de VibeM3U. No se deben publicar aliases
temporales ni URLs `127.0.0.1` del proyecto de referencia Vavoo.

## Highfly

Se conservó Highfly como proveedor independiente. Los canales actuales y sus
slugs lógicos son:

| `tvg-id` | `x-resolver-id` |
|---|---|
| `SkySportsF1.uk` | `now-sky-sports-f1-free` |
| `ESPN.us` | `us-espn-hd` |
| `MarqueeSportsNetwork.us` | `us-marquee-sports-network-hd` |
| `SkySportsPremierLeague.uk` | `now-sky-sports-premier-league` |
| `SkySport1.nz` | `nz-sky-sport-1` |
| `SkySportsTennis.uk` | `now-sky-sports-tennis` |
| `SkySportsGolf.uk` | `vip-sky-sports-golf` |

El manifiesto configurado se utiliza como fuente de renovación. La página
`sports.highfly.dev/configure` no debe consultarse durante cada reproducción.

## EPG

La EPG se mantiene separada de la URL HLS y se asocia mediante `tvg-id`.

Se ampliaron las fuentes y asociaciones XMLTV para incluir, entre otras:

- Reino Unido `uk1`.
- Argentina `ar1`.
- Portugal `pt1`.
- Nueva Zelanda `nz1`.
- Estados Unidos `us2`.
- Pluto TV.
- Francia, Alemania, Italia, España y otras fuentes ya usadas por los canales
  TvVoo.

La lógica de `update_m3u.py` asigna cada señal a una entrada XMLTV concreta. No
se debe reutilizar una parrilla de otro canal solo porque tenga un nombre
parecido.

Si no existe programación real para un canal, se marca como sin guía o se
excluye la parrilla inventada. En particular, MCM fue tratado explícitamente
como canal sin bloques vigentes cuando la fuente EPG no entregó programación
real. No se debe fabricar continuidad genérica para ocultar ese problema.

El estado publicado contiene 176 nodos de canal y 11.544 programas. Todos los
IDs de la M3U tienen un nodo correspondiente en XMLTV. En las 47 pruebas Vavoo
nuevas, 22 tienen parrilla real identificada y 25 están marcadas
explícitamente como `sin guía`.

### Diagnóstico de una vista de solo 64 canales

La M3U y la EPG no están limitadas a 64: la comprobación remota devuelve 176
canales y 176 IDs XMLTV. VibeM3U filtra los canales en
`MainActivity.applyPlaylist()` cuando el grupo de un resolutor está
desactivado en las preferencias. Como existen 112 canales `tvvoo`, si esa
preferencia está en `false`, el resultado visible es exactamente:

```text
176 canales totales - 112 canales TvVoo = 64 canales visibles
```

Para mostrar la lista completa hay que activar `TvVoo` en
`Opciones > Resolutores > Grupos de canales` y guardar. La preferencia no se
anula automáticamente, porque desactivar un grupo es una opción deliberada de
la aplicación. El catálogo incluido de fábrica se actualizó a la versión
`2026.08.25.6`, por lo que una instalación nueva ya conoce los 112 aliases;
una instalación existente puede actualizarlo desde `Actualizar resolutores`.

## Pluto TV y canales directos

Los canales Pluto mediante `https://jmp2.uk/plu-...` se mantienen como
localizadores directos. No deben recibir automáticamente el resolutor TvVoo ni
convertirse en URLs finales permanentes.

La lógica de la lista incorpora EPG de Pluto para sus canales lineales y deduplica
tarjetas repetidas antes de generar el XMLTV.

Los canales directos normales siguen sin `x-resolver`. VibeM3U debe reproducirlos
con su flujo directo y no aplicarles heurísticas de nombre que los conviertan en
TvVoo, Vavoo o Highfly.

## Automatización y publicación

El actualizador principal es:

```text
update_m3u.py
```

La ejecución coordinada de 48 horas utiliza:

```text
run_m3u_48h.py
```

El workflow de GitHub Actions fue ajustado para considerar también
`resolver-catalog.json` como salida de producción. Ahora:

- publica M3U, EPG y catálogo como una unidad lógica;
- comprueba que los archivos Raw coincidan con el commit publicado;
- comprueba que `resolver-catalog.json` tenga `schemaVersion: 1`;
- restaura los tres archivos anteriores si la actualización falla;
- no publica un catálogo nuevo sin comprobarlo.

La ejecución local equivalente debe validar el contrato con las opciones
existentes de `update_m3u.py`, especialmente:

```text
--sync-resolver-contract
--validate-resolvers-only
```

La automatización del repositorio Lista M3U no sustituye a la resolución de
VibeM3U. Su función es mantener el catálogo, la EPG, los logos y una URL de
respaldo. VibeM3U debe renovar fuentes dinámicas justo antes de reproducir.

## Reglas de compatibilidad para VibeM3U

1. `M3uParser` debe conservar todos los atributos `x-resolver-*`.
2. `tvg-id` debe permanecer estable aunque cambie la URL HLS.
3. Los resolutores deben preferir los metadatos explícitos de la M3U.
4. `resolver-catalog.json` debe cargarse como configuración de datos validada.
5. Un catálogo remoto no puede habilitar hosts arbitrarios ni código remoto.
6. Las URLs dinámicas deben renovarse al abrir el canal y ante rechazo de
   autorización o fallo de segmento.
7. Una URL temporal antigua no debe reutilizarse como retry genérico después de
   confirmar que caducó.
8. Los tokens y query strings sensibles no deben escribirse en caché, logs,
   analytics, preferencias ni archivos de diagnóstico.
9. Un resolutor desactivado por el usuario debe afectar solamente a su grupo.
10. Un canal directo, Pluto o Meganoticias de producción no debe ser alterado
    por una heurística amplia de nombre.
11. La EPG debe seguir usando el `tvg-id`, nunca la URL temporal.
12. Si un proveedor desconocido aparece en una futura M3U, la app debe
    conservar el canal como fallback controlado o mostrarlo como no disponible,
    sin bloquear los demás canales.

## Qué cambios requieren APK y cuáles no

No requiere nueva APK:

- cambiar un alias estable;
- agregar un canal que utilice un motor ya incluido;
- modificar el grupo, logo o nombre;
- corregir una asociación EPG;
- actualizar un endpoint que el motor ya permite;
- modificar la configuración declarativa del catálogo.

Sí requiere nueva APK:

- crear un parser o protocolo nuevo;
- cambiar el algoritmo de sesión Vavoo;
- modificar la detección de expiración;
- agregar un proveedor con una API nueva;
- cambiar la forma de aplicar cabeceras a Media3;
- cambiar el motor de validación HLS;
- modificar la política de renovación y reanudación.

## Validaciones realizadas en Lista M3U

Los cambios del contrato incorporaron validaciones para:

- conservar `tvg-id` y atributos del canal;
- comparar aliases contra el mapa autoritativo del actualizador;
- comprobar que cada canal TvVoo tenga aliases estables;
- comprobar que Highfly tenga slug y manifiesto válidos;
- impedir que Highfly use la página `/configure` como manifiesto de reproducción;
- impedir que Pluto reciba `x-resolver` por error;
- comprobar que el catálogo tenga esquema y proveedores válidos;
- comprobar que los archivos publicados desde Raw correspondan al commit;
- validar maestros, variantes y primeros segmentos HLS antes de publicar
  respaldos cuando el proveedor lo permite.

La validación en Lista M3U no debe interpretarse como garantía permanente de que
una URL dinámica seguirá viva horas después. TvVoo/Vavoo y Highfly entregan
fuentes efímeras; la renovación de tiempo de reproducción corresponde a
VibeM3U.

## Resumen para el desarrollo de VibeM3U

La Lista M3U ya está preparada para que VibeM3U funcione como reproductor
autosuficiente:

```text
M3U con metadatos estables
        +
EPG asociada por tvg-id
        +
resolver-catalog.json declarativo
        +
URL HLS de respaldo para reproductores externos
        +
resolución fresca dentro de VibeM3U
```

La aplicación no debe depender de que GitHub haya renovado recientemente la URL
temporal publicada en la M3U. Debe identificar el proveedor, resolver la fuente
actual, validarla, reproducirla y descartar el resultado cuando el canal cambie
o la sesión expire.

Este documento no contiene tokens ni URLs HLS temporales deliberadamente.
