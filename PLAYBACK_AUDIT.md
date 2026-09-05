# Auditoría de apertura y recuperación de canales

Revisión sobre la base `1387107`, septiembre de 2026. Las mejoras se verifican con pruebas automatizadas en GitHub Actions. Las cifras de latencia reales de proveedores y Android TV necesitan una sesión de reproducción en el dispositivo.

## Hallazgos y cambios

| Área | Problema identificado en código | Cambio |
| --- | --- | --- |
| TvVoo/Vavoo | Espera de lotes completos antes de validar; rutas externas y directas con esperas acumuladas | Entrega incremental de candidatos, validación concurrente limitada y presupuesto compartido; inicio demorado de la segunda ruta en modo ambos |
| Identidad Vavoo | Coincidencias por prefijo podían confundir nombres numerados o países | Coincidencias más estrictas y prioridad de identidad ganadora; firmas y enlaces resueltos siguen siendo temporales |
| HTTP | Cancelar un Future no cerraba una petición bloqueada | Cliente OkHttp compartido con Media3; cancelación de llamadas y presupuesto total por resolución |
| HLS | Se descargaban otra vez manifiestos que acababan de validarse | Entrega en RAM de un solo uso, durante cinco segundos y con URL exacta; no guarda segmentos ni claves |
| Renovación | Una caducidad posterior podía agotar el permiso de renovación durante toda la sesión | Presupuesto por fallo, renovable tras quince segundos de reproducción continua; rechazos de autorización salen antes al recuperador |
| Listas y EPG | HTTP bajo bloqueo de caché y espera de todas las listas antes de mostrarlas | Lecturas locales independientes de HTTP, resultados incrementales y mezcla de EPG fuera del hilo principal |
| Cabeceras M3U | Los canales directos perdían opciones HTTP necesarias | Opciones de User-Agent, Referer, Origin, Cookie y Authorization por canal; las credenciales nuevas se excluyen de la caché persistente |
| Video | El objetivo automático de muestras no se adaptaba al heap disponible | Objetivo de heap/8, mínimo 8 MiB, máximo 64 MiB o 32 MiB en dispositivos de poca RAM; conserva los tiempos de carga de Media3 |
| Diagnóstico | No había una medición atribuible a cada apertura ni contexto de memoria del corte | Intentos etiquetados, primer fotograma, rebuffer y resumen p50/p95; historial limitado de búfer, memoria y códigos HTTP |

## Comprobación de la sospecha de caché en Highfly

El usuario observa el problema especialmente en Highfly. La app no empleaba `SimpleCache` ni `CacheDataSource` para almacenar video en disco. Las cachés persistentes de listas, EPG y logos son independientes del búfer del reproductor.

La implementación de Media3 1.10.1 detiene la carga al alcanzar el objetivo de tiempo o bytes y conserva las muestras pendientes. No hay un vaciado periódico del video activo en la app. Por tanto, el código no demuestra que el corte observado se deba a una caché llena. La caducidad de la fuente, errores de segmentos, disponibilidad del servidor y presión de memoria requieren distinguirse con registros del dispositivo.

`PlaybackBufferManager` limita el objetivo de muestras comprimidas, no la memoria total del proceso: decodificadores, superficies y otras estructuras consumen memoria adicional, y una carga puede superar temporalmente el objetivo. No ejecuta GC, reinicia ni hace seek al recibir presión de memoria; solamente permite que el asignador libere bloques libres sobrantes. Media3 libera su asignador cuando se detiene o libera el reproductor.

## Medición en Android TV

Capturar únicamente los registros específicos de la app durante una sesión de Highfly:

```sh
adb logcat -v threadtime VibeM3U-Startup:I VibeM3U-Buffer:I '*:S'
```

Los registros de búfer incluyen el identificador del intento, estado, milisegundos por delante, bytes de muestras en uso, objetivo de bytes, heap utilizado/máximo y códigos de error y HTTP. Conservan hasta 24 muestras en memoria y las imprimen al entrar en buffering, recibir presión de memoria o fallar. No incluyen URL, token, nombre del canal ni excepción completa. La ausencia de un aviso de memoria no descarta un problema del decodificador o memoria nativa.

Comparar arranque frío, repetición del mismo canal y cambios rápidos. Para la caducidad, mantener Highfly reproduciendo hasta que ocurra el fallo habitual y observar si un rechazo HTTP precede a una renovación. Los tests con un servidor local y video sintético comprueban el comportamiento de recuperación, pero no reproducen las condiciones del proveedor ni sustituyen esta observación.

Referencias de implementación: [DefaultLoadControl 1.10.1](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java), [DefaultAllocator 1.10.1](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/upstream/DefaultAllocator.java), [transporte de red de Media3](https://developer.android.com/media/media3/exoplayer/network-stacks).
