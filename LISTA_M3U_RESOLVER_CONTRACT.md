# Contrato de Lista M3U para los resolutores de VibeM3U

Este documento es el traspaso técnico para el repositorio independiente
`SPxMM3R1/lista-m3u`. No requiere copiar código Android ni mezclar historiales Git.

## Objetivo

La M3U debe seguir incluyendo una URL de respaldo para reproductores externos,
pero VibeM3U debe recibir identificadores estables suficientes para resolver una
fuente nueva justo antes de reproducirla. Ningún token, URL de sesión, `serverKey`
o query de autorización debe publicarse dentro del catálogo de resolutores.

## Archivos que debe publicar Lista M3U

1. `m3u.m3u`, con los atributos `x-resolver-*` descritos abajo.
2. `resolver-catalog.json`, en la raíz de la rama `main`.

VibeM3U consulta el catálogo en:

`https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/resolver-catalog.json`

El archivo inicial puede copiar la estructura de
`app/src/main/assets/resolver_catalog.json`. Debe conservar:

- `schemaVersion: 1`;
- una `catalogVersion` ascendente, por ejemplo `2026.08.24.2`;
- solamente los motores que VibeM3U reconoce: `tvn`, `meganoticias`,
  `24horas`, `tvvoo` y `highfly`;
- endpoints HTTPS de los hosts permitidos por la app;
- IDs, coincidencias, aliases y reglas estables, nunca respuestas temporales.

El catálogo es declarativo: actualiza identificación, endpoints, plantillas,
patrones acotados, TTL y aliases. No contiene Java, JavaScript, DEX ni código
ejecutable. Un motor nuevo sigue requiriendo una actualización normal de la APK.

## Orden de identificación

La aplicación usa este orden y la M3U debe aprovecharlo:

1. `x-resolver` explícito;
2. `tvg-id` exacto;
3. sufijo estable del `tvg-id`;
4. host conocido;
5. fuente directa.

No identificar proveedores por una coincidencia parcial del nombre visible.

## TvVoo

Por cada entrada que utilice `TVVOO_STREAM_RESOLVER_IDS`, `update_m3u.py` debe
emitir los aliases de ese mapa dentro de `x-resolver-ids`, en el mismo orden y
separados por `;`:

```m3u
#EXTINF:-1 tvg-id="SkySportsNFL.uk@TvVoo" tvg-name="Sky Sports NFL" tvg-country="GB" x-resolver="tvvoo" x-resolver-endpoint="https://tvvoo.hayd.uk/stream/tv" x-resolver-ids="vavoo_SKY%20SPORTS%20NFL%7Cgroup%3Auk;vavoo_SKY%20SPORTS%20NFL%20HD%7Cgroup%3Auk" x-resolver-refresh="on_play" group-title="PRUEBA - Deportes - Sky",Sky Sports NFL
https://URL_TEMPORAL_DE_RESPALDO/hls/index.m3u8
```

Reglas:

- `x-resolver="tvvoo"` es obligatorio para las nuevas entradas.
- `x-resolver-ids` contiene aliases estables, no URLs ni tokens.
- Los aliases pueden mantenerse URL-encoded como ya aparecen en
  `TVVOO_STREAM_RESOLVER_IDS`; VibeM3U evita codificarlos dos veces.
- La URL de la línea siguiente es solo compatibilidad para otros reproductores.
- No crear un canal adicional por cada alias o país.
- Premier Sports 1, Premier Sports 2 y Sky Sports Racing también deben recibir
  metadatos explícitos aunque conserven su `tvg-id` histórico.

## Highfly

Cada canal debe transportar un slug estable y la URL final del `manifest.json`
generado por Highfly:

```m3u
#EXTINF:-1 tvg-id="SkySportsF1.uk" tvg-name="Sky Sports F1" x-resolver="highfly" x-resolver-id="now-sky-sports-f1-free" x-resolver-manifest="https://HOST_PERMITIDO/RUTA/manifest.json" x-resolver-refresh="on_play",Sky Sports F1
https://leaf.highfly.dev/m3u/now-sky-sports-f1-free/live.m3u8
```

Reglas:

- `x-resolver-id` es el slug estable.
- `x-resolver-manifest` debe ser el manifiesto final, no la página HTML
  `https://sports.highfly.dev/configure`.
- La URL `leaf.highfly.dev` queda como respaldo, no como dirección permanente.
- Si el manifiesto se publica en GitHub Raw, debe estar dentro de
  `SPxMM3R1/lista-m3u`.

## TVN

```m3u
#EXTINF:-1 tvg-id="0104" x-resolver="tvn" x-resolver-refresh="on_play",TVN
https://URL_DE_RESPALDO_DE_TVN.m3u8
```

No publicar `access_token`. VibeM3U lo obtiene desde `https://live.tvn.cl/` y lo
mantiene únicamente en RAM durante esa reproducción.

## 24 Horas

```m3u
#EXTINF:-1 tvg-id="0201" x-resolver="24horas" x-resolver-refresh="on_play",24 Horas
https://mdstrm.com/live-stream-playlist/57d1a22064f5d85712b20dab.m3u8
```

El ID de la URL puede seguir siendo un respaldo para reproductores externos.
VibeM3U consulta la página oficial y extrae el `data-ms` activo al reproducir.

## Meganoticias

El canal de producción actual con `tvg-id="Meganoticias.cl"` y URL oficial
directa debe seguir siendo directo. No agregarle `x-resolver="meganoticias"`.

El motor dinámico solo corresponde al identificador histórico explícito:

```m3u
#EXTINF:-1 tvg-id="MeganoticiasAhora.cl" x-resolver="meganoticias" x-resolver-refresh="on_play",Meganoticias Ahora
https://URL_DE_RESPALDO.m3u8
```

No aplicar esta regla a Mega ni a otros canales cuyo nombre contenga “Mega”.

## Pluto y fuentes directas

Los localizadores `https://jmp2.uk/plu-...m3u8` y las fuentes directas no deben
recibir `x-resolver`. Media3 seguirá sus redirecciones normalmente y conservará
el `tvg-id` para la EPG.

## Cambios concretos en `update_m3u.py`

1. Al construir cada `#EXTINF`, consultar `TVVOO_STREAM_RESOLVER_IDS` y emitir
   `x-resolver`, `x-resolver-endpoint`, `x-resolver-ids` y
   `x-resolver-refresh`.
2. Añadir metadatos explícitos para TVN y 24 Horas.
3. Para Highfly, mantener un mapa `tvg-id -> slug -> manifest final` y emitirlo.
4. Mantener Meganoticias de producción como fuente directa.
5. Generar y validar `resolver-catalog.json` en la automatización.
6. No copiar respuestas `streams[].url`, tokens o claves al catálogo.
7. Mantener la URL temporal de la M3U únicamente como compatibilidad externa.

## Validación antes de publicar

La automatización debe comprobar:

- todos los canales dinámicos tienen un `tvg-id` estable;
- todo `x-resolver` pertenece a la lista permitida;
- TvVoo tiene al menos un alias y no contiene `/sunshine/` en atributos;
- Highfly tiene slug y manifiesto configurado;
- el catálogo es JSON válido, usa esquema 1 y aumenta su versión;
- no aparecen `access_token`, `serverKey`, tokens ni URLs de sesión en el
  catálogo;
- la M3U conserva exactamente una entrada por canal y la EPG conserva sus IDs;
- las URLs de respaldo siguen validándose como master -> variante/media ->
  primer segmento cuando estén disponibles.

## Compatibilidad y despliegue

La app incluye un catálogo seguro de fábrica. Si `resolver-catalog.json` aún no
existe o falla su validación, conserva el catálogo instalado y la reproducción
normal. Una vez publicado el archivo remoto, el usuario puede entrar a
`Opciones > Resolutores > Actualizar resolutores` para instalar reglas nuevas
sin sustituir la APK.
