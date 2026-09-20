# Publicación de selección VibeM3U

VibeM3U puede publicar automáticamente la selección local de TvVoo y Highfly
en:

`data/vibem3u-selection.json`

del repositorio `SPxMM3R1/lista-m3u`, mediante la API Contents de GitHub. El
archivo se publica sin tokens, contraseñas, URLs HLS firmadas ni claves de
sesión.

## Qué declara el archivo

El documento declara únicamente la selección que hizo el usuario y el orden
que debe conservarse. Cada fila incluye:

- `provider`: `tvvoo` o `highfly`;
- `catalogKey`: identidad lógica del catálogo;
- `providerResourceId`: identidad que permite resolver el elemento en su
  proveedor;
- `resolverSlug`, cuando Highfly lo entrega;
- `name`, `group`, `category` y `order` como metadatos de ayuda.

No incluye `tvg-id`. Tampoco convierte `resolverSlug`, `leaf:*`, un alias
TvVoo ni el nombre visible en una identidad XMLTV.

## Responsabilidad del runner Lista M3U

El runner debe leer `catalogKey` y `providerResourceId`, cruzarlos con su
catálogo autoritativo y generar la entrada pública de la lista. El runner es
quien decide y conserva:

1. el `tvg-id` canónico que exista en `epg.xml`;
2. el logo curado de `logos/`;
3. el nombre y grupo finales cuando haya una corrección editorial;
4. la asociación con el `channel id` XMLTV;
5. la referencia sin token que VibeM3U resolverá al reproducir.

Para TvVoo, `catalogKey` es el `stableId` (`countryKey|canonicalAlias`). Para
Highfly, `catalogKey` es la identidad estable derivada del canal, mientras
`providerResourceId`/`resolverSlug` puede cambiar si Highfly rota su recurso.
Por eso ninguno de esos campos debe usarse directamente como `tvg-id` sin el
cruce explícito del catálogo del runner.

## Ejemplo conceptual

```json
{
  "provider": "highfly",
  "catalogKey": "SkySportsF1.uk",
  "providerResourceId": "leaf:f1-3949409",
  "resolverSlug": "f1-3949409",
  "name": "(FHD) : SKY SPORTS F1",
  "order": 1
}
```

El runner puede convertirlo, por ejemplo, en una entrada con el `tvg-id`,
logo y asociación EPG que ya use su catálogo. Si Highfly cambia el slug, el
runner debe resolver el nuevo recurso por `catalogKey`/nombre normalizado y
actualizar solo la referencia del proveedor; no debe cambiar el `tvg-id`
canónico por ese motivo.

## Publicación automática

La app necesita un token personal de GitHub configurado por el usuario. GitHub
no permite escribir en el repositorio de forma anónima. Se recomienda un token
fine-grained limitado al repositorio `SPxMM3R1/lista-m3u` con permiso
`Contents: Read and write`.

El token se cifra con Android Keystore. La app publica en segundo plano al
guardar una selección, al guardar la configuración y al iniciar si detecta una
firma de selección diferente. Si no hay cambios, no vuelve a descargar ni a
escribir el archivo. Un error se reintenta después de una ventana de espera,
sin mostrar el token ni el cuerpo de la respuesta en logs.

La publicación desde la app y el consumo del archivo son fases separadas: este
contrato habilita el puente, pero el runner de Lista M3U debe implementar su
lectura antes de que la selección afecte automáticamente a `m3u.m3u` y
`epg.xml`.
