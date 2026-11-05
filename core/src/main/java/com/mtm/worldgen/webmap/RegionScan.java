package com.mtm.worldgen.webmap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads the land of one region file into a raster of level 0: each column is scanned from the top down to its first
 * natural ground block (see {@link BlockClass}). The file is read as it is on disk, which lags the game by its last
 * save, and chunks that cannot be read are left out instead of failing the tile: the server may be writing the file.
 */
final class RegionScan {
    private static final int SECTOR = 4096;
    private static final int CHUNKS = 32;
    private static final int GZIP = 1;
    private static final int ZLIB = 2;
    private static final int NONE = 3;
    /** Set on the compression type when the chunk is too large for the region file and lies in a .mcc file of its own. */
    private static final int EXTERNAL = 0x80;
    /** Chunks from this generation step on have their blocks. */
    private static final Set<String> WITH_BLOCKS = Set.of("surface", "carvers", "liquid_carvers", "features",
            "initialize_light", "light", "spawn", "heightmaps", "full");

    private RegionScan() {
    }

    /** The raster of a region file, or null if the file holds no readable chunk with blocks. */
    static Raster read(Path file, int regionX, int regionZ) throws IOException {
        byte[] data = Files.readAllBytes(file);
        if (data.length < 2 * SECTOR) {
            return null;
        }
        Raster raster = new Raster();
        boolean any = false;
        for (int i = 0; i < CHUNKS * CHUNKS; i++) {
            int entry = ((data[4 * i] & 0xFF) << 24) | ((data[4 * i + 1] & 0xFF) << 16) | ((data[4 * i + 2] & 0xFF) << 8) | (data[4 * i + 3] & 0xFF);
            int offset = (entry >>> 8) * SECTOR;
            if (entry == 0 || offset + 5 > data.length) {
                continue;
            }
            int localX = i % CHUNKS;
            int localZ = i / CHUNKS;
            try {
                Map<String, Object> chunk = chunk(file, data, offset, regionX * CHUNKS + localX, regionZ * CHUNKS + localZ);
                if (chunk != null && scan(chunk, raster, localX * 16, localZ * 16)) {
                    any = true;
                }
            } catch (IOException | RuntimeException e) {
                // A chunk being written, or damaged: the rest of the tile is still worth showing
            }
        }
        return any ? raster : null;
    }

    private static Map<String, Object> chunk(Path file, byte[] data, int offset, int chunkX, int chunkZ) throws IOException {
        int length = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
        int type = data[offset + 4] & 0xFF;
        InputStream raw;
        if ((type & EXTERNAL) != 0) {
            Path external = file.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
            if (!Files.exists(external)) {
                return null;
            }
            raw = new ByteArrayInputStream(Files.readAllBytes(external));
            type &= ~EXTERNAL;
        } else {
            if (length <= 1 || offset + 4 + length > data.length) {
                return null;
            }
            raw = new ByteArrayInputStream(data, offset + 5, length - 1);
        }
        InputStream stream = switch (type) {
            case GZIP -> new GZIPInputStream(raw);
            case ZLIB -> new InflaterInputStream(raw);
            case NONE -> raw;
            // LZ4 and custom compression need libraries the map does without
            default -> null;
        };
        if (stream == null) {
            return null;
        }
        try (InputStream in = stream) {
            return Nbt.read(in);
        }
    }

    /**
     * Fills the 16 by 16 pixels of a chunk. Chunks from before 1.18, which keep their blocks elsewhere, and chunks
     * still being generated have nothing to show.
     */
    @SuppressWarnings("unchecked")
    static boolean scan(Map<String, Object> chunk, Raster raster, int pixelX, int pixelZ) {
        Object status = chunk.get("Status");
        if (status instanceof String s && !WITH_BLOCKS.contains(s.startsWith("minecraft:") ? s.substring(10) : s)) {
            return false;
        }
        if (!(chunk.get("sections") instanceof List<?> sections)) {
            return false;
        }
        // Sections from the top down, each decoded only once a column reaches it
        Map<Integer, Map<String, Object>> byY = new HashMap<>();
        int top = Integer.MIN_VALUE;
        int bottom = Integer.MAX_VALUE;
        for (Object element : sections) {
            if (element instanceof Map<?, ?> section && section.get("Y") instanceof Number y) {
                byY.put(y.intValue(), (Map<String, Object>) section);
                top = Math.max(top, y.intValue());
                bottom = Math.min(bottom, y.intValue());
            }
        }
        if (byY.isEmpty()) {
            return false;
        }
        Map<Integer, BlockClass[]> decoded = new HashMap<>();
        boolean any = false;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                if (column(byY, decoded, top, bottom, x, z, raster, Raster.index(pixelX + x, pixelZ + z))) {
                    any = true;
                }
            }
        }
        return any;
    }

    /**
     * Scans one column. Water keeps counting over whatever lies below it before the ground, since oceans hold ruins,
     * wrecks and monuments; a pool on a roof shows as a little water, which is rare. Trees and snow layers count only
     * above water.
     */
    private static boolean column(Map<Integer, Map<String, Object>> byY, Map<Integer, BlockClass[]> decoded, int top, int bottom,
                                  int x, int z, Raster raster, int pixel) {
        int waterTop = Raster.UNKNOWN;
        boolean frozen = false;
        boolean tree = false;
        boolean snowLayer = false;
        for (int sectionY = top; sectionY >= bottom; sectionY--) {
            BlockClass[] blocks = decoded.computeIfAbsent(sectionY, y -> decode(byY.get(y)));
            if (blocks == null) {
                continue;
            }
            for (int y = 15; y >= 0; y--) {
                BlockClass block = blocks[(y * 16 + z) * 16 + x];
                int height = sectionY * 16 + y;
                switch (block) {
                    case WATER, FROZEN -> {
                        if (waterTop == Raster.UNKNOWN) {
                            waterTop = height + 1;
                        }
                        frozen |= block == BlockClass.FROZEN;
                    }
                    case TREE -> tree = waterTop == Raster.UNKNOWN;
                    case SNOW_LAYER -> snowLayer = waterTop == Raster.UNKNOWN;
                    case LAVA -> {
                        raster.ground[pixel] = (short) (height + 1);
                        raster.cover[pixel] = Raster.LAVA;
                        return true;
                    }
                    case PASS -> {
                        // Air, plants and anything built: the land is further down
                    }
                    default -> {
                        raster.ground[pixel] = (short) (height + 1);
                        raster.water[pixel] = (short) waterTop;
                        int flags = (tree ? Raster.FOREST : 0) | (snowLayer ? Raster.SNOWY : 0) | (frozen ? Raster.FROZEN : 0);
                        raster.cover[pixel] = (byte) (cover(block) | flags);
                        return true;
                    }
                }
            }
        }
        if (waterTop != Raster.UNKNOWN) {
            raster.water[pixel] = (short) waterTop;
            return true;
        }
        return false;
    }

    private static byte cover(BlockClass block) {
        return switch (block) {
            case GRASS -> Raster.GRASS;
            case SOIL -> Raster.SOIL;
            case SAND -> Raster.SAND;
            case GRAVEL -> Raster.GRAVEL;
            case SNOW -> Raster.SNOW;
            case ICE -> Raster.ICE;
            case TERRACOTTA -> Raster.TERRACOTTA;
            default -> Raster.ROCK;
        };
    }

    /** The classes of a section's 4096 blocks, indexed (y * 16 + z) * 16 + x, or null if it has no blocks. */
    @SuppressWarnings("unchecked")
    static BlockClass[] decode(Map<String, Object> section) {
        if (section == null || !(section.get("block_states") instanceof Map<?, ?> states)
                || !(states.get("palette") instanceof List<?> palette) || palette.isEmpty()) {
            return null;
        }
        BlockClass[] classes = new BlockClass[palette.size()];
        for (int i = 0; i < classes.length; i++) {
            classes[i] = BlockClass.of(name(palette.get(i)));
        }
        BlockClass[] blocks = new BlockClass[4096];
        if (classes.length == 1 || !(states.get("data") instanceof long[] data)) {
            java.util.Arrays.fill(blocks, classes[0]);
            return blocks;
        }
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(classes.length - 1));
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        for (int i = 0; i < 4096; i++) {
            int word = i / perLong;
            if (word >= data.length) {
                blocks[i] = BlockClass.PASS;
                continue;
            }
            int index = (int) ((data[word] >>> ((i % perLong) * bits)) & mask);
            blocks[i] = index < classes.length ? classes[index] : BlockClass.PASS;
        }
        return blocks;
    }

    /**
     * A palette entry's block name: a compound with Name, or since 26.1 a plain string for a block in its default state,
     * which a compound without Name may also stand for.
     */
    private static String name(Object entry) {
        if (entry instanceof String name) {
            return name;
        }
        if (entry instanceof Map<?, ?> map) {
            Object name = map.get("Name");
            if (name == null) {
                name = map.get("name");
            }
            if (name == null && map.size() == 1) {
                name = map.values().iterator().next();
            }
            if (name instanceof String s) {
                return s;
            }
        }
        return "minecraft:air";
    }
}
