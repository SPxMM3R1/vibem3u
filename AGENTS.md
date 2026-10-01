# AGENTS.md — VibeM3U

> Reglas vigentes de ambos proyectos (identidad, EPG, logos, catálogo, versiones): `Lista M3U/REGLAS.md` (https://github.com/SPxMM3R1/lista-m3u/blob/main/REGLAS.md). Mandan sobre documentos antiguos como `vibem3u_context.md`.

Guía para agentes de IA que trabajen en esta aplicación Android TV (reproducción, resolutores, OSD, ajustes y auxiliar local).

## Protocolo de traspaso (obligatorio para cualquier agente)

El usuario cambia de proveedor de IA (Codex, Claude, Gemini, Copilot…) sin aviso: cualquier
agente debe poder retomar el trabajo solo con lo que está en el repositorio.

1. **Al empezar**: lee este archivo, `ESTADO.md` (cómo está todo hoy, pendientes,
   preferencias del usuario y bitácora) y ``Lista M3U/REGLAS.md``.
2. **Con cada cambio**, en el mismo commit:
   - actualiza `ESTADO.md`: la sección «Hoy» si cambió el estado y una línea nueva en
     «Bitácora» con la fecha;
   - actualiza este `AGENTS.md` si cambió la forma de trabajar, un comando, un test esperado o
     una regla;
   - si cambió una regla compartida, actualiza `REGLAS.md` (en Lista M3U).
3. **Nunca** dejes información solo en el chat o en la memoria de una herramienta: si otro
   agente la necesitará, va a estos archivos.
4. Codex y OpenCode leen este `AGENTS.md` directo; `opencode.json` además hace que OpenCode
   cargue siempre `ESTADO.md`. `CLAUDE.md`, `GEMINI.md` y `.github/copilot-instructions.md`
   solo apuntan aquí. No se agregan reglas en ninguno de ellos.

## Qué es esta aplicación

- App Android TV (paquete `cl.streambox.tv`, Media3/ExoPlayer) que reproduce lo publicado por el repositorio Lista M3U.
- Consume `data/channel-editor-layout.json` como autoridad de canales, orden, número, visibilidad, logo y nombre (`PublishedPlaybackCatalog*`). No mantiene listas paralelas, canales propios ocultos ni selector/creador de catálogos.
- Las listas M3U (Lista 1/Lista 2) se habilitan/apagan con su dirección: una dirección vacía significa que la lista no se usa. Los interruptores heredados (`playlist_enabled`/`playlist_enabled_2`) se respetan solo por migración.
- Resolutores: Highfly, TvVoo, TVN y Meganoticias (`*StreamResolver.java`), declarados en `resolver-catalog.json` (publicado por Lista M3U).
- Control remoto: OK muestra el OSD; segundo OK o INFO, detalle del programa; ◀ o GUIDE, Guía completa (`EpgGuideView`/`EpgGuideNavigator`); ▶, fuentes y calidades; OK mantenido o MENU, Opciones. En la Guía: ◀ ▶ mueven el foco entre programas, ▲ desde el primer canal va a los filtros por categoría, OK corto abre el canal y OK mantenido programa o quita un recordatorio. El contrato de hilos y superficies está en `OSD_THREADING.md`.
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
- **Dos estilos de interfaz** (Opciones › Interfaz › Estilo, `UiStyle`): moderno (por defecto) y clásico (diseño de la 0.5.40). Lo visual del clásico vive aparte con prefijo `classic_` (layouts, drawables, colores) y en `EpgGuideClassicView`; el OSD se infla desde `osd_modern.xml` o `classic_osd.xml` y el detalle desde un `ViewStub`, siempre con los mismos IDs. La lógica (canales, EPG, recordatorios, reproducción) es una sola: un cambio funcional debe funcionar en ambos estilos, y un cambio visual se aplica al estilo que el usuario pida (por defecto, solo al moderno).
- Un estilo nunca debe impedir que la app abra: `UiStyle.beginStart`/`startCompleted` vuelven al moderno si un arranque en clásico falla. Antes de publicar un cambio que toque el clásico, comprobar que cada vista que usa ya esté buscada (incidente 0.5.44: `applyClassicChrome` antes de `findViewById` del selector).
- **Diseño**: todo cambio visual se muestra primero como mockup (imagen) y solo se implementa con «aplícalo» del usuario. El estilo vigente (Guía, OSD y menús) está descrito en `ESTADO.md`: fondo oscuro con el video asomando, filas tenues, foco con velo cyan, píldoras y el reloj con fecha en recuadro gris.
- Tests: CI corre `testDebugUnitTest`, `testExperimentalUnitTest`, `testReleaseUnitTest`, lint y pruebas instrumentadas. También compila y prueba el editor local (`:local-catalog:test :local-catalog:installDist`): `local-catalog/build.gradle.kts` comparte una lista explícita de clases de la app, y una clase nueva que use un resolutor debe agregarse ahí (así se rompió el editor con `PublishedHighflyLinks` en la 0.5.40). Agregar tests JVM en `app/src/test/java/cl/streambox/tv/` para lógica pura (por ejemplo `EpgDataTest`).
- Contrato de filas de proveedor: `app/src/test/resources/contracts/layout-provider-rows.json` es copia idéntica de `Lista M3U/contracts/layout-provider-rows.json`. Lo validan `LayoutContractTest` (app) y `LocalCatalogContractTest` (auxiliar); no editar la copia aquí sin cambiar primero la de Lista M3U.
- Commits en español con prefijo: `feat(osd)`, `fix(settings)`, `feat(highfly)`, `chore(release)`.

## Reglas de reproducción y OSD

- Highfly abre primero con el enlace directo que publica Lista M3U (`data/highfly-live.json`, `PublishedHighflyLinks`); si falla, se marca y se usa el resolutor.
- Recuperación automática: 3 intentos con esperas de 2, 5 y 10 s (`PlaybackRecoveryBudget`); desde el segundo se renueva la fuente.
- `ResolverCatalog` rechaza el catálogo completo si un host no está en su lista permitida (así se cayeron todos los resolutores en la 0.5.37). Al cambiar un host de proveedor, agregarlo a la lista en el mismo cambio; lo cubre `ResolverCatalogTest.bundledCatalogLoadsWithEveryProvider`.
- Logos: `LogoFit` recorta el borde transparente y aplica tamaño óptico (misma superficie visual) en el OSD y la Guía. Sus medidas viven en `LogoFit`, no en `MainActivity`: el lint de Media3 impide que otras clases lean constantes de `MainActivity` (API inestable).
- Resolver antes de reproducir: los resolutores conservan la autenticación en memoria; nunca persistir tokens ni URLs de sesión.
- OSD: el marquee dibuja en su propia Surface y los diagnósticos corren en su worker (`OSD_THREADING.md`). No agregar trabajo por fotograma a la UI. Única excepción: `WavyProgressView` (onda de carga) se redibuja por fotograma, pero solo mientras el panel de carga está visible (pantalla negra, sin video).
- Carga en el moderno: `showModernLoadingStep` (sin puntos suspensivos) y `setOsdLoadingAppearance` (sin degradado y textos al 70 % sobre negro; se restaura en `hideLoadingState`). El clásico conserva la barra y los puntos.
- Opciones (`SettingsActivity`, `activity_settings.xml` y `classic_activity_settings.xml` con los mismos IDs): pestañas `TAB_PLAYBACK`=0, `TAB_GENERAL` (Video y audio)=1, `TAB_INTERFACE`=2, `TAB_REMINDERS`=3, `TAB_SOURCE` (Canales)=4, `TAB_UPDATES` (Sistema)=5. Los interruptores se guardan al instante, Atrás llama a `save()`, Cancelar revierte las direcciones. `MainActivity` envía estado del servicio y datos de la señal como extras, y solo recarga canales al volver si cambiaron listas, proveedores o la nivelación. `KEY_AUTO_RECONNECT` (por defecto activa) corta `requestFullPlaybackRecovery` si está apagada.
- El watchdog de stall (agregado 2026-09-05/10) debe tolerar pausas del compositor: no recuperar el canal por retrasos con el OSD abierto. La mitigación de v0.5.27 se revirtió en v0.5.28; si reaparece lentitud en 50/60 fps con OSD abierto, el ajuste acordado es evitar re-disparar el indicador indeterminado en cada tick, sin tocar render ni superficies.
- Un canal Highfly sin hoja publicada se resuelve por identidad (`catalogKey`) probando las demás hojas del proveedor; si Highfly deja de publicar la señal, el cambio de proveedor se coordina primero en Lista M3U (caso real: Sky Sports F1 pasó a TvVoo).

## Documentos de referencia

- `ESTADO.md` (puesta al día: estado de hoy, pendientes, preferencias del usuario y bitácora).
- `VIBEM3U_ID_CONTRACT_EPG_LOGOS.md` (contrato vigente de identidad, EPG y logos).
- `TVVOO_M3U_CONTRACT.md`, `RESOLVER_RECIPE_V1.md`, `CONTEXTO_LISTA_M3U_PARA_VIBEM3U.md`.
- `OSD_THREADING.md`, `PLAYBACK_AUDIT.md`, `vibem3u_context.md` (solo historia: describe la 0.4.83).
