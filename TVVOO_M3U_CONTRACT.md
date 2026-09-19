# Contrato M3U de TvVoo

VibeM3U exporta la identidad estable de un canal TvVoo. La entrada no guarda
una URL HLS resuelta, token, contraseña ni fuente temporal; la app renueva la
fuente al abrir el canal.

```m3u
#EXTINF:-1 tvg-id="unitedkingdom|vavoo_SKY%201%7Cgroup%3Auk@TvVoo" tvg-logo="https://example.com/sky.png" x-resolver="tvvoo" x-resolver-id="vavoo_SKY%201%7Cgroup%3Auk" x-resolver-ids="vavoo_SKY%201%7Cgroup%3Auk" x-resolver-country="unitedkingdom" x-resolver-refresh="on_play",SKY 1
tvvoo://channel/unitedkingdom%7Cvavoo_SKY%25201%257Cgroup%253Auk
```

`tvg-id` es `stableId@TvVoo`; `stableId` es `countryKey|canonicalAlias` y
`canonicalAlias` codifica una sola vez el payload completo
`NOMBRE|group:país`. El path de `tvvoo://` codifica el `stableId` como un path
URI, por eso los signos `%` del alias aparecen como `%25`.

La posición de un canal es su ubicación en el archivo M3U. Si el parser recibe
la misma identidad desde un M3U y desde la selección local, el M3U conserva su
nombre, logo y orden; los canales seleccionados que no estén en el M3U se
añaden al final.

## Uso desde la aplicación

1. En Ajustes, activa «TvVoo · usar canales seleccionados» y abre «Abrir
   catálogo TvVoo».
2. Elige el país, busca por nombre o categoría y marca los canales. «Aplicar»
   guarda el orden de selección; «Copiar M3U» copia las entradas de identidad
   para pegarlas en otra lista.
3. Al pegar una entrada en un archivo M3U, conserva juntas sus dos líneas
   (`#EXTINF` y `tvvoo://channel/...`) y colócala en la posición deseada. El
   actualizador conserva esa posición de canal, el alias y el `tvg-logo`.
   Puedes cambiar el nombre mostrado y el `tvg-logo` sin alterar el
   `stableId`, el `tvg-id` ni el alias.
4. La app numera automáticamente los canales según el orden de las entradas
   `#EXTINF` (1, 2, 3...). No hace falta escribir números manuales. La
   posición es el orden de canales, no el número de línea del archivo.

La configuración de MediaFlow se abre desde el botón «Configurar MediaFlow
Proxy» en Ajustes. Al desactivarlo se conserva el origen y la contraseña
guardados; la reproducción vuelve al resolutor TvVoo directo. Al activarlo,
la resolución se envía al origen MediaFlow configurado y la contraseña no se
incluye en la entrada M3U.

La validación automática del contrato comprueba identidad, alias, límites y
estructura de la referencia. No reemplaza la prueba de reproducción en el
dispositivo: la disponibilidad final depende de red, región, servidor
MediaFlow y del momento en que se abra el canal.
