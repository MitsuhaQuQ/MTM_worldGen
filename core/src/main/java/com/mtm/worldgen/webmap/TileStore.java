package com.mtm.worldgen.webmap;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The tile pyramid of a world's overworld. A tile of level 0 is one region file; a tile of level k covers 2^k by 2^k
 * of them, merged from the four tiles of level k - 1 below it. Rasters and images are kept in the world's tile
 * database and stamped with the newest change of the region files under them, so a tile is drawn again only once the
 * game has saved something new there. Without a database (its driver could not load) tiles are only kept in memory.
 */
final class TileStore {
    static final int MAX_LEVEL = 8;
    /** How long a listing of the region folder is trusted. The game saves every few minutes anyway. */
    private static final long LISTING_MILLIS = 15_000L;
    private static final int MEMORY_RASTERS = 48;

    private final Path regions;
    /** Null where there is none: tiles are then drawn again after every restart. */
    private final TileDatabase database;
    private final Map<Long, CompletableFuture<Raster>> computing = new ConcurrentHashMap<>();
    private final Map<Long, Raster> memory = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Raster> eldest) {
            return size() > MEMORY_RASTERS;
        }
    };
    private volatile Map<Long, Long> listing = Map.of();
    private volatile long listedAt;

    TileStore(Path regions, TileDatabase database) {
        this.regions = regions;
        this.database = database;
    }

    /** Region files by their key, with the time each was last changed. */
    Map<Long, Long> regions() {
        long now = System.currentTimeMillis();
        if (now - listedAt > LISTING_MILLIS) {
            Map<Long, Long> found = new HashMap<>();
            if (Files.isDirectory(regions)) {
                try (Stream<Path> files = Files.list(regions)) {
                    files.forEach(file -> {
                        String[] parts = file.getFileName().toString().split("\\.");
                        if (parts.length == 4 && parts[0].equals("r") && parts[3].equals("mca")) {
                            try {
                                found.put(key(0, Integer.parseInt(parts[1]), Integer.parseInt(parts[2])), Files.getLastModifiedTime(file).toMillis());
                            } catch (NumberFormatException | IOException ignored) {
                                // Not a region file of the game's, or gone while listing
                            }
                        }
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            listing = found;
            listedAt = now;
        }
        return listing;
    }

    static long key(int level, int x, int z) {
        return ((long) level << 56) ^ (((long) x & 0xFFFFFFFL) << 28) ^ ((long) z & 0xFFFFFFFL);
    }

    /** Newest change of the region files under a tile, or 0 if there are none. */
    long stamp(int level, int tileX, int tileZ) {
        long newest = 0;
        int span = 1 << level;
        for (Map.Entry<Long, Long> region : regions().entrySet()) {
            long k = region.getKey();
            int x = signed28((int) (k >>> 28));
            int z = signed28((int) k);
            if (Math.floorDiv(x, span) == tileX && Math.floorDiv(z, span) == tileZ) {
                newest = Math.max(newest, region.getValue());
            }
        }
        return newest;
    }

    private static int signed28(int value) {
        return (value << 4) >> 4;
    }

    /** The PNG of a tile, or null where nothing has been generated. */
    byte[] image(int level, int tileX, int tileZ) throws IOException {
        if (level < 0 || level > MAX_LEVEL) {
            return null;
        }
        long stamp = stamp(level, tileX, tileZ);
        if (stamp == 0) {
            return null;
        }
        byte[] stored = load(TileDatabase.IMAGES, level, tileX, tileZ, stamp);
        if (stored != null) {
            return stored;
        }
        Raster raster = raster(level, tileX, tileZ);
        if (raster == null) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(TileRenderer.render(raster, level), "png", bytes);
        byte[] png = bytes.toByteArray();
        store(TileDatabase.IMAGES, level, tileX, tileZ, stamp, png);
        return png;
    }

    /** A tile's raster, from memory, the database or the tiles below; null where nothing has been generated. */
    Raster raster(int level, int tileX, int tileZ) throws IOException {
        long stamp = stamp(level, tileX, tileZ);
        if (stamp == 0) {
            return null;
        }
        long key = key(level, tileX, tileZ);
        // Tiles asked for at once, as a browser does, are computed once; the stamp keeps a newer save from being missed
        CompletableFuture<Raster> mine = new CompletableFuture<>();
        CompletableFuture<Raster> running = computing.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return running.join();
            } catch (RuntimeException e) {
                throw new IOException(e);
            }
        }
        try {
            Raster raster = computeRaster(level, tileX, tileZ, key, stamp);
            mine.complete(raster);
            return raster;
        } catch (IOException | RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            computing.remove(key);
        }
    }

    private Raster computeRaster(int level, int tileX, int tileZ, long key, long stamp) throws IOException {
        synchronized (memory) {
            Raster known = memory.get(key ^ stamp);
            if (known != null) {
                return known;
            }
        }
        Raster raster = null;
        byte[] stored = load(TileDatabase.RASTERS, level, tileX, tileZ, stamp);
        if (stored != null) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(stored))) {
                raster = Raster.read(in);
            } catch (IOException e) {
                // A damaged tile is only made again
            }
        }
        if (raster == null) {
            raster = level == 0 ? readRegion(tileX, tileZ) : mergeChildren(level, tileX, tileZ);
            if (raster == null) {
                return null;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                raster.write(gzip);
            }
            store(TileDatabase.RASTERS, level, tileX, tileZ, stamp, bytes.toByteArray());
        }
        synchronized (memory) {
            memory.put(key ^ stamp, raster);
        }
        return raster;
    }

    private Raster readRegion(int regionX, int regionZ) throws IOException {
        Path file = regions.resolve("r." + regionX + "." + regionZ + ".mca");
        return Files.exists(file) ? RegionScan.read(file, regionX, regionZ) : null;
    }

    private Raster mergeChildren(int level, int tileX, int tileZ) throws IOException {
        Raster merged = new Raster();
        boolean any = false;
        for (int dz = 0; dz < 2; dz++) {
            for (int dx = 0; dx < 2; dx++) {
                Raster child = raster(level - 1, 2 * tileX + dx, 2 * tileZ + dz);
                if (child != null) {
                    merged.merge(child, dx, dz);
                    any = true;
                }
            }
        }
        return any ? merged : null;
    }

    private byte[] load(String table, int level, int tileX, int tileZ, long stamp) throws IOException {
        if (database == null) {
            return null;
        }
        try {
            return database.read(table, level, tileX, tileZ, stamp);
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    private void store(String table, int level, int tileX, int tileZ, long stamp, byte[] data) throws IOException {
        if (database != null) {
            try {
                database.write(table, level, tileX, tileZ, stamp, data);
            } catch (SQLException e) {
                throw new IOException(e);
            }
        }
    }

    /** Bounds of the region files, in blocks: min x, min z, max x, max z, or null if there are none. */
    int[] bounds() {
        Map<Long, Long> all = regions();
        if (all.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long k : all.keySet()) {
            int x = signed28((int) (k >>> 28));
            int z = signed28((int) k);
            minX = Math.min(minX, x);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxZ = Math.max(maxZ, z);
        }
        return new int[]{minX * Raster.SIZE, minZ * Raster.SIZE, (maxX + 1) * Raster.SIZE, (maxZ + 1) * Raster.SIZE};
    }
}
