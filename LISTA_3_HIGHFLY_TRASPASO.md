# Traspaso a VibeM3U: Lista 3 Highfly y Lista 4 temporal

Fecha de corte: 2026-09-03.

Este archivo acompaña el contrato publicado por el repositorio Lista M3U. La
app no reconstruye la membresia estable desde el catalogo Premium protegido.
La fuente de verdad para los canales estables es la Lista 3 publica.

## Contrato de entrada

VibeM3U consume:

    https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/3.m3u

Cada entrada estable lleva:

    x-resolver="highfly"
    x-resolver-id="<slug>"
    x-highfly-premium-stable="true"
    x-highfly-premium-id="leaf:<slug>"
    x-highfly-premium-kind="estable"
    x-highfly-premium-list="3"
    x-resolver-refresh="on_play"

La URL visible bajo #EXTINF es solamente un localizador leaf publico de
respaldo. Los logos, el tvg-id, los nombres, grupos y la asociacion EPG
llegan desde la lista 3 que mantiene el otro proyecto.

El parser debe conservar los atributos desconocidos. La identificacion no debe
depender de que la URL de respaldo tenga una forma concreta ni de que el nombre
contenga la palabra Highfly.

## Resolucion estable al abrir el canal

El flujo implementado es:

    Lista 3 cacheada
        -> slug leaf
        -> solicitud Premium protegida con la credencial en memoria
        -> fuente HLS actual
        -> validacion
        -> Media3

Para una entrada estable:

1. HighflyPremiumCatalogRepository lee el slug desde los atributos de Lista
   3 y no consulta el catalogo protegido para volver a descubrirlo.
2. Lee la credencial desde HighflyPremiumCredentialStore.
3. Solicita la fuente del identificador leaf:<slug> en el endpoint Premium
   correspondiente a la region seleccionada.
4. Parsea candidatos HLS, valida master/variante/playlist y entrega la fuente
   actual a Media3.
5. ResolvedPlaybackSource solo vive en el flujo de reproduccion. El resolver
   no permite cachear el resultado.

El token se usa unicamente para construir la solicitud protegida en memoria.
No se escribe en 3.m3u, cache de playlists, SharedPreferences, logs,
analytics ni mensajes de error. La URL HLS firmada que devuelve Highfly tiene
la misma politica.

Si la credencial no existe o la solicitud Premium falla, un canal estable puede
probar una vez su localizador leaf publico como respaldo. Un evento temporal
no puede usar ese fallback fabricado.

## Actualizacion de la lista 3

MainActivity incorpora la Lista 3 como una fuente remota independiente:

- se muestra primero la copia cacheada;
- se consulta la cabecera/descarga de forma condicional en segundo plano;
- solo se reemplaza cuando el archivo remoto cambio;
- si el usuario desactiva Premium, la fuente 3 se retira en el siguiente
  ensamblado;
- un cambio de slug no modifica la EPG ni los logos de las otras listas.

La lista 3 no debe depender de que VibeM3U tenga una sesion del catalogo para
aparecer. La credencial es necesaria para reproducir los canales Premium, no
para reconstruir sus metadatos publicos.

## Lista 4 de eventos temporales

La app es la unica responsable de la Lista 4:

- consulta el catalogo protegido solo cuando el selector de eventos esta
  habilitado;
- permite seleccionar identidades de eventos;
- crea placeholders en memoria;
- resuelve cada evento nuevamente al abrirlo;
- coloca los eventos seleccionados despues de todas las listas;
- los retira al cambiar la seleccion, limpiar la sesion o desactivar eventos.

Las preferencias pueden conservar IDs logicos de eventos para recuperar la
seleccion, pero no deben conservar URLs, tokens, posters privados ni
respuestas completas del proveedor.

La lista 3 nunca debe mezclarse con la lista 4 usando una bandera generica:
x-highfly-premium-virtual="true" queda reservado a eventos.

## Clases y limites

| Clase | Funcion |
|---|---|
| HighflyPremiumPreferences | URL publica de Lista 3, region, selector de eventos y orden de candidatos. |
| HighflyPremiumCredentialStore | Credencial protegida y generacion de sesion; nunca expone persistencia en texto plano. |
| HighflyPremiumCatalogRepository | Consulta eventos protegidos y resuelve una fuente Premium al reproducir. Para Lista 3 usa directamente el slug. |
| HighflyPremiumPayloadParser | Parsea manifiesto/catalogo/respuestas de streams con limites y sin regex ejecutable. |
| HighflyPremiumPlaylistMerger | Reemplaza slots Highfly estables por identidad y agrega eventos al final. |
| HighflyStreamResolver | Seleccion explicita del camino Premium y fallback directo controlado. |
| M3uCacheSanitizer | Impide que una URL firmada o token se congele en la cache persistente. |

No agregar un mapa permanente de todos los canales estables dentro de la APK.
Si el runner publica un nuevo slug valido, la app debe poder reproducirlo sin
una actualizacion de la aplicacion, siempre que la forma del endpoint siga
siendo compatible.

## Pruebas sin secretos

Las pruebas deben usar slugs ficticios o publicos, nunca una credencial real:

- 3.m3u conserva todos los atributos de Lista 3;
- un ID streamed: se ignora al generar la salida estable;
- un canal estable no activa la consulta de catalogo;
- una solicitud estable usa leaf:<slug> y no la URL de respaldo como clave;
- el resultado dinamico no se cachea;
- un evento requiere catalogo protegido y no usa el fallback leaf;
- una respuesta 401/403 invalida la credencial de sesion y permite una sola
  renovacion;
- una respuesta tardia no cambia el canal actualmente seleccionado;
- el sanitizador elimina query strings sensibles de la cache.

## Fuente de verdad del otro proyecto

El detalle de generacion, orden, logos, EPG y workflow esta en:

    D:\Users\SP4MM3R\Documents\Codex\Lista M3U\VIBEM3U_LISTA_3_HIGHFLY.md

Su repositorio publica la lista en la rama main. No copiar manualmente sus
canales a codigo Java ni editar el proyecto Lista M3U desde VibeM3U. La
integracion entre ambos proyectos es el contrato M3U y las URLs publicas.
