# DISENO.md — Línea oficial de diseño de VibeM3U: «Flotante minimalista»

> Adoptada por el usuario el 2026-10-13 («déjalo incorporar al proyecto como línea oficial de
> diseño»). Nació con el selector «Fuentes y calidades» de la 0.5.95 y se extendió en mockups a
> la Guía y a Opciones. Todo diseño nuevo del estilo **moderno** sigue estas reglas; el estilo
> clásico (0.5.40) no se toca. Desde la 0.5.98 todas las pantallas del moderno siguen esta línea.
>
> El proceso no cambia: todo cambio visual se muestra primero como mockup (imagen, sobre un
> fotograma real de canal) y solo se implementa con «aplícalo» del usuario.

Referencias aprobadas en `docs/diseno/`:

| Pantalla | Archivo | Estado |
| --- | --- | --- |
| Selector «Fuentes y calidades» | `selector-respaldos.jpg` | implementado (0.5.95) |
| Guía | `guia-ahora.jpg`, `guia-futuro.jpg` (mockup) · `guia-app.jpg` (app) | implementado (0.5.96) |
| Opciones | `opciones-*.jpg` (mockup) · `opciones-*-app.jpg` (app) | implementado (0.5.96) |
| OSD | `osd.jpg` (app) | implementado (0.5.96) |
| Escenas (salir, actualización, papelera, Premium, GitHub, token vencido) | `*-app.jpg` | implementado (0.5.98) |
| Avisos (mejor calidad, recordatorio) y MediaFlow | `recordatorio-app.jpg`, `mediaflow-app.jpg` | implementado (0.5.98) |

En la app no hay desenfoque detrás del texto (Android no puede desenfocar el video de un
`SurfaceView`): por eso los degradados de la Guía y de Opciones son algo más oscuros que en los
mockups.

## Principios

1. **El texto flota sobre el video.** Sin paneles, tarjetas, bordes, píldoras ni cajas. Lo
   único detrás del texto es **una sola capa** de oscurecimiento difuminado.
2. **Una capa, no varias.** Un degradado negro (y, donde hay mucho texto, un desenfoque suave
   con máscara) que nace del lado donde está el contenido y se abre hacia el video:
   - selector: desde el borde derecho (`applyMinimalSelectorShade`);
   - Guía: desde abajo (grilla) y desde la izquierda (detalle);
   - Opciones: desde la izquierda (columna de texto).
   El video siempre asoma del otro lado. Nunca un color casi opaco que en videos claros se vea
   como banda sólida (incidente 0.5.94).
3. **Sin relleno.** Cada elemento mide lo que su contenido necesita. No se agregan títulos,
   datos ni adornos para ocupar espacio; no se duplica lo que ya dice la pestaña.
4. **Mismo código visual en toda la app** (ver «Estados»). Quien aprende el selector ya sabe
   usar la Guía y Opciones.

## Estados (iguales en todas las pantallas)

| Estado | Cómo se ve |
| --- | --- |
| Normal | texto blanco al 55–62 % |
| Foco | texto **blanco 100 % en negrita** + **línea blanca fina** (2–3 dp) debajo, del ancho del texto |
| En uso / elegido / en vivo | **punto verde** `#5BE9A0` (≈7–8 dp) después del texto (o antes, en etiquetas) |
| Sin señal / no disponible | texto al 25 % |
| Peligro (Papelera) | rosa `#FF9A9A`, sin fondo |
| Ahora / activo / interruptor encendido | cian `#00B8D9` (línea de «ahora» de la Guía, barra de avance, interruptor) |
| Recordatorio | anillo cian (borde de 1,5 dp, sin relleno) junto al título |

El foco nunca es un fondo de color, un velo ni un recuadro.

## Tipografía y jerarquía (Roboto)

- **Etiqueta de sección / eyebrow**: MAYÚSCULAS, espaciado 0,2 em, `sans-serif-medium`, blanco
  al 42 % (secciones) o 60 % (eyebrow «TVN · 001 · NACIONALES»).
- **Título**: negrita, interletrado −0,015 em (selector 28 sp; detalle de Guía ≈30 sp).
- **Opción / fila**: regular; su detalle (subtítulo, horario, Mbps) más pequeño y al 40–50 %.
- **Datos de solo lectura** (señal, estado del servicio, atajos): rejilla de 3 columnas,
  etiqueta pequeña tenue arriba y valor debajo, sin recuadro.
- **Línea técnica** al final (resolución · fps · códec · bitrate): pequeña y al 36–40 %.
- Sombra de texto suave (≈10 px, negro al 45 %) para leer sobre video claro.

## Componentes

- **Pestañas / filtros**: fila de texto arriba a la izquierda, precedida por el nombre de la
  pantalla en eyebrow («GUÍA», «OPCIONES»); la activa en negrita con línea debajo. Reloj a la
  derecha como texto («Miércoles 14 · **00:20**»), sin caja.
- **Opciones elegibles** (calidades, versiones, Moderno|Clásico, Estándar|Invertidos): texto en
  línea con su detalle debajo; pasan a otra línea si no caben (`minimalRow`). ◀ ▶ recorre la
  fila; ▲ ▼ salta a la opción más cercana de la línea vecina.
- **Filas de ajuste**: título + descripción; la acción o el valor a la derecha como texto
  («Elegir otra ›», «100 % ›», «Vincular ›»). Los botones también son texto.
- **Interruptor**: pastilla pequeña (≈40×22 dp), cian encendida y gris al 22 % apagada; es el
  único elemento «sólido» permitido.
- **Barra de avance**: línea de 2 dp, fondo blanco al 18 % y avance cian.
- **Guía**: programas como texto separados solo por una línea vertical tenue (blanco al 12 %)
  al inicio de cada uno; el que está en emisión, más claro que los futuros; línea de «ahora»
  cian con la hora encima; punto verde junto al número del canal que se está viendo.

## Al implementar

- Generar vistas desde un único bloque/generador por pantalla, como el bloque «Selector moderno
  minimalista» de `MainActivity`; mantener los IDs que comparte con el clásico.
- Medir sobre fotogramas reales y claros (TVN, Mega) antes de entregar.
- Probar foco y navegación con el control en el emulador `VibeTV`.
