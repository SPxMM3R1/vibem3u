# Validación técnica de resolutores — 2026-08-24

Validación ejecutada desde Chile antes de integrar los motores nuevos. Se leyó
el repositorio `D:\Users\SP4MM3R\Documents\Codex\Lista M3U` sin modificarlo.
Las URLs firmadas y los tokens se mantuvieron solo en memoria y no se imprimen
en este informe.

## Inventario comprobado

- Commit local de Lista M3U: `8c1929c`.
- 118 entradas `#EXTINF` y 118 `tvg-id` únicos.
- 54 familias presentes en `TVVOO_STREAM_RESOLVER_IDS`.
- 7 localizadores actuales de `leaf.highfly.dev`.
- La M3U generada todavía no contenía atributos `x-resolver-*`.
- 54 URLs temporales contenían credenciales dentro de una ruta `/sunshine/`.

## Método HLS

Una fuente se consideró reproducible solo después de completar:

1. playlist maestra;
2. playlist de variante o media;
3. lectura parcial del primer segmento.

Un HTTP 200 de la maestra por sí solo no se consideró suficiente. Las pruebas
de red se repitieron con Windows Schannel porque el almacén de certificados del
Python disponible no representaba el comportamiento TLS de Android/Windows.

## Resultados

### TVN

- La página oficial entregó un ID de 24 caracteres y un `access_token` temporal.
- Playlist maestra: HTTP 200.
- Playlist de media: HTTP 200.
- Primer segmento: HTTP 206, tipo `video/mp2t`.
- El token no fue registrado ni persistido.

### 24 Horas

- La página oficial contenía un enlace activo con `data-ms` de 24 caracteres.
- Playlist maestra: HTTP 200.
- Playlist de media: HTTP 200.
- Primer segmento: HTTP 206, tipo `video/mp2t`.

### TvVoo

Se consultaron aliases reales de `TVVOO_STREAM_RESOLVER_IDS`, se parseó
`streams[].url` como JSON y se validaron candidatos completos.

- Premier Sports 1: válido con alias 1.
- Sky Sports Main Event: válido con alias 2.
- CNN: válido con alias 1.
- M6 Music: válido con alias 1.
- DAZN FAST+: válido con alias 1.
- Sport TV 1: válido con alias 1.
- Sky Sport NBA Italia: válido con alias 1.
- Eurosport 2 UK: sin candidato utilizable durante la ventana de prueba.
- Sky Sports NFL devolvió dos candidatos que respondieron HTTP 502 durante la
  prueba puntual.

El resultado confirma que el resolutor necesita preservar el orden de aliases,
probar alternativas y no asumir que una respuesta JSON implica una señal sana.

### Highfly

- Se validó el localizador actual de Sky Sports F1.
- Playlist maestra: HTTP 200.
- Playlist de media: HTTP 200.
- Primer segmento: HTTP 206.

### Pluto

- Se comprobó un localizador `jmp2.uk/plu-...` actual.
- La cadena de redirección produjo una maestra, media y segmento válidos.
- No se justifica agregar un motor dinámico para Pluto en esta fase.

## Conclusiones aplicadas al código

- TVN, Meganoticias dinámico y 24 Horas tienen TTL cero: fuente nueva en cada
  apertura y ninguna reutilización de token.
- TvVoo y Highfly usan solamente caché de sesión en RAM, con TTL corto,
  invalidación ante error y resolución forzada antes de reintentar.
- La caché persistente sustituye rutas TvVoo temporales por un placeholder y
  elimina queries sensibles de los demás proveedores.
- Los 54 aliases estables del actualizador se copiaron al catálogo integrado
  como puente de compatibilidad, verificando igualdad exacta clave por clave.
- La M3U será la autoridad cuando publique `x-resolver-ids`.
- La validación de HLS queda encapsulada y se ejecuta fuera del hilo principal.
