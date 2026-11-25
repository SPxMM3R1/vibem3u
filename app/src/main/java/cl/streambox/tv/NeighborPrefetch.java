package cl.streambox.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Vecinos que se precalientan con el canal estable (0.6.74: solo los directos).
 *
 * <p>Hasta la 0.6.73 también se resolvían por adelantado los vecinos con resolutor
 * (TvVoo/Vavoo, MediaFlow, Meganoticias…). Cada uno son varios segundos de red y CPU que
 * competían con el canal en pantalla, casi siempre se descartaban porque el usuario no iba a
 * ellos, y en Vavoo sumaban pedidos desde la misma IP. Además, si el CH+ caía en el vecino que
 * se estaba resolviendo, la apertura compartía esa resolución y al cortarse recibía «Solicitud
 * cancelada». Pedido del usuario (2026-11-24): «debería ser solo para directos».
 *
 * <p>Clase pura, sin Android, para poder probarla en JVM ({@code NeighborPrefetchTest}).
 */
final class NeighborPrefetch {
    private NeighborPrefetch() {}

    /**
     * Vecinos que corresponde precalentar: primero el siguiente y después el anterior, sin
     * repetir y saltando el canal que suena. Los que tienen resolutor no entran. Una lista de
     * un solo canal (o vacía) no tiene vecinos; en los bordes se vuelve al otro extremo.
     *
     * @param hasResolver decide si un canal necesita resolutor (es el mismo criterio que usa
     *                    la precarga para elegir entre calentar la conexión o resolver)
     */
    static List<Channel> directNeighbors(List<Channel> channels, int channelIndex,
            Channel playing, Predicate<Channel> hasResolver) {
        if (channels == null || channels.size() < 2) return Collections.emptyList();
        int size = channels.size();
        int index = ((channelIndex % size) + size) % size;
        List<Channel> neighbors = new ArrayList<>(2);
        for (int delta : new int[] {1, -1}) {
            Channel neighbor = channels.get(((index + delta) % size + size) % size);
            if (neighbor == null || neighbor == playing) continue;
            if (hasResolver != null && hasResolver.test(neighbor)) continue;
            // Con dos canales, +1 y -1 son el mismo: entra una sola vez.
            if (!neighbors.contains(neighbor)) neighbors.add(neighbor);
        }
        return neighbors;
    }
}
