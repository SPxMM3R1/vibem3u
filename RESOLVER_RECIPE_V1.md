# Receta declarativa de recuperación de resolutores

Estado del contrato: `schemaVersion: 1`, receta APK
`bounded-payload-v1`, validación `media-signature-v1`.

Este documento es el traspaso técnico para continuar el desarrollo sin mezclar
los repositorios independientes `VibeM3U` y `lista-m3u`.

## Objetivo

VibeM3U puede recuperar candidatos HLS cuando TvVoo conserva el canal y sus
aliases, pero cambia la forma de su respuesta. La lista describe qué receta
de datos solicita; el catálogo remoto la autoriza y limita; la APK contiene la
única implementación ejecutable.

El sistema no descarga ni ejecuta Java, JavaScript, DEX, JAR o scripts. Un
protocolo nuevo, una sesión nueva o una receta nueva exige una APK nueva.

## Activación de dos llaves

Una entrada TvVoo solicita la receta dentro de `#EXTINF`:

```m3u
x-resolver="tvvoo" x-resolver-recipe="bounded-payload-v1"
```

El proveedor `tvvoo` del catálogo debe autorizar exactamente la misma receta:

```json
{
  "recipeId": "bounded-payload-v1",
  "validationMode": "media-signature-v1",
  "maxPayloadDepth": 6,
  "maxExtractedStrings": 256
}
```

La receta se activa solamente si coinciden estas tres identidades:

1. la solicitada por la M3U;
2. la autorizada por el catálogo validado;
3. la compilada dentro de la APK.

Una receta desconocida o una discrepancia falla de forma cerrada. La M3U no
puede habilitar por sí sola una capacidad ejecutable.

## Flujo de resolución

```text
canal y aliases estables
        |
        v
endpoint TvVoo HTTPS permitido
        |
        +--> parser fijo streams[].url
        |
        +--> bounded-payload-v1, si fue autorizada por ambas llaves
                  |
                  +--> JSON anidado o serializado
                  +--> entidades HTML
                  +--> URL encoding
                  +--> Base64 estándar o URL-safe
                  +--> URL HLS absoluta o relativa
        |
        v
candidatos únicos bajo límites estrictos
        |
        v
playlist maestra -> variante -> segmento reciente
        |
        v
firma multimedia -> Media3
```

El parser recorre únicamente valores escalares. Sus límites efectivos son:

- profundidad máxima: 8, configurada actualmente en 6;
- cadenas examinadas: máximo 512, configuradas actualmente en 256;
- candidatos HLS: máximo 32, además del límite del proveedor;
- longitud por valor: 8192 caracteres;
- catálogo remoto completo: 256 KiB;
- máximo de cuatro redirecciones HLS.

## Comprobaciones de seguridad

- Cada URL candidata debe usar HTTP o HTTPS y no puede contener credenciales.
- El host inicial y cada redirección se resuelven y comprueban antes de abrirse.
- Se bloquean loopback, `localhost`, redes privadas, link-local, CGNAT,
  multicast, IPv6 local y direcciones de metadatos como `169.254.169.254`.
- La respuesta debe ser una playlist que empiece por `#EXTM3U`.
- Se sigue una variante y se prueba un segmento reciente.
- Un segmento no cifrado debe tener firma MPEG-TS, fMP4/CMAF, ADTS, MP3 o ID3;
  HTML, JSON, XML y bytes aleatorios se rechazan.
- Las playlists cifradas siguen necesitando un segmento no vacío que no sea un
  documento de error; Media3 conserva la responsabilidad de reproducción.
- Tokens, firmas, URLs de sesión y cabeceras temporales permanecen en RAM y no
  deben entrar en caché persistente, preferencias, diagnósticos o catálogo.

La política reduce ataques por redirección y respuestas falsas. No convierte
una fuente pública en confiable ni intenta romper login, DRM o cifrado.

## Compatibilidad y degradación

- Una APK anterior ignora `x-resolver-recipe` y conserva su parser fijo.
- Una lista sin receta sigue usando el comportamiento anterior.
- Si el parser flexible no encuentra candidatos, TvVoo continúa con el parser
  fijo y luego con el motor Vavoo propio permitido por la configuración.
- La URL debajo de `#EXTINF` sigue siendo el respaldo para reproductores
  externos.
- TVN, Meganoticias, Highfly, Pluto y las fuentes directas no usan esta receta.

## Archivos de implementación

- `ResolverCatalog.java`: valida el catálogo, la receta y el modo permitidos.
- `ResolverDefinition.java`: aplica la autorización de dos llaves.
- `ResolverPayloadParsers.java`: extracción acotada, sin ejecución de código.
- `TvVooStreamResolver.java`: integra parser fijo, receta y fallback.
- `PublicStreamPolicy.java`: bloquea destinos de red no públicos.
- `TokenHttpClient.java`: valida cada redirección HLS antes de conectarse.
- `HlsStreamValidator.java`: valida playlist, variante y firma del segmento.
- `app/src/main/assets/resolver_catalog.json`: catálogo seguro de fábrica.

Las pruebas unitarias cubren payloads anidados, JSON serializado, URL encoding,
Base64, recetas desconocidas, autorización doble, redes no públicas y firmas
multimedia. La compilación y las pruebas Android deben verificarse en GitHub
Actions; este proyecto no usa el entorno local como autoridad de compilación.

## Cómo evolucionar el contrato

Un cambio de aliases, límites o endpoint ya permitido puede publicarse en el
catálogo aumentando `catalogVersion`. Una transformación nueva no puede
reutilizar silenciosamente `bounded-payload-v1`: debe recibir otro identificador,
incorporarse a la lista blanca de la APK, agregar pruebas y publicarse mediante
una actualización normal de VibeM3U.

El repositorio de Lista M3U genera y valida la mitad declarativa del contrato en
`RESOLVER_RECIPE_CONTRACT.md` y `update_m3u.py`.
