package cl.streambox.tv;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Recordatorio de un programa de la Guía. Se identifica por el canal y la hora
 * de inicio: marcar dos veces el mismo programa lo quita.
 */
final class ProgramReminder {
    /** Un recordatorio vencido se descarta un minuto después de su inicio. */
    static final long EXPIRY_GRACE_MILLIS = 60_000L;

    final String channelIdentity;
    final String channelName;
    final String title;
    final long startMillis;
    final long stopMillis;

    ProgramReminder(
            String channelIdentity,
            String channelName,
            String title,
            long startMillis,
            long stopMillis
    ) {
        this.channelIdentity = safe(channelIdentity);
        this.channelName = safe(channelName);
        this.title = safe(title);
        this.startMillis = startMillis;
        this.stopMillis = Math.max(stopMillis, startMillis);
    }

    String id() {
        return channelIdentity + "@" + startMillis;
    }

    boolean isExpired(long nowMillis) {
        return startMillis + EXPIRY_GRACE_MILLIS < nowMillis;
    }

    /** Vigentes (no vencidos), ordenados por hora de inicio. */
    static List<ProgramReminder> upcoming(List<ProgramReminder> reminders, long nowMillis) {
        List<ProgramReminder> result = new ArrayList<>();
        for (ProgramReminder reminder : reminders) {
            if (!reminder.isExpired(nowMillis)) result.add(reminder);
        }
        Collections.sort(result, Comparator.comparingLong((ProgramReminder r) -> r.startMillis)
                .thenComparing(r -> r.channelName));
        return result;
    }

    /** Agrega o quita el recordatorio; devuelve true si quedó agregado. */
    static boolean toggle(List<ProgramReminder> reminders, ProgramReminder reminder) {
        for (int index = 0; index < reminders.size(); index++) {
            if (reminders.get(index).id().equals(reminder.id())) {
                reminders.remove(index);
                return false;
            }
        }
        reminders.add(reminder);
        return true;
    }

    static ProgramReminder find(List<ProgramReminder> reminders, String id) {
        for (ProgramReminder reminder : reminders) {
            if (reminder.id().equals(id)) return reminder;
        }
        return null;
    }

    /** Una línea por recordatorio, campos codificados y separados por tabulador. */
    static String encode(List<ProgramReminder> reminders) {
        StringBuilder text = new StringBuilder();
        for (ProgramReminder reminder : reminders) {
            if (text.length() > 0) text.append('\n');
            text.append(escape(reminder.channelIdentity)).append('\t')
                    .append(escape(reminder.channelName)).append('\t')
                    .append(escape(reminder.title)).append('\t')
                    .append(reminder.startMillis).append('\t')
                    .append(reminder.stopMillis);
        }
        return text.toString();
    }

    /** Lee lo guardado; ignora líneas dañadas en vez de fallar. */
    static List<ProgramReminder> decode(String text) {
        List<ProgramReminder> reminders = new ArrayList<>();
        if (text == null || text.isEmpty()) return reminders;
        for (String line : text.split("\n")) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 5) continue;
            try {
                ProgramReminder reminder = new ProgramReminder(
                        unescape(fields[0]),
                        unescape(fields[1]),
                        unescape(fields[2]),
                        Long.parseLong(fields[3]),
                        Long.parseLong(fields[4])
                );
                if (!reminder.channelIdentity.isEmpty()) reminders.add(reminder);
            } catch (RuntimeException ignored) {
                // Línea inválida: se descarta.
            }
        }
        return reminders;
    }

    private static String escape(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String unescape(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ProgramReminder)) return false;
        ProgramReminder that = (ProgramReminder) other;
        return startMillis == that.startMillis
                && stopMillis == that.stopMillis
                && channelIdentity.equals(that.channelIdentity)
                && channelName.equals(that.channelName)
                && title.equals(that.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelIdentity, channelName, title, startMillis, stopMillis);
    }
}
