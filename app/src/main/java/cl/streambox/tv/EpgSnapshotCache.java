package cl.streambox.tv;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Fast, persistent representation of an already parsed XMLTV document. */
final class EpgSnapshotCache {
    private static final int MAGIC = 0x56455047; // VEPG
    // v2 añade la sinopsis (<desc>); las instantáneas v1 se descartan y se regeneran.
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_SNAPSHOTS = 4;
    private static final int MAX_PROGRAMMES = 250_000;
    private static final int MAX_STRING_BYTES = 256 * 1024;

    private final File directory;

    EpgSnapshotCache(File directory) {
        this.directory = directory;
        if (!directory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            directory.mkdirs();
        }
    }

    synchronized EpgData load(String url, byte[] xmlBytes) {
        return loadFingerprint(url, fingerprint(xmlBytes));
    }

    /** 0.6.26: valida con la huella SHA-256 (hex) guardada en la caché, sin leer el XML. */
    synchronized EpgData loadDigest(String url, String sha256Hex) {
        byte[] fingerprint = unhex(sha256Hex);
        return fingerprint == null ? null : loadFingerprint(url, fingerprint);
    }

    synchronized void storeDigest(String url, String sha256Hex, EpgData data) {
        byte[] fingerprint = unhex(sha256Hex);
        if (fingerprint != null) storeFingerprint(url, fingerprint, data);
    }

    private EpgData loadFingerprint(String url, byte[] expectedFingerprint) {
        File file = snapshotFile(url);
        if (!file.isFile() || file.length() <= 0) return null;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new FileInputStream(file)
        ))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                remove(file);
                return null;
            }
            int fingerprintLength = input.readInt();
            if (fingerprintLength != expectedFingerprint.length) {
                remove(file);
                return null;
            }
            byte[] storedFingerprint = new byte[fingerprintLength];
            input.readFully(storedFingerprint);
            if (!Arrays.equals(expectedFingerprint, storedFingerprint)) {
                remove(file);
                return null;
            }

            int count = input.readInt();
            if (count < 0 || count > MAX_PROGRAMMES) {
                remove(file);
                return null;
            }
            List<EpgProgramme> programmes = new ArrayList<>(count);
            // 0.6.26: canal, título y sinopsis se repiten miles de veces; una sola copia.
            java.util.HashMap<String, String> pool = new java.util.HashMap<>();
            for (int index = 0; index < count; index++) {
                String channelId = pooled(pool, readString(input));
                String title = pooled(pool, readString(input));
                long startMillis = input.readLong();
                long stopMillis = input.readLong();
                String description = pooled(pool, readString(input));
                if (AppStrings.isBlank(channelId) || AppStrings.isBlank(title) || stopMillis <= startMillis) {
                    throw new IOException("La instantánea EPG no es válida.");
                }
                programmes.add(new EpgProgramme(
                        channelId, title, startMillis, stopMillis, description));
            }
            file.setLastModified(System.currentTimeMillis());
            return new EpgData(programmes);
        } catch (IOException | RuntimeException error) {
            remove(file);
            return null;
        }
    }

    synchronized void store(String url, byte[] xmlBytes, EpgData data) {
        storeFingerprint(url, fingerprint(xmlBytes), data);
    }

    private void storeFingerprint(String url, byte[] sourceFingerprint, EpgData data) {
        if (data == null || data.getProgrammeCount() <= 0) return;
        List<EpgProgramme> programmes = data.getProgrammes();
        if (programmes.size() > MAX_PROGRAMMES) return;

        File target = snapshotFile(url);
        File temporary = new File(directory, target.getName() + ".tmp");
        try (FileOutputStream fileOutput = new FileOutputStream(temporary);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(fileOutput))) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(sourceFingerprint.length);
            output.write(sourceFingerprint);
            output.writeInt(programmes.size());
            for (EpgProgramme programme : programmes) {
                writeString(output, programme.getChannelId());
                writeString(output, programme.getTitle());
                output.writeLong(programme.getStartMillis());
                output.writeLong(programme.getStopMillis());
                writeString(output, programme.getDescription());
            }
            output.flush();
            fileOutput.getFD().sync();
        } catch (IOException error) {
            remove(temporary);
            return;
        }

        if (target.exists() && !target.delete()) {
            remove(temporary);
            return;
        }
        if (!temporary.renameTo(target)) {
            remove(temporary);
            return;
        }
        target.setLastModified(System.currentTimeMillis());
        trim();
    }

    private void trim() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".bin"));
        if (files == null || files.length <= MAX_SNAPSHOTS) return;
        Arrays.sort(files, new Comparator<>() {
            @Override
            public int compare(File first, File second) {
                return Long.compare(second.lastModified(), first.lastModified());
            }
        });
        for (int index = MAX_SNAPSHOTS; index < files.length; index++) {
            remove(files[index]);
        }
    }

    private File snapshotFile(String url) {
        return new File(directory, hex(fingerprint(url.getBytes(StandardCharsets.UTF_8))) + ".bin");
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Texto EPG fuera de límite.");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Texto EPG fuera de límite.");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    static String pooled(java.util.Map<String, String> pool, String value) {
        if (value == null || value.isEmpty()) return value;
        String existing = pool.putIfAbsent(value, value);
        return existing == null ? value : existing;
    }

    private static byte[] unhex(String value) {
        if (value == null || value.length() != 64) return null;
        byte[] result = new byte[32];
        for (int index = 0; index < 32; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static byte[] fingerprint(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible.", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void remove(File file) {
        if (file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
