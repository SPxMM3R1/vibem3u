# Separación de hilos del OSD

## Responsabilidades

- UI de Android: controles, foco, distribución de las vistas y aplicación de los textos finales.
  No se permite acceder a View, TextView ni al Player de la actividad desde los trabajadores.
- VibeM3U-Marquee: preparación del título y dibujo del desplazamiento en una SurfaceView
  transparente limitada al área del título. Usa su propio Choreographer y Surface.lockHardwareCanvas().
  No anima propiedades de las vistas ni invalida el OSD en cada fotograma.
- VibeM3U-Diagnostics: cálculo de FPS y bitrate. El callback del renderer deposita únicamente
  marcas de tiempo en un buffer circular de 512 posiciones; no crea tareas por fotograma.
  Las ventanas de FPS usan los tiempos originales, no el momento en que el trabajador los procesa.
- Los hilos de reproducción/decodificación de Media3 no cambian.

Android sigue compartiendo GPU, ancho de banda de memoria y compositor entre estas superficies.
La separación de hilos no garantiza por sí sola eliminar todos los saltos de presentación.
El contador de FPS sigue midiendo cuadros enviados por el renderer, no la presentación física del panel.

## Entrega y ciclo de vida

El trabajador de diagnósticos procesa las marcas cada 250 ms y publica snapshots inmutables.
Agrupa sus avisos a la UI con un intervalo mínimo de un segundo, excepto al observar el primer
fotograma. La UI admite como máximo un aviso pendiente y no reasigna textos idénticos.
Mientras el OSD está oculto, la ventana pierde el foco o están abiertas las opciones, conserva
las mediciones sin enviar avisos a la interfaz. Al mostrar el OSD se lee el snapshot más reciente.

Cada cambio de fuente sustituye la sesión completa de medición, incluido su buffer de marcas.
El trabajo pendiente de una sesión anterior no puede publicar resultados en la nueva.
Al destruir/recrear el reproductor se cierra el executor de diagnósticos.

El scroll se detiene al ocultar el OSD, perder el foco, desconectar la vista o salir de la app.
No se toca una superficie destruida: el cierre excluye cualquier operación de Canvas en curso.
Los callbacks tardíos comprueban que pertenecen al renderer activo.
Si no hay aceleración, las animaciones están desactivadas, la dirección es RTL, el título cabe
o falla la superficie, queda un TextView nativo estático. El texto completo permanece accesible.
Las dimensiones, colores, velocidad de 40 dp/s y separación de 12 dp del OSD se mantienen.

## Comprobación antes de publicar

Las pruebas unitarias cubren movimiento por tiempo transcurrido, repetición y límites del buffer,
timestamps conservados, cálculo fuera del hilo llamador, cancelación, cambio de canal,
notificaciones agrupadas, entrega de un cambio pospuesto y ausencia de avisos con el OSD oculto.
Se conserva la condición READY + primer fotograma antes de mostrar codecs/FPS/bitrate.

Antes de distribuir el APK:

1. Ejecutar testDebugUnitTest, lintDebug y lintExperimental mediante el flujo de GitHub Actions.
2. Comprobar en Android TV real un título largo con vídeo a 60 FPS; comparar OSD visible y oculto.
3. Verificar títulos cortos/largos, cambio rápido de canal, apertura/cierre de opciones y diálogo
   de salida: sin títulos superpuestos, texto antiguo, pérdida de foco ni superficies negras.
4. Comprobar que se detiene VibeM3U-Marquee al ocultar el OSD y que se cierra
   VibeM3U-Diagnostics al liberar/recrear el reproductor.
5. Capturar tiempos de UI/RenderThread/SurfaceFlinger y descartes de vídeo antes de afirmar una mejora.
   El emulador sirve para función/ciclo de vida, no sustituye esta comprobación del compositor de la TV.

No se cambia la configuración de Media3, la calidad del vídeo, los resolutores ni las cachés de la lista/EPG.
No se añade ninguna dependencia de ejecución ni permiso.

## Referencias

- [SurfaceView: renderizado desde un hilo secundario y ciclo de vida](https://developer.android.com/reference/android/view/SurfaceView)
- [Choreographer: un coordinador por Looper](https://developer.android.com/reference/android/view/Choreographer)
- [Surface: Canvas acelerado desde API 23](https://developer.android.com/reference/android/view/Surface#lockHardwareCanvas())
- [Hilos y restricciones de las vistas de Android](https://developer.android.com/topic/performance/threads)
