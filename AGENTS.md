# AGENTS.md — VibeM3U

Guía para agentes de IA que trabajen en esta aplicación Android TV (reproducción, resolutores, OSD, ajustes y auxiliar local).

## Qué es esta aplicación

- App Android TV (paquete `cl.streambox.tv`, Media3/ExoPlayer) que reproduce lo publicado por el repositorio Lista M3U.
- Consume `data/channel-editor-layout.json` como autoridad de canales, orden, número, visibilidad, logo y nombre (`PublishedPlaybackCatalog*`). No mantiene listas paralelas, canales propios ocultos ni selector/creador de catálogos.
- Las listas M3U (Lista 1/Lista 2) se habilitan/apagan con su dirección: una dirección vacía significa que la lista no se usa. Los interruptores heredados (`playlist_enabled`/`playlist_enabled_2`) se respetan solo por migración.
- Resolutores: Highfly, TvVoo, TVN y Meganoticias (`*StreamResolver.java`), declarados en `resolver-catalog.json` (publicado por Lista M3U).
- OSD y control remoto: D-pad derecha abre el menú de fuentes/calidades; D-pad izquierda abre la mini EPG del canal (actual + 2 siguientes, lista numerada). El contrato de hilos y superficies está en `OSD_THREADING.md`.
- Auxiliar local: `local-catalog/` sirve el editor web de Lista M3U en 127.0.0.1 y resuelve previsualizaciones con las clases Java del propio repo.

## Límites

- No modificar el repositorio Lista M3U desde este checkout. Un commit por repositorio.
- La identidad de canal es `catalogKey`/`tvg-id`; las URL, slugs y hojas de proveedor son referencias que rotan.
- Nunca introducir tokens, credenciales ni URL firmadas en el repositorio, en JSON públicos ni en logs.
- No tocar la app para cambios editoriales: orden, números, visibilidad, logos y nombres se resuelven publicando desde el editor de Lista M3U.

## Cómo trabajar

- No hay SDK Android local en el equipo: los cambios se validan con CI ("Android CI"). No prometer builds locales.
- Release:
  1. Actualizar `versionCode` (+1) y `versionName` en `app/build.gradle.kts`.
     - Numeración: cada release sube solo el último número (0.5.33 → 0.5.34). Subir el número del medio o el primero (0.6.0, 1.0.0) lo decide el usuario: preguntar antes. Las 0.6.0/0.6.1 del 27-09 fueron un error: 0.6.2 (versionCode 141) es un puente que acepta 0.5.33+ como actualización, 0.5.33 (versionCode 142) retira el puente y después se borran los releases 0.6.x.
  2. Commit `chore(release): bump VibeM3U a X.Y.Z` (y antes un commit del cambio con prefijo `feat(...)`/`fix(...)`).
  3. `git tag vX.Y.Z && git push origin vX.Y.Z` (etiqueta lightweight). El workflow "Publicar APK" compila y publica el APK en el release.
  4. Verificar con `gh run watch` y `gh release view vX.Y.Z`; informar tamaño y SHA-256 del APK.
- Tests: CI corre `testDebugUnitTest`, `testExperimentalUnitTest`, `testReleaseUnitTest`, lint y pruebas instrumentadas. Agregar tests JVM en `app/src/test/java/cl/streambox/tv/` para lógica pura (por ejemplo `EpgDataTest`).
- Contrato de filas de proveedor: `app/src/test/resources/contracts/layout-provider-rows.json` es copia idéntica de `Lista M3U/contracts/layout-provider-rows.json`. Lo validan `LayoutContractTest` (app) y `LocalCatalogContractTest` (auxiliar); no editar la copia aquí sin cambiar primero la de Lista M3U.
- Commits en español con prefijo: `feat(osd)`, `fix(settings)`, `feat(highfly)`, `chore(release)`.

## Reglas de reproducción y OSD

- Resolver antes de reproducir: los resolutores conservan la autenticación en memoria; nunca persistir tokens ni URLs de sesión.
- OSD: el marquee dibuja en su propia Surface y los diagnósticos corren en su worker (`OSD_THREADING.md`). No agregar trabajo por fotograma a la UI.
- El watchdog de stall (agregado 2026-09-05/10) debe tolerar pausas del compositor: no recuperar el canal por retrasos con el OSD abierto. La mitigación de v0.5.27 se revirtió en v0.5.28; si reaparece lentitud en 50/60 fps con OSD abierto, el ajuste acordado es evitar re-disparar el indicador indeterminado en cada tick, sin tocar render ni superficies.
- Un canal Highfly sin hoja publicada se resuelve por identidad (`catalogKey`) probando las demás hojas del proveedor; si Highfly deja de publicar la señal, el cambio de proveedor se coordina primero en Lista M3U (caso real: Sky Sports F1 pasó a TvVoo).

## Documentos de referencia

- `VIBEM3U_ID_CONTRACT_EPG_LOGOS.md` (contrato vigente de identidad, EPG y logos).
- `TVVOO_M3U_CONTRACT.md`, `RESOLVER_RECIPE_V1.md`, `CONTEXTO_LISTA_M3U_PARA_VIBEM3U.md`.
- `OSD_THREADING.md`, `PLAYBACK_AUDIT.md`, `vibem3u_context.md` (contexto histórico).
