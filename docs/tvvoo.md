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
