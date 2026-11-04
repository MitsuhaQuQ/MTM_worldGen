package com.mtm.worldgen.logic;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Erosion of logic regions kept on disk, since a region costs about half a second to erode and its result only
 * depends on the seed, the settings and the algorithm. It only saves time: a file that is missing, damaged or made for
 * other terrain is computed again, with exactly the same result.
 * <p>
 * Each file carries a key made from the seed, the settings and the algorithm version, and the land the erosion
 * started from at a few points of the region. The key catches other worlds and other settings; the land catches
 * changes to the terrain code that nobody remembered to mark with a new version.
 */
final class ErosionStore {
    private static final int MAGIC = 0x4D544D45;
    /** Bump when the file layout or the erosion algorithm changes. */
    private static final int FORMAT = 5;

    private final Path directory;
    private final long key;

    ErosionStore(Path directory, long seed, String settings, int algorithmVersion) {
        this.directory = directory;
        long hash = fnv(0xCBF29CE484222325L, settings.getBytes(StandardCharsets.UTF_8));
        this.key = fnv(hash, ByteBuffer.allocate(16).putLong(seed).putInt(algorithmVersion).putInt(FORMAT).array());
    }

    /** The stored erosion of a region, or null when there is none for this terrain. */
    ErosionLayer.Eroded read(int regionX, int regionZ, double[] checks, int length) {
        Path file = file(regionX, regionZ);
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new InflaterInputStream(raw, new Inflater(), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT || in.readLong() != key || in.readInt() != length
                    || in.readInt() != checks.length) {
                return null;
            }
            for (double check : checks) {
                // Exact: the same code on the same land gives the same bits
                if (Double.doubleToLongBits(in.readDouble()) != Double.doubleToLongBits(check)) {
                    return null;
                }
            }
            float[] change = readFloats(in, length);
            float[] creek = change == null ? null : readFloats(in, length);
            byte[] down = creek == null ? null : in.readNBytes(length);
            return down == null || down.length != length ? null : new ErosionLayer.Eroded(change, creek, down);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            // A damaged file is only a region to compute again
            return null;
        }
    }

    /** Stores the erosion of a region. Failures are ignored: the region is then computed again next time. */
    void write(int regionX, int regionZ, double[] checks, ErosionLayer.Eroded eroded) {
        float[] values = eroded.change();
        Path file = file(regionX, regionZ);
        Path temporary = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().getId() + ".tmp");
        try {
            Files.createDirectories(directory);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(values.length * 2);
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(buffer, new Deflater(Deflater.BEST_SPEED), 1 << 16))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT);
                out.writeLong(key);
                out.writeInt(values.length);
                out.writeInt(checks.length);
                for (double check : checks) {
                    out.writeDouble(check);
                }
                writeFloats(out, values);
                writeFloats(out, eroded.creek());
                out.write(eroded.down());
            }
            try (OutputStream out = Files.newOutputStream(temporary)) {
                buffer.writeTo(out);
            }
            // Readers never see half a file: the finished one takes the place of the old in one step
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Nothing more to do; a stray temporary file is harmless
            }
        }
    }

    private static float[] readFloats(DataInputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length * Float.BYTES);
        if (bytes.length != length * Float.BYTES) {
            return null;
        }
        float[] values = new float[length];
        ByteBuffer.wrap(bytes).asFloatBuffer().get(values);
        return values;
    }

    private static void writeFloats(DataOutputStream out, float[] values) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * Float.BYTES);
        bytes.asFloatBuffer().put(values);
        out.write(bytes.array());
    }

    private Path file(int regionX, int regionZ) {
        return directory.resolve("r." + regionX + "." + regionZ + ".bin");
    }

    /** 64-bit FNV-1a, which unlike String.hashCode is long enough that two settings never share a key in practice. */
    private static long fnv(long hash, byte[] bytes) {
        for (byte b : bytes) {
            hash ^= b & 0xFF;
            hash *= 0x100000001B3L;
        }
        return hash;
    }
}
