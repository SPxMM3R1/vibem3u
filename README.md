# VibeM3U

[![Android CI](https://github.com/SPxMM3R1/vibem3u/actions/workflows/android-ci.yml/badge.svg)](https://github.com/SPxMM3R1/vibem3u/actions/workflows/android-ci.yml)

Reproductor M3U para Android TV inspirado en la experiencia de un set-top box:
abre directamente la señal, muestra primero la lista y la programación desde su
caché local, valida cambios en segundo plano y muestra una barra compacta al
cambiar de canal.

La versión 0.4.4 recuerda el último canal, permite fijar una variante de video por
canal, presenta el reloj dentro de un panel con el mismo estilo de la información
del canal y usa `Atrás` para ocultar primero la información visible. También conserva
la actualización desde GitHub introducida en 0.3.0.

## Descargar

- [Descargar la versión más reciente](https://github.com/SPxMM3R1/vibem3u/releases/latest)
- [Ver todas las versiones](https://github.com/SPxMM3R1/vibem3u/releases)

## Actualizaciones en Android TV

Al abrir VibeM3U, la app consulta la última Release pública de GitHub. Si encuentra
una versión superior:

1. Muestra un aviso con **Actualizar** seleccionado.
2. Descarga el APK en el almacenamiento privado de la app.
3. Comprueba el nombre del paquete, el número de versión y el certificado.
4. Solicita, si hace falta, permiso para instalar desde VibeM3U.
5. Abre el instalador de Android para que el usuario confirme la actualización.

Android no permite a una app normal completar silenciosamente el último paso. La
versión 0.3.0 debe instalarse una vez mediante Downloader; las versiones posteriores
ya podrán anunciarse y descargarse desde la propia app.

## Controles

- `↑` / `↓`: canal anterior o siguiente; el sentido puede invertirse en Configuración.
- `→`: elegir `Automático`, fijar una resolución/bitrate y activar o desactivar subtítulos del canal.
- `OK`: mostrar la información del canal.
- Mantener `OK`, botón `Menú` o botón `Configuración`: editar la URL M3U.
- `Atrás`: ocultar la información si está visible; si ya está oculta, confirmar salida.

En Configuración se puede activar la normalización general de volumen. Esta opción
procesa el audio PCM para reducir diferencias entre canales y puede desactivar el
passthrough de audio digital mientras está activa.

La programación y los logos se conservan en una caché privada. Al volver a abrir
la aplicación se reutiliza la copia disponible mientras se valida la M3U y el XML
con `ETag`/`Last-Modified`; si el servidor expone esos validadores y confirma que
no cambiaron, no se vuelve a descargar el contenido completo. Los tokens temporales de los proveedores
dinámicos no se guardan en la copia de la lista.

TVN y Meganoticias se resuelven únicamente en memoria. Cada apertura, reanudación
después de salir de la aplicación y reintento de un canal resuelto descarta la URL
anterior y solicita un token nuevo; las respuestas del resolutor se marcan como no
cacheables. Al leer una caché creada por una versión anterior, la aplicación elimina
las credenciales del respaldo antes de usarla o la descarta si no puede migrarla.

Configuración también muestra la versión instalada y permite buscar manualmente
una actualización desde GitHub.

Desde 0.4.38, la sección **Resolutores** permite usar automáticamente TvVoo y
Vavoo, limitar la reproducción al motor Vavoo propio o utilizar solamente el
servicio TvVoo externo. El motor propio prueba HTTPS primero y solo usa el HTTP
equivalente cuando la cadena TLS confirma que el certificado del nodo expiró;
otros errores TLS siguen bloqueados.

## Compilación automática

GitHub Actions realiza toda la compilación:

- Cada cambio enviado a `main` ejecuta las pruebas, el análisis de Android y genera
  un APK descargable desde la ejecución de **Android CI**.
- Cada etiqueta con formato `vX.Y.Z` vuelve a compilar el proyecto en GitHub y
  publica el APK en **Releases**.
- La firma se recupera desde un secreto cifrado de GitHub para que el APK pueda
  actualizar instalaciones existentes.

Para publicar una nueva versión, primero actualiza `versionCode` y `versionName`,
y luego crea una etiqueta que coincida con la versión, por ejemplo `v0.3.0`.

## Edición experimental paralela

El proyecto también genera `app-experimental.apk`, con el paquete
`cl.streambox.tv.experimental` y el nombre **VibeM3U Experimental**. Puede
instalarse junto a la edición estable porque mantiene preferencias, cachés y
datos privados independientes.

Esta variante contiene una prueba de resolución Vavoo directa. Sustituye solo
el motor de las entradas que ya declaran `x-resolver="tvvoo"`, permanece
desactivada por defecto y conserva la URL de la M3U como respaldo. No captura
canales por coincidencias parciales del nombre. La sesión y las URLs resultantes
viven únicamente en RAM; al cerrar la aplicación se descartan.

Para probarla, instala el artefacto **VibeM3U-experimental** de Android CI y
activa **Vavoo directo experimental** en `Opciones > Resolutores`. La edición
experimental no ofrece instalar Releases estables encima de su paquete.

El experimento se limita a endpoints públicos y señales sin login ni DRM. La
implementación del comportamiento del catálogo se contrastó con el proyecto
[OwnerPlugins/vavoo](https://github.com/OwnerPlugins/vavoo), sin incorporar su
interfaz Enigma2, su proxy local, su telemetría ni la desactivación global de TLS.
