package com.mtm.worldgen.logic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErosionStoreTest {
    private static final long SEED = 12345L;

    @Test
    void storedRegionsReadBackOnlyForTheSameTerrain(@TempDir Path directory) throws IOException {
        Random random = new Random(1);
        float[] values = new float[1000];
        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextFloat() * -100.0F;
        }
        double[] checks = {1.5, 200.25, 63.0};
        ErosionStore store = new ErosionStore(directory, SEED, "{}", 1);
        store.write(2, -3, checks, values);
        assertArrayEquals(values, store.read(2, -3, checks, values.length));
        assertNull(store.read(2, -2, checks, values.length), "another region");
        assertNull(store.read(2, -3, new double[]{1.5, 200.25, 63.000001}, values.length), "other land");
        assertNull(store.read(2, -3, checks, values.length + 1), "another grid");
        assertNull(new ErosionStore(directory, SEED + 1, "{}", 1).read(2, -3, checks, values.length), "another seed");
        assertNull(new ErosionStore(directory, SEED, "{ }", 1).read(2, -3, checks, values.length), "other settings");
        assertNull(new ErosionStore(directory, SEED, "{}", 2).read(2, -3, checks, values.length), "another version");

        Path file = directory.resolve("r.2.-3.bin");
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length / 2));
        assertNull(store.read(2, -3, checks, values.length), "a damaged file");
    }

    @Test
    void cachedTerrainMatchesComputedTerrain(@TempDir Path directory) {
        WorldgenSettings settings = new WorldgenSettings();
        TerrainLogic plain = new TerrainLogic(SEED, settings);
        TerrainLogic writing = new TerrainLogic(SEED, settings);
        writing.useCacheDirectory(directory);
        TerrainLogic reading = new TerrainLogic(SEED, settings);
        reading.useCacheDirectory(directory);
        // Well inside region 0,0, so only its own erosion is read
        int[][] points = new int[64][];
        for (int i = 0; i < points.length; i++) {
            points[i] = new int[]{1024 + 251 * (i % 8), 1024 + 257 * (i / 8)};
        }
        double[] expected = new double[points.length];
        for (int i = 0; i < points.length; i++) {
            expected[i] = plain.sampleLandform(points[i][0], points[i][1]);
            assertEquals(expected[i], writing.sampleLandform(points[i][0], points[i][1]), "while computing at " + i);
        }
        assertTrue(Files.exists(directory.resolve("r.0.0.bin")), "region 0,0 was not stored");
        for (int i = 0; i < points.length; i++) {
            assertEquals(expected[i], reading.sampleLandform(points[i][0], points[i][1]), "from the store at " + i);
        }
    }
}
