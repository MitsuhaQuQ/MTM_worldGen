package com.mtm.worldgen.webmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebMapTest {
    /** Ground of the test chunk: stone up to this height, as the top of its highest block. */
    private static final int GROUND = 64;

    /**
     * A chunk at the given position: stone up to {@link #GROUND}, a wooden house on top of it in the corner, a lake
     * down to sand in the middle and a tree, in sections as Minecraft saves them.
     */
    private static Map<String, Object> chunk() {
        String[][] blocks = new String[16 * 16][];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                String[] column = new String[384];
                java.util.Arrays.fill(column, "minecraft:air");
                for (int y = 0; y < GROUND + 64; y++) {
                    column[y] = "minecraft:stone";
                }
                if (x < 4 && z < 4) {
                    // A house: planks from the ground up, a roof of slabs
                    for (int y = GROUND + 64; y < GROUND + 64 + 6; y++) {
                        column[y] = "minecraft:oak_planks";
                    }
                    column[GROUND + 64 + 6] = "minecraft:stone_brick_slab";
                } else if (x >= 6 && x < 10 && z >= 6 && z < 10) {
                    // A lake four blocks deep over sand
                    for (int y = GROUND + 64 - 5; y < GROUND + 64; y++) {
                        column[y] = y == GROUND + 64 - 5 ? "minecraft:sand" : "minecraft:water";
                    }
                } else if (x == 12 && z == 12) {
                    column[GROUND + 64 - 1] = "minecraft:grass_block";
                    for (int y = GROUND + 64; y < GROUND + 64 + 5; y++) {
                        column[y] = "minecraft:oak_log";
                    }
                    column[GROUND + 64 + 5] = "minecraft:oak_leaves";
                }
                blocks[z * 16 + x] = column;
            }
        }
        List<Object> sections = new ArrayList<>();
        for (int sectionY = -4; sectionY < 20; sectionY++) {
            List<String> palette = new ArrayList<>();
            int[] indices = new int[4096];
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        String name = blocks[z * 16 + x][(sectionY + 4) * 16 + y];
                        int index = palette.indexOf(name);
                        if (index < 0) {
                            index = palette.size();
                            palette.add(name);
                        }
                        indices[(y * 16 + z) * 16 + x] = index;
                    }
                }
            }
            Map<String, Object> states = new LinkedHashMap<>();
            List<Object> entries = new ArrayList<>();
            for (String name : palette) {
                entries.add(Map.of("Name", name));
            }
            states.put("palette", entries);
            if (palette.size() > 1) {
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                int perLong = 64 / bits;
                long[] data = new long[(4096 + perLong - 1) / perLong];
                for (int i = 0; i < 4096; i++) {
                    data[i / perLong] |= (long) indices[i] << ((i % perLong) * bits);
                }
                states.put("data", data);
            }
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("Y", (byte) sectionY);
            section.put("block_states", states);
            sections.add(section);
        }
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("Status", "minecraft:full");
        chunk.put("sections", sections);
        return chunk;
    }

    @Test
    void columnsShowTheLandUnderBuildingsAndTheWaterOnIt() {
        Raster raster = new Raster();
        assertTrue(RegionScan.scan(chunk(), raster, 0, 0));
        int house = Raster.index(1, 1);
        assertEquals(GROUND, raster.ground[house], "ground under the house");
        assertEquals(Raster.UNKNOWN, raster.water[house]);
        assertEquals(Raster.ROCK, raster.cover[house] & Raster.KIND_MASK);
        int lake = Raster.index(7, 7);
        assertEquals(GROUND - 4, raster.ground[lake], "lake bed");
        assertEquals(GROUND, raster.water[lake], "lake surface");
        int tree = Raster.index(12, 12);
        assertEquals(GROUND, raster.ground[tree]);
        assertEquals(Raster.GRASS, raster.cover[tree] & Raster.KIND_MASK);
        assertTrue((raster.cover[tree] & Raster.FOREST) != 0, "tree");
    }

    /** Rivers a pixel wide stay on the map when four pixels merge into one. */
    @Test
    void waterSurvivesMerging() {
        Raster child = new Raster();
        for (int z = 0; z < Raster.SIZE; z++) {
            for (int x = 0; x < Raster.SIZE; x++) {
                int i = Raster.index(x, z);
                child.ground[i] = 80;
                child.cover[i] = Raster.GRASS;
                if (x == 100) {
                    child.ground[i] = 70;
                    child.water[i] = 74;
                }
            }
        }
        Raster merged = new Raster();
        merged.merge(child, 1, 0);
        int river = Raster.index(Raster.SIZE / 2 + 50, 10);
        assertEquals(74, merged.water[river]);
        assertEquals(Raster.UNKNOWN, merged.water[Raster.index(Raster.SIZE / 2 + 51, 10)]);
        assertTrue(merged.known(Raster.index(Raster.SIZE / 2 + 51, 10)));
        assertTrue(!merged.known(Raster.index(10, 10)), "the quarter no child covers stays empty");
    }

    @Test
    void serverDrawsTilesOfRegionFiles(@TempDir Path root) throws IOException {
        Path regions = root.resolve("region");
        writeRegion(regions.resolve("r.0.-1.mca"), chunk());
        Path config = root.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("mtm_worldgen-webmap.json"), "{\"enabled\": true, \"bind\": \"127.0.0.1\", \"port\": 0}");
        WebMap.start(root, regions, config, "Test world", new WebMap.Log() {
            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message, Throwable error) {
                throw new AssertionError(message, error);
            }
        });
        try {
            String address = WebMap.status();
            assertNotNull(address);
            assertTrue(get(address).startsWith("<!doctype html>"), "page");
            assertTrue(get(address + "info.json").contains("\"bounds\":[0,-512,512,0]"));
            for (int level = 0; level <= 3; level++) {
                HttpURLConnection tile = (HttpURLConnection) URI.create(address + "tiles/" + level + "/0/-1.png").toURL().openConnection();
                assertEquals(200, tile.getResponseCode(), "tile of level " + level);
                assertEquals("image/png", tile.getContentType());
            }
            HttpURLConnection empty = (HttpURLConnection) URI.create(address + "tiles/0/5/5.png").toURL().openConnection();
            assertEquals(404, empty.getResponseCode());
            assertTrue(Files.exists(root.resolve("mtm_worldgen/webmap/0/0.-1.png")), "tile cached");
        } finally {
            WebMap.stop();
        }
        assertNull(WebMap.status());
    }

    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** A region file holding one chunk, its first, compressed with zlib as Minecraft does. */
    private static void writeRegion(Path file, Map<String, Object> chunk) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(compressed))) {
            out.writeByte(10);
            out.writeUTF("");
            writeCompound(out, chunk);
        }
        byte[] payload = compressed.toByteArray();
        int sectors = (payload.length + 5 + 4095) / 4096;
        ByteArrayOutputStream region = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(region);
        out.writeInt((2 << 8) | sectors);
        out.write(new byte[8192 - 4]);
        out.writeInt(payload.length + 1);
        out.writeByte(2);
        out.write(payload);
        out.write(new byte[sectors * 4096 - payload.length - 5]);
        Files.createDirectories(file.getParent());
        Files.write(file, region.toByteArray());
    }

    @SuppressWarnings("unchecked")
    private static void writeCompound(DataOutputStream out, Map<String, Object> map) throws IOException {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            out.writeByte(type(value));
            out.writeUTF(entry.getKey());
            writeValue(out, value);
        }
        out.writeByte(0);
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(DataOutputStream out, Object value) throws IOException {
        if (value instanceof Byte b) {
            out.writeByte(b);
        } else if (value instanceof String s) {
            out.writeUTF(s);
        } else if (value instanceof long[] longs) {
            out.writeInt(longs.length);
            for (long l : longs) {
                out.writeLong(l);
            }
        } else if (value instanceof List<?> list) {
            out.writeByte(list.isEmpty() ? 0 : type(list.get(0)));
            out.writeInt(list.size());
            for (Object element : list) {
                writeValue(out, element);
            }
        } else if (value instanceof Map<?, ?> map) {
            writeCompound(out, (Map<String, Object>) map);
        } else {
            throw new IllegalArgumentException(String.valueOf(value));
        }
    }

    private static int type(Object value) {
        if (value instanceof Byte) {
            return 1;
        }
        if (value instanceof String) {
            return 8;
        }
        if (value instanceof long[]) {
            return 12;
        }
        if (value instanceof List) {
            return 9;
        }
        return 10;
    }
}
