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
    private static final int FORMAT_VERSION = 1;
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
        File file = snapshotFile(url);
        if (!file.isFile() || file.length() <= 0) return null;
        byte[] expectedFingerprint = fingerprint(xmlBytes);
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
            for (int index = 0; index < count; index++) {
                String channelId = readString(input);
                String title = readString(input);
                long startMillis = input.readLong();
                long stopMillis = input.readLong();
                if (channelId.isBlank() || title.isBlank() || stopMillis <= startMillis) {
                    throw new IOException("La instantánea EPG no es válida.");
                }
                programmes.add(new EpgProgramme(channelId, title, startMillis, stopMillis));
            }
            file.setLastModified(System.currentTimeMillis());
            return new EpgData(programmes);
        } catch (IOException | RuntimeException error) {
            remove(file);
            return null;
        }
    }

    synchronized void store(String url, byte[] xmlBytes, EpgData data) {
        if (data == null || data.getProgrammeCount() <= 0) return;
        List<EpgProgramme> programmes = data.getProgrammes();
        if (programmes.size() > MAX_PROGRAMMES) return;

        File target = snapshotFile(url);
        File temporary = new File(directory, target.getName() + ".tmp");
        try (FileOutputStream fileOutput = new FileOutputStream(temporary);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(fileOutput))) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            byte[] sourceFingerprint = fingerprint(xmlBytes);
            output.writeInt(sourceFingerprint.length);
            output.write(sourceFingerprint);
            output.writeInt(programmes.size());
            for (EpgProgramme programme : programmes) {
                writeString(output, programme.getChannelId());
                writeString(output, programme.getTitle());
                output.writeLong(programme.getStartMillis());
                output.writeLong(programme.getStopMillis());
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
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
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
