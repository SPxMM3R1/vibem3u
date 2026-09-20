# Publicación de selección VibeM3U

VibeM3U puede publicar automáticamente la selección local de TvVoo y Highfly
en:

`data/vibem3u-selection.json`

del repositorio `SPxMM3R1/lista-m3u`, mediante la API Contents de GitHub. El
archivo se publica sin tokens, contraseñas, URLs HLS firmadas ni claves de
sesión.

La especificación canónica compartida de identidades, EPG y logos vive en
[`VIBEM3U_ID_CONTRACT_EPG_LOGOS.md`](https://github.com/SPxMM3R1/lista-m3u/blob/main/VIBEM3U_ID_CONTRACT_EPG_LOGOS.md)
en el repositorio Lista M3U. Este documento describe la implementación Android
de esa misma frontera; si ambos documentos difieren, el contrato de Lista M3U
es la referencia de integración.

## Qué declara el archivo

El documento declara únicamente la selección que hizo el usuario y el orden
que debe conservarse. Cada fila incluye:

- `provider`: `tvvoo` o `highfly`;
- `catalogKey`: identidad lógica del catálogo;
- `providerResourceId`: identidad que permite resolver el elemento en su
  proveedor;
- `resolverSlug`, cuando Highfly lo entrega;
- `identityState`: `canonical` si la identidad fue asignada por un catálogo
  conocido o `provisional` si todavía deriva del nombre y requiere revisión;
- `countryKey`, cuando la identidad canónica contiene una región conocida;
- `aliases`, cuando existen aliases estables para una búsqueda controlada;
- `name`, `group`, `category` y `order` como metadatos de ayuda.

No incluye `tvg-id`. Tampoco convierte `resolverSlug`, `leaf:*`, un alias
TvVoo ni el nombre visible en una identidad XMLTV.

Durante la transición, las filas TvVoo también pueden incluir
`resolverAliases`, que era el nombre utilizado por versiones anteriores de la
app. Los consumidores nuevos deben leer `aliases`; ambos campos contienen los
mismos aliases estables y nunca URLs de reproducción.

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
  "countryKey": "uk",
  "identityState": "canonical",
  "order": 1
}
```

El runner puede convertirlo, por ejemplo, en una entrada con el `tvg-id`,
logo y asociación EPG que ya use su catálogo. Si Highfly cambia el slug, el
runner debe resolver el nuevo recurso por `catalogKey`/nombre normalizado y
actualizar solo la referencia del proveedor; no debe cambiar el `tvg-id`
canónico por ese motivo.

Si el catálogo de la app todavía no puede confirmar la identidad, publicará
`identityState: "provisional"` y una clave como `Highfly.nombre`. El runner
puede usarla para revisión o reconciliación, pero no debe convertirla
automáticamente en un `tvg-id` público.

## Autorización mediante PIN y QR

Además del token manual, VibeM3U incorpora el GitHub OAuth Device Flow. Desde
Opciones se puede solicitar un código, mostrar la dirección oficial de GitHub
y mostrar un QR que abre esa dirección. El usuario autoriza la aplicación en
otro dispositivo introduciendo el PIN; VibeM3U espera la autorización y recibe
los tokens sin que el usuario tenga que copiar un secreto en el Android TV.

El flujo solicita `public_repo offline_access`: el primer alcance permite
escribir en el repositorio público y el segundo permite recibir un refresh
token cuando la OAuth App usa tokens expirables. El acceso y el refresh token se cifran con Android Keystore. No se
guardan en la selección, en la M3U, en logs ni en el archivo publicado. Si el
access token vence, la app intenta renovarlo con el refresh token antes de
publicar. Si la renovación no es posible, solicita volver a vincular GitHub.

El QR no contiene el token: solamente codifica la URL oficial de verificación
y el PIN se muestra por separado en la pantalla. La app no abre ni controla el
navegador externo; el usuario completa la autorización en GitHub.

Para que el botón funcione, cada APK debe compilarse con el Client ID público
de una OAuth App de GitHub que tenga activado Device Flow. Se puede suministrar
al Gradle mediante la variable de entorno:

```powershell
$env:VIBEM3U_GITHUB_DEVICE_CLIENT_ID = "Iv1...."
.\gradlew.bat assembleRelease
```

También se admite la propiedad `-PgithubDeviceClientId=Iv1....`. El Client ID
no es una contraseña y puede formar parte del APK; no se debe incluir un
Client Secret, ya que el Device Flow no lo requiere. Si una compilación no
recibe ese valor, el botón informa que debe configurarse y el token manual
continúa disponible.

## Publicación automática

La app necesita una autorización de GitHub configurada por el usuario. GitHub
no permite escribir en el repositorio de forma anónima. La opción recomendada
es el PIN/QR anterior; como alternativa se mantiene un token fine-grained
limitado al repositorio `SPxMM3R1/lista-m3u` con permiso `Contents: Read and
write`.

El token se cifra con Android Keystore. La app publica en segundo plano al
guardar una selección, al guardar la configuración y al iniciar si detecta una
firma de selección diferente. Si no hay cambios, no vuelve a descargar ni a
escribir el archivo. Un error se reintenta después de una ventana de espera,
sin mostrar el token ni el cuerpo de la respuesta en logs.

La publicación desde la app y el consumo del archivo son fases separadas: este
contrato habilita el puente, pero el runner de Lista M3U debe implementar su
lectura antes de que la selección afecte automáticamente a `m3u.m3u` y
`epg.xml`.
