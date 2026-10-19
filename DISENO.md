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
| Selector «Fuentes y calidades» | `selector-respaldos.jpg` · cuadrícula: `selector-calidad-app.jpg`, `selector-versiones-app.jpg` | implementado (0.5.95); cuadrícula y escala tipográfica en la 0.6.18 |
| Guía | `guia-ahora.jpg`, `guia-futuro.jpg` (mockup) · `guia-app.jpg` (app) · bloques: `guia-bloques-app.jpg` | implementado (0.5.96); bloques B1 en la 0.6.14 |
| Opciones | `opciones-*.jpg` (mockup) · `opciones-*-app.jpg` (app); pestaña Proveedores: `opciones-canales-app.jpg`, `opciones-proveedores-app.jpg` | implementado (0.5.96); Proveedores en la 0.6.02 |
| OSD | `osd.jpg` (app) | implementado (0.5.96) |
| Escenas (salir, actualización, papelera, Premium, GitHub, token vencido) | `*-app.jpg` | implementado (0.5.98) |
| Avisos (mejor calidad, recordatorio) y MediaFlow | `recordatorio-app.jpg`, `mediaflow-app.jpg` | implementado (0.5.98) |
| Pantalla de error, ajuste del logo, avisos breves, «CONECTANDO» | `error-app.jpg`, `logo-ajuste-app.jpg`, `aviso-app.jpg` | implementado (0.5.99) |

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

Colores de texto: blancos con transparencia (`minimal_muted` = #9EFFFFFF para lo secundario). El gris azulado `osd_muted` es del estilo anterior y solo queda en el clásico.

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
- **Selector «Fuentes y calidades» (0.6.18; tamaños de 0.6.21, opción «A» aprobada)**: panel
  de 380 dp a 48 dp del borde derecho, centrado en alto. Solo cuatro tamaños de letra: título 25 sp,
  opciones 16 sp, detalles (Mbps, estado, acciones) 11,5 sp, etiquetas y línea técnica 9,5 sp
  (0.6.18 con 15/9,5/6,5/6 sp y 270 dp y 0.6.20 con 20/12,5/9/7,5 sp y 320 dp quedaron pequeñas). Versiones y
  calidades siempre a la vista en una **cuadrícula de 4 columnas iguales** (8 dp entre columnas
  y filas); un nombre que no cabe ocupa varias columnas y, si ni así, termina en «…». Una
  línea fina (blanco al 10 %) separa las opciones de las acciones. Números tabulares en Mbps.
- **Opciones elegibles** (calidades, versiones, Moderno|Clásico, Estándar|Invertidos): texto en
  línea con su detalle debajo; pasan a otra línea si no caben (`minimalRow`). ◀ ▶ recorre la
  fila; ▲ ▼ salta a la opción más cercana de la línea vecina.
- **Filas de ajuste**: título + descripción; la acción o el valor a la derecha como texto
  («Elegir otra ›», «100 % ›», «Vincular ›»). Los botones también son texto.
- **Interruptor**: pastilla pequeña (≈40×22 dp), cian encendida y gris al 22 % apagada; es el
  único elemento «sólido» permitido. Excepción aprobada (0.6.13, Opciones › Canales): los
  interruptores de las listas llevan «Encendida»/«Apagada» a su izquierda y con foco se marcan
  solo con **negrita** y la bolita blanca, **sin línea** debajo; su borde derecho se alinea con
  el de la última pestaña («Sistema»).
- **Barra de avance**: línea de 2 dp, fondo blanco al 18 % y avance cian.
- **Guía** (0.6.14, B1 aprobada; excepción a «sin cajas»): cada programa es un **bloque tenue**
  del largo de su duración (blanco al 5,5 %, al aire 9 %, esquinas de 3 dp, 2 dp entre bloques),
  porque solo con texto parecía terminar donde terminaba el título. Texto a la izquierda,
  centrado en el alto; el tramo ya emitido del programa al aire en cian al 13 %; el **foco** es
  el bloque más claro (20 %) con **borde blanco** de 1 dp (no línea bajo el texto). Filas cada
  34 dp. Línea de «ahora» cian con la hora encima; punto verde junto al número del canal que se
  está viendo. Referencia: `docs/diseno/guia-bloques-app.jpg`.

## Opciones (0.6.02, opción A aprobada)

- Siete pestañas: En reproducción · Video y audio · Interfaz · Recordatorios · Canales ·
  **Proveedores** · Sistema. Canales tiene las listas y los ocultos; Proveedores, los
  interruptores de proveedores, Highfly Premium (vincular y región) y avanzado. Cada página es
  una sola columna y cabe sin cortarse.
- Highfly Premium aparece una sola vez (en Proveedores); Sistema › Vinculaciones solo tiene GitHub.
- Debajo de Opciones solo se ve el video: ni OSD, ni pantalla de error, ni «CONECTANDO».
- Sin ningún dato de la señal, «Información de la señal» es una sola línea.
- Las opciones en línea (calidades) pasan a la línea siguiente cuando no caben; nunca se parten.

## Al implementar

- Generar vistas desde un único bloque/generador por pantalla, como el bloque «Selector moderno
  minimalista» de `MainActivity`; mantener los IDs que comparte con el clásico.
- Medir sobre fotogramas reales y claros (TVN, Mega) antes de entregar.
- Probar foco y navegación con el control en el emulador `VibeTV`.
