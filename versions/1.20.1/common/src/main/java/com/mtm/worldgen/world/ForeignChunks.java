package com.mtm.worldgen.world;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.logic.ForeignMap;
import com.mtm.worldgen.logic.LogicThreads;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Finds chunks MTM did not generate when a world starts, such as region files merged in from another world, and fits
 * them in: superflat maps are raised, what lies below a map is generated, and their edges go into the snapshot that
 * new terrain blends toward. Changed region files are backed up first. A chunk is only ever handled once.
 */
public final class ForeignChunks {
    /** Chunk tag with the algorithm version of the MTM that generated the chunk. */
    public static final String VERSION_TAG = "mtm_version";
    /** Version a chunk from an older MTM is saved with again, when what it was is no longer known: any older one. */
    public static final int OLDER_VERSION = 0;
    /**
     * Chunk tag with the {@link TerrainLogic#worldId} of the world that generated the chunk. These tags travel with
     * region files, so a version alone would take the chunks of another MTM world for this one's.
     */
    public static final String WORLD_TAG = "mtm_world";
    /** Chunk tag with the id of the world that has fitted this foreign chunk, which holds for as long as it stays. */
    public static final String FITTED_TAG = "mtm_fitted";

    private static final int SECTION_BLOCKS = 16;
    private static final int LOWEST_SECTION = -4;
    /** A map counts as superflat when this share of its ground lies at one height, well below the sea. */
    private static final double FLAT_SHARE = 0.5;
    private static final int FLAT_BELOW_SEA = 8;
    private static final int REPORTS = 20;
    private static final int SECTOR = 4096;
    private static final int REGION_CHUNKS = 1024;
    /** Threads that use the disk at a time: on a spinning disk, more of them only make each other seek. */
    private static final Semaphore DISK = new Semaphore(2);
    /** Region files that share a stretch of terrain logic, along each axis. */
    private static final int REGIONS_PER_BLOCK = 8;
    private static final int HEADER_SECTORS = 2;
    private static final int MAX_SECTORS = 255;
    private static final int PACKED_SIZE = 16 * 1024;
    /** Open water at one height over this share of a map counts as its sea. */
    private static final double MIN_SEA_SHARE = 0.05;
    /** How far a sea is moved to meet the world's own: half a section, which is as far as lifting can leave it. */
    private static final int MAX_SEA_ALIGNMENT = 8;
    private static final int STONE_SECTIONS_ABOVE_LAND = 2;
    private static final Set<String> NOT_GROUND_NAMES = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:water", "minecraft:lava", "minecraft:grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
            "minecraft:snow", "minecraft:vine", "minecraft:seagrass", "minecraft:tall_seagrass", "minecraft:kelp", "minecraft:kelp_plant");

    private final ServerLevel level;
    private final TerrainLogic logic;
    private final WorldgenSettings settings;
    private final Path root;
    private final Path snapshotFile;
    private final Path regions;
    private final ChunkGenerator generator;
    private final RandomState randomState;
    /**
     * Fills chunks without structures. The real manager reads chunks through the server thread, which is waiting for
     * this work to finish, so asking it would hang the world; and what lies under a map has no use for their foundations.
     */
    private final StructureManager noStructures;

    private ForeignChunks(ServerLevel level, TerrainLogic logic, Path root, ChunkGenerator generator, RandomState randomState) {
        this.generator = generator;
        this.randomState = randomState;
        this.noStructures = new StructureManager(level, level.getServer().getWorldData().worldGenOptions(), null) {
            @Override
            public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> predicate) {
                return List.of();
            }
        };
        this.level = level;
        this.logic = logic;
        this.settings = logic.settings();
        this.root = root;
        this.snapshotFile = root.resolve(MtmWorldgen.MOD_ID).resolve("legacy.dat");
        this.regions = root.resolve("region");
    }

    /** Brings the snapshot of an MTM overworld up to date, before it generates anything. */
    public static void prepare(ServerLevel level) {
        TerrainLogic logic = MtmGenerators.terrainLogic(level);
        if (logic == null || level.dimension() != Level.OVERWORLD) {
            return;
        }
        try {
            new ForeignChunks(level, logic, level.getServer().getWorldPath(LevelResource.ROOT),
                    level.getChunkSource().getGenerator(), level.getChunkSource().randomState()).run();
        } catch (IOException | RuntimeException e) {
            // A world must still start, and a stale snapshot only costs a seam
            MtmWorldgen.LOGGER.error("Could not process foreign chunks", e);
        }
    }

    /** Runs on the world folder at a path, with the given terrain. For tests. */
    public static void run(ServerLevel level, TerrainLogic logic, Path root, ChunkGenerator generator, RandomState randomState) throws IOException {
        new ForeignChunks(level, logic, root, generator, randomState).run();
    }

    private void run() throws IOException {
        Map<Long, ForeignMap.Chunk> chunks = new HashMap<>();
        Map<String, long[]> files = new TreeMap<>();
        readSnapshot(chunks, files);
        List<Path> changed = changedFiles(files);
        boolean filesGone = forgetMissingFiles(chunks, files);
        if (!changed.isEmpty() || filesGone) {
            fit(changed, chunks);
            for (Path file : changed) {
                files.put(file.getFileName().toString(), stamp(file));
            }
            readExposedEdges(chunks);
            dropInnerDetail(chunks);
            writeSnapshot(chunks, files);
        }
        logic.setForeign(new ForeignMap(chunks, settings));
    }

    /** Region files that have been removed take their chunks out of the snapshot, which follows what the save holds. */
    private boolean forgetMissingFiles(Map<Long, ForeignMap.Chunk> chunks, Map<String, long[]> files) {
        List<String> gone = new ArrayList<>();
        for (String name : files.keySet()) {
            if (!Files.exists(regions.resolve(name))) {
                gone.add(name);
            }
        }
        for (String name : gone) {
            int[] region = regionOf(regions.resolve(name));
            chunks.keySet().removeIf(key -> Math.floorDiv((int) (key >> 32), 32) == region[0] && Math.floorDiv((int) (long) key, 32) == region[1]);
            files.remove(name);
        }
        return !gone.isEmpty();
    }

    /**
     * Chunks that had foreign chunks on all sides keep no detail, so where those neighbors have gone their edge has to
     * be read again. Without it the land coming in there would have nothing to close in on.
     */
    private void readExposedEdges(Map<Long, ForeignMap.Chunk> chunks) {
        Map<Path, Set<Long>> exposed = new TreeMap<>();
        for (Map.Entry<Long, ForeignMap.Chunk> entry : chunks.entrySet()) {
            int x = (int) (entry.getKey() >> 32);
            int z = (int) (long) entry.getKey();
            if (ForeignMap.isInner(entry.getValue()) && !isInner(chunks, x, z)) {
                Path file = regions.resolve("r." + Math.floorDiv(x, 32) + "." + Math.floorDiv(z, 32) + ".mca");
                exposed.computeIfAbsent(file, f -> new HashSet<>()).add(entry.getKey());
            }
        }
        List<Map.Entry<Path, Set<Long>>> work = new ArrayList<>(exposed.entrySet());
        Map<Long, ForeignMap.Chunk> read = new ConcurrentHashMap<>();
        LogicThreads.forEach(work.size(), i -> {
            Set<Long> wanted = work.get(i).getValue();
            try {
                eachChunk(work.get(i).getKey(), pos -> wanted.contains(ForeignMap.key(pos.x, pos.z)), (pos, tag) -> {
                    Found found = new Found(pos, tag, work.get(i).getKey());
                    read(found);
                    read.put(ForeignMap.key(pos.x, pos.z), snapshot(found, false));
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        chunks.putAll(read);
        // Anything that could not be read is no use as an edge
        exposed.values().forEach(keys -> keys.forEach(key -> {
            chunks.remove(key, ForeignMap.INNER);
            chunks.remove(key, ForeignMap.UPGRADED_INNER);
        }));
    }

    private static boolean isInner(Map<Long, ForeignMap.Chunk> chunks, int x, int z) {
        return chunks.containsKey(ForeignMap.key(x - 1, z)) && chunks.containsKey(ForeignMap.key(x + 1, z))
                && chunks.containsKey(ForeignMap.key(x, z - 1)) && chunks.containsKey(ForeignMap.key(x, z + 1));
    }

    private List<Path> changedFiles(Map<String, long[]> known) throws IOException {
        if (!Files.isDirectory(regions)) {
            return List.of();
        }
        List<Path> changed = new ArrayList<>();
        try (Stream<Path> list = Files.list(regions)) {
            for (Path file : (Iterable<Path>) list::iterator) {
                if (file.getFileName().toString().endsWith(".mca") && Files.size(file) > 0
                        && !Arrays.equals(known.get(file.getFileName().toString()), stamp(file))) {
                    changed.add(file);
                }
            }
        }
        return changed;
    }

    private static long[] stamp(Path file) throws IOException {
        return new long[]{Files.getLastModifiedTime(file).toMillis(), Files.size(file)};
    }

    /** A chunk on its way through: its tag and what was read from it. */
    private static final class Found {
        final ChunkPos pos;
        /** Only there while a chunk is being worked on. */
        CompoundTag tag;
        final Path file;
        int lift;
        int sea;
        final int[] ground = new int[ForeignMap.SAMPLES];
        final short[] water = new short[ForeignMap.SAMPLES];
        String biome;
        int lowestSection = Integer.MAX_VALUE;

        Found(ChunkPos pos, CompoundTag tag, Path file) {
            this.pos = pos;
            this.tag = tag;
            this.file = file;
        }
    }

    /**
     * Works in two passes over the region files, which run in parallel file by file. A big map does not fit in memory
     * as chunk tags, so the first pass only keeps what is read from each chunk, and the second reads each one again
     * and writes it back right away.
     */
    private void fit(List<Path> files, Map<Long, ForeignMap.Chunk> chunks) {
        Map<Long, Found> foreign = new ConcurrentHashMap<>();
        Map<Long, ForeignMap.Chunk> plain = new ConcurrentHashMap<>();
        Set<Long> present = ConcurrentHashMap.newKeySet();
        AtomicInteger scanned = new AtomicInteger();
        LogicThreads.forEach(files.size(), i -> {
            scanFile(files.get(i), chunks, foreign, plain, present);
            report("Scanned", scanned.incrementAndGet(), files.size());
        });
        // The snapshot follows the save: land around a chunk that was cleared blends toward what is left. Taken for
        // still being there, a cleared chunk would count as the inside of a map and come back as a flat plain
        Set<String> scannedRegions = new HashSet<>();
        files.forEach(file -> scannedRegions.add(file.getFileName().toString()));
        chunks.keySet().removeIf(key -> {
            int x = (int) (key >> 32);
            int z = (int) (long) key;
            return !present.contains(key) && scannedRegions.contains("r." + Math.floorDiv(x, 32) + "." + Math.floorDiv(z, 32) + ".mca");
        });
        chunks.putAll(plain);

        for (List<Found> island : islands(foreign)) {
            int lift = lift(island);
            int sea = seaOf(island, lift);
            for (Found found : island) {
                found.lift = lift;
                found.sea = sea;
            }
            if (lift != 0) {
                MtmWorldgen.LOGGER.info("Raising {} foreign chunks by {}", island.size(), lift);
            }
        }
        MtmWorldgen.LOGGER.info("Fitting {} foreign chunks", foreign.size());
        // Neighboring files follow each other, or threads scattered over the map would keep evicting the terrain logic
        Map<Path, List<Found>> byFile = new TreeMap<>(Comparator
                .<Path>comparingInt(file -> Math.floorDiv(regionOf(file)[0], REGIONS_PER_BLOCK))
                .thenComparingInt(file -> Math.floorDiv(regionOf(file)[1], REGIONS_PER_BLOCK))
                .thenComparing(Comparator.naturalOrder()));
        foreign.values().forEach(found -> byFile.computeIfAbsent(found.file, file -> new ArrayList<>()).add(found));
        List<Map.Entry<Path, List<Found>>> work = new ArrayList<>(byFile.entrySet());
        AtomicInteger fitted = new AtomicInteger();
        LogicThreads.forEach(work.size(), i -> {
            fitFile(work.get(i).getKey(), work.get(i).getValue());
            report("Fitted", fitted.incrementAndGet(), work.size());
        });
        foreign.forEach((key, found) -> chunks.put(key, snapshot(found, false)));
    }

    /** Logs every so often: this can take minutes on a big map, while the game sits at the start of loading. */
    private static void report(String what, int done, int total) {
        if (done == total || done % Math.max(1, total / REPORTS) == 0) {
            MtmWorldgen.LOGGER.info("{} {} of {} region files", what, done, total);
        }
    }

    private static int[] regionOf(Path file) {
        String[] name = file.getFileName().toString().split("\\.");
        return new int[]{Integer.parseInt(name[1]), Integer.parseInt(name[2])};
    }

    private ChunkStorage openStorage() {
        return VersionApi.chunkStorage(level, regions);
    }

    private void scanFile(Path file, Map<Long, ForeignMap.Chunk> chunks, Map<Long, Found> foreign, Map<Long, ForeignMap.Chunk> plain, Set<Long> present) {
        try (ChunkStorage storage = openStorage()) {
            eachChunk(file, pos -> true, (pos, stored) -> {
                long key = ForeignMap.key(pos.x, pos.z);
                present.add(key);
                Origin origin = origin(stored);
                // The snapshot only says what stood here: a new map at the same place is told by its tags
                if (origin == Origin.OWN || origin != Origin.FOREIGN && chunks.containsKey(key)) {
                    return;
                }
                CompoundTag tag = storage.upgradeChunkTag(Level.OVERWORLD, level::getDataStorage, stored, Optional.empty());
                if (!tag.getString("Status").endsWith("full")) {
                    return;
                }
                Found found = new Found(pos, tag, file);
                read(found);
                found.tag = null;
                if (origin == Origin.FOREIGN) {
                    foreign.put(key, found);
                } else {
                    plain.put(key, snapshot(found, origin == Origin.UPGRADED));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Fits the chunks of a file and writes the file anew in one go. Going through the storage would write chunk by
     * chunk, all over the file, from many threads at once; a finished file only takes the disk once, front to back.
     * The original file becomes the backup, which costs nothing.
     */
    private void fitFile(Path file, List<Found> chunks) {
        Map<Long, Found> wanted = new HashMap<>();
        chunks.forEach(found -> wanted.put(found.pos.toLong(), found));
        try (ChunkStorage storage = openStorage()) {
            byte[] bytes = readFile(file);
            ByteBuffer old = ByteBuffer.wrap(bytes);
            int[] region = regionOf(file);
            ByteArrayOutputStream body = new ByteArrayOutputStream(bytes.length);
            ByteBuffer locations = ByteBuffer.allocate(SECTOR);
            List<Found> oversized = new ArrayList<>();
            for (int i = 0; i < REGION_CHUNKS && 4 * i + 4 <= bytes.length; i++) {
                int entry = old.getInt(4 * i);
                int offset = (entry >>> 8) * SECTOR;
                if (entry == 0 || offset + 5 > bytes.length) {
                    continue;
                }
                ChunkPos pos = new ChunkPos(region[0] * 32 + (i & 31), region[1] * 32 + (i >> 5));
                Found found = wanted.get(pos.toLong());
                byte[] chunk;
                if (found == null) {
                    chunk = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 4 + old.getInt(offset)));
                } else {
                    CompoundTag stored = bytes[offset + 4] < 0 ? storage.read(pos).join().orElseThrow() : read(bytes, offset, pos);
                    found.tag = storage.upgradeChunkTag(Level.OVERWORLD, level::getDataStorage, stored, Optional.empty());
                    rebuild(found, found.lift);
                    alignSea(found, found.sea);
                    chunk = pack(found.tag);
                    if (chunk.length > MAX_SECTORS * SECTOR) {
                        oversized.add(found);
                        continue;
                    }
                    found.tag = null;
                }
                int sectors = (chunk.length + SECTOR - 1) / SECTOR;
                locations.putInt(4 * i, (HEADER_SECTORS + body.size() / SECTOR) << 8 | sectors);
                body.write(chunk);
                body.write(new byte[sectors * SECTOR - chunk.length]);
            }
            replaceFile(file, locations.array(), Arrays.copyOfRange(bytes, SECTOR, 2 * SECTOR), body);
            // Chunks too big for a region file go to files of their own, which the storage takes care of
            for (Found found : oversized) {
                VersionApi.store(storage, found.pos, found.tag);
                found.tag = null;
            }
            storage.flushWorker();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CompoundTag read(byte[] bytes, int offset, ChunkPos pos) throws IOException {
        int length = ByteBuffer.wrap(bytes).getInt(offset);
        int kind = bytes[offset + 4];
        if (kind < 0 || !RegionFileVersion.isValidVersion(kind)) {
            throw new IOException("Cannot read chunk " + pos + " of kind " + kind);
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(RegionFileVersion.fromId(kind)
                .wrap(new ByteArrayInputStream(bytes, offset + 5, length - 1))))) {
            return NbtIo.read(input);
        }
    }

    /** A chunk as it lies in a region file: its length, how it is packed, and the packed tag. */
    private static byte[] pack(CompoundTag tag) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream(PACKED_SIZE);
        packed.write(new byte[5]);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(RegionFileVersion.VERSION_DEFLATE.wrap(packed)))) {
            NbtIo.write(tag, output);
        }
        byte[] bytes = packed.toByteArray();
        ByteBuffer.wrap(bytes).putInt(0, bytes.length - 4).put(4, (byte) RegionFileVersion.VERSION_DEFLATE.getId());
        return bytes;
    }

    private void replaceFile(Path file, byte[] locations, byte[] times, ByteArrayOutputStream body) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Path backup = backupOf(file);
        DISK.acquireUninterruptibly();
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temporary), SECTOR * 16)) {
            output.write(locations);
            output.write(times);
            body.writeTo(output);
        } finally {
            DISK.release();
        }
        if (Files.exists(backup)) {
            Files.delete(file);
        } else {
            Files.move(file, backup);
        }
        Files.move(temporary, file);
    }

    private Path backupOf(Path file) throws IOException {
        Path folder = root.resolve(MtmWorldgen.MOD_ID).resolve("backup");
        Files.createDirectories(folder);
        return folder.resolve(file.getFileName() + "." + Files.getLastModifiedTime(file).toMillis());
    }

    private interface ChunkVisitor {
        void visit(ChunkPos pos, CompoundTag tag) throws IOException;
    }

    /**
     * Reads a region file whole and goes through its chunks. Threads reading chunk by chunk spend nearly all their time
     * waiting for each other at the disk, so the file is read in one go, and only unpacking runs on many threads.
     */
    private void eachChunk(Path file, Predicate<ChunkPos> wanted, ChunkVisitor visitor) throws IOException {
        byte[] bytes = readFile(file);
        int[] region = regionOf(file);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (int i = 0; i < REGION_CHUNKS && 4 * i + 4 <= bytes.length; i++) {
            int entry = buffer.getInt(4 * i);
            int offset = (entry >>> 8) * SECTOR;
            ChunkPos pos = new ChunkPos(region[0] * 32 + (i & 31), region[1] * 32 + (i >> 5));
            if (entry == 0 || offset + 5 > bytes.length || !wanted.test(pos)) {
                continue;
            }
            if (bytes[offset + 4] < 0) {
                // Oversized chunks live in files of their own, which the storage knows how to find
                try (ChunkStorage storage = openStorage()) {
                    Optional<CompoundTag> stored = storage.read(pos).join();
                    if (stored.isPresent()) {
                        visitor.visit(pos, stored.get());
                    }
                }
                continue;
            }
            visitor.visit(pos, read(bytes, offset, pos));
        }
    }

    private static byte[] readFile(Path file) throws IOException {
        DISK.acquireUninterruptibly();
        try {
            return Files.readAllBytes(file);
        } finally {
            DISK.release();
        }
    }

    /** Where a stored chunk comes from. */
    private enum Origin {
        /** Generated by this world. */
        OWN,
        /** Generated by an older version of this world's terrain: it blends, but needs no fitting. */
        UPGRADED,
        /** Already fitted into this world. */
        FITTED,
        /** From anywhere else, including other MTM worlds. */
        FOREIGN
    }

    private Origin origin(CompoundTag tag) {
        long world = logic.worldId();
        if (tag.contains(FITTED_TAG) && tag.getLong(FITTED_TAG) == world) {
            return Origin.FITTED;
        }
        if (!tag.contains(VERSION_TAG)) {
            return Origin.FOREIGN;
        }
        if (tag.contains(WORLD_TAG) && tag.getLong(WORLD_TAG) != world) {
            return Origin.FOREIGN;
        }
        return tag.contains(WORLD_TAG) && tag.getInt(VERSION_TAG) == TerrainLogic.ALGORITHM_VERSION ? Origin.OWN : Origin.UPGRADED;
    }

    /** Foreign chunks in groups connected by their sides. A map moves as a whole, or it would come apart in steps. */
    private static List<List<Found>> islands(Map<Long, Found> foreign) {
        List<List<Found>> islands = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<Long> keys = new ArrayList<>(foreign.keySet());
        keys.sort(null);
        for (long start : keys) {
            if (!seen.add(start)) {
                continue;
            }
            List<Found> island = new ArrayList<>();
            ArrayDeque<Long> open = new ArrayDeque<>(List.of(start));
            while (!open.isEmpty()) {
                Found found = foreign.get(open.poll());
                island.add(found);
                int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] side : sides) {
                    long next = ForeignMap.key(found.pos.x + side[0], found.pos.z + side[1]);
                    if (foreign.containsKey(next) && seen.add(next)) {
                        open.add(next);
                    }
                }
            }
            islands.add(island);
        }
        return islands;
    }

    /** Blocks a map has to rise by: whole sections, up to where superflat ground lands on its target height. */
    private int lift(List<Found> island) {
        Map<Integer, Integer> counts = new HashMap<>();
        int total = 0;
        for (Found found : island) {
            for (int i = 0; i < found.ground.length; i++) {
                total++;
                // Dry ground only: a flat sea floor lies low as well, but it is no superflat map
                if (found.water[i] == ForeignMap.NO_WATER) {
                    counts.merge(found.ground[i], 1, Integer::sum);
                }
            }
        }
        int mode = 0;
        int best = 0;
        for (Map.Entry<Integer, Integer> entry : new TreeMap<>(counts).entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                mode = entry.getKey();
            }
        }
        boolean flat = best >= FLAT_SHARE * total && mode < settings.general.seaLevel - FLAT_BELOW_SEA;
        return flat ? Math.round((settings.foreign.flatGround - mode) / (float) SECTION_BLOCKS) * SECTION_BLOCKS : 0;
    }

    /**
     * Height a map's sea ends up at, or {@link ForeignMap#NO_WATER} if it has none to speak of. Maps only move in
     * whole sections, so a raised sea rarely lands on the world's own.
     */
    private static int seaOf(List<Found> island, int lift) {
        Map<Integer, Integer> counts = new TreeMap<>();
        int total = 0;
        for (Found found : island) {
            for (short water : found.water) {
                total++;
                if (water != ForeignMap.NO_WATER) {
                    counts.merge(water + lift, 1, Integer::sum);
                }
            }
        }
        int sea = ForeignMap.NO_WATER;
        int best = (int) (MIN_SEA_SHARE * total);
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                sea = entry.getKey();
            }
        }
        return sea;
    }

    /**
     * Brings a sea close to the world's own level onto it, so the two meet without a step. A sea further off stays:
     * moving it would swallow shores or leave them high and dry, and the land around it holds the two apart.
     */
    private void alignSea(Found found, int sea) {
        int target = settings.general.seaLevel - 1;
        if (sea == ForeignMap.NO_WATER || sea == target || Math.abs(sea - target) > MAX_SEA_ALIGNMENT) {
            return;
        }
        Map<Integer, SectionBlocks> sections = new HashMap<>();
        ListTag list = found.tag.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag section = list.getCompound(i);
            sections.put((int) section.getByte("Y"), new SectionBlocks(section));
        }
        for (int x = 0; x < SECTION_BLOCKS; x++) {
            for (int z = 0; z < SECTION_BLOCKS; z++) {
                if (isOpenSea(sections, x, sea, z)) {
                    for (int y = Math.min(sea, target) + 1; y <= Math.max(sea, target); y++) {
                        setBlock(sections, x, y, z, target < sea ? "minecraft:air" : "minecraft:water", target > sea);
                    }
                }
            }
        }
        sections.values().forEach(SectionBlocks::write);
        for (int i = 0; i < found.water.length; i++) {
            found.water[i] = found.water[i] == sea ? (short) target : found.water[i];
        }
    }

    private static boolean isOpenSea(Map<Integer, SectionBlocks> sections, int x, int y, int z) {
        return "minecraft:water".equals(blockAt(sections, x, y, z)) && !"minecraft:water".equals(blockAt(sections, x, y + 1, z));
    }

    private static String blockAt(Map<Integer, SectionBlocks> sections, int x, int y, int z) {
        SectionBlocks section = sections.get(Math.floorDiv(y, SECTION_BLOCKS));
        return section == null ? "minecraft:air" : section.get(x, Math.floorMod(y, SECTION_BLOCKS), z);
    }

    private static void setBlock(Map<Integer, SectionBlocks> sections, int x, int y, int z, String name, boolean onlyAir) {
        SectionBlocks section = sections.get(Math.floorDiv(y, SECTION_BLOCKS));
        if (section != null && (!onlyAir || section.get(x, Math.floorMod(y, SECTION_BLOCKS), z).endsWith("air"))) {
            section.set(x, Math.floorMod(y, SECTION_BLOCKS), z, name);
        }
    }

    /** The blocks of a section tag, unpacked for editing. */
    private static final class SectionBlocks {
        private static final int SIZE = 4096;
        private final CompoundTag states;
        private final List<CompoundTag> palette = new ArrayList<>();
        private final int[] blocks = new int[SIZE];
        private boolean changed;

        SectionBlocks(CompoundTag section) {
            this.states = section.getCompound("block_states");
            ListTag entries = states.getList("palette", Tag.TAG_COMPOUND);
            for (int i = 0; i < entries.size(); i++) {
                palette.add(entries.getCompound(i));
            }
            if (palette.size() > 1) {
                new SimpleBitStorage(bits(palette.size()), SIZE, states.getLongArray("data")).unpack(blocks);
            }
        }

        private static int bits(int entries) {
            return Math.max(4, Mth.ceillog2(entries));
        }

        String get(int x, int y, int z) {
            return palette.isEmpty() ? "minecraft:air" : palette.get(blocks[y << 8 | z << 4 | x]).getString("Name");
        }

        void set(int x, int y, int z, String name) {
            int entry = -1;
            for (int i = 0; i < palette.size() && entry < 0; i++) {
                entry = palette.get(i).getString("Name").equals(name) && !palette.get(i).contains("Properties") ? i : -1;
            }
            if (entry < 0) {
                CompoundTag added = new CompoundTag();
                added.putString("Name", name);
                palette.add(added);
                entry = palette.size() - 1;
            }
            blocks[y << 8 | z << 4 | x] = entry;
            changed = true;
        }

        void write() {
            if (!changed) {
                return;
            }
            ListTag entries = new ListTag();
            entries.addAll(palette);
            states.put("palette", entries);
            SimpleBitStorage storage = new SimpleBitStorage(bits(palette.size()), SIZE);
            for (int i = 0; i < SIZE; i++) {
                storage.set(i, blocks[i]);
            }
            states.putLongArray("data", storage.getRaw());
        }
    }

    private void read(Found found) {
        Map<Integer, CompoundTag> sections = new TreeMap<>();
        ListTag list = found.tag.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag section = list.getCompound(i);
            if (section.contains("block_states") && !isEmpty(section)) {
                sections.put((int) section.getByte("Y"), section);
                found.lowestSection = Math.min(found.lowestSection, section.getByte("Y"));
            }
        }
        List<Integer> descending = new ArrayList<>(sections.keySet());
        descending.sort((a, b) -> b - a);
        int[] offsets = ForeignMap.SAMPLE_OFFSETS;
        for (int i = 0; i < offsets.length; i++) {
            for (int j = 0; j < offsets.length; j++) {
                int index = j * offsets.length + i;
                found.ground[index] = 0;
                found.water[index] = ForeignMap.NO_WATER;
                scanColumn(found, sections, descending, offsets[i], offsets[j], index);
            }
        }
    }

    private void scanColumn(Found found, Map<Integer, CompoundTag> sections, List<Integer> descending, int x, int z, int index) {
        for (int y : descending) {
            CompoundTag section = sections.get(y);
            ListTag palette = section.getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
            long[] data = section.getCompound("block_states").getLongArray("data");
            SimpleBitStorage storage = palette.size() > 1 ? new SimpleBitStorage(Math.max(4, Mth.ceillog2(palette.size())), 4096, data) : null;
            for (int local = SECTION_BLOCKS - 1; local >= 0; local--) {
                int entry = storage == null ? 0 : storage.get(local << 8 | z << 4 | x);
                String name = palette.getCompound(entry).getString("Name");
                int height = y * SECTION_BLOCKS + local;
                if (name.equals("minecraft:water") && found.water[index] == ForeignMap.NO_WATER) {
                    found.water[index] = (short) height;
                }
                if (isGround(name)) {
                    found.ground[index] = height;
                    if (found.biome == null) {
                        ListTag biomes = section.getCompound("biomes").getList("palette", Tag.TAG_STRING);
                        found.biome = biomes.isEmpty() ? null : biomes.getString(0);
                    }
                    return;
                }
            }
        }
    }

    private static boolean isGround(String name) {
        return !NOT_GROUND_NAMES.contains(name) && !name.endsWith("_leaves") && !name.endsWith("_log") && !name.endsWith("flower");
    }

    private static boolean isEmpty(CompoundTag section) {
        ListTag palette = section.getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
        return palette.size() == 1 && palette.getCompound(0).getString("Name").endsWith("air");
    }

    /** Moves a chunk up, drops its old floor, generates the world below it, and leaves light to be worked out again. */
    private void rebuild(Found found, int lift) {
        CompoundTag tag = found.tag;
        ListTag old = tag.getList("sections", Tag.TAG_COMPOUND);
        ListTag sections = new ListTag();
        int liftSections = lift / SECTION_BLOCKS;
        int top = settings.buildHeight() / SECTION_BLOCKS + LOWEST_SECTION;
        Set<Integer> taken = new HashSet<>();
        for (int i = 0; i < old.size(); i++) {
            CompoundTag section = old.getCompound(i);
            int y = section.getByte("Y") + liftSections;
            if (y >= LOWEST_SECTION && y < top && section.contains("block_states")) {
                section.putByte("Y", (byte) y);
                section.remove("BlockLight");
                section.remove("SkyLight");
                replaceBlock(section, "minecraft:bedrock", "minecraft:stone");
                sections.add(section);
                taken.add(y);
            }
        }
        // Worlds that reached deeper lose what lies below this one, so only what stays counts as the floor of the map
        int floor = found.lowestSection + liftSections;
        floor = found.lowestSection == Integer.MAX_VALUE ? 0 : Math.max(floor, lowestKept(sections));
        if (floor > LOWEST_SECTION) {
            fillBelow(found, sections, taken, floor);
        }
        tag.put("sections", sections);
        tag.putInt("yPos", LOWEST_SECTION);
        tag.putBoolean("isLightOn", false);
        for (String moved : List.of("block_entities", "block_ticks", "fluid_ticks")) {
            ListTag entries = tag.getList(moved, Tag.TAG_COMPOUND);
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entry = entries.getCompound(i);
                entry.putInt("y", entry.getInt("y") + lift);
            }
        }
        // Vanilla's own blending and retrogen would work against the blend around these chunks, and the height maps
        // have another length in a taller world, so they are left for the game to compute
        for (String dropped : List.of("Heightmaps", "blending_data", "below_zero_retrogen", "PostProcessing", "UpgradeData", VERSION_TAG, WORLD_TAG)) {
            tag.remove(dropped);
        }
        tag.putLong(FITTED_TAG, logic.worldId());
        for (int i = 0; i < found.ground.length; i++) {
            found.ground[i] += lift;
            found.water[i] = found.water[i] == ForeignMap.NO_WATER ? ForeignMap.NO_WATER : (short) (found.water[i] + lift);
        }
    }

    private static int lowestKept(ListTag sections) {
        int lowest = 0;
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            if (!isEmpty(section)) {
                lowest = Math.min(lowest, section.getByte("Y"));
            }
        }
        return lowest;
    }

    /**
     * Sections below a map, from a chunk of the world's own terrain. MTM land may lie lower than the map's floor there,
     * so the sections above that land are made solid: only rock should meet the bottom of the map.
     */
    private void fillBelow(Found found, ListTag sections, Set<Integer> taken, int floor) {
        // A short chunk ends the noise at the map, which saves most of the work of a full column
        LevelHeightAccessor below = LevelHeightAccessor.create(LOWEST_SECTION * SECTION_BLOCKS, (floor - LOWEST_SECTION) * SECTION_BLOCKS);
        ProtoChunk proto = new ProtoChunk(found.pos, UpgradeData.EMPTY, below,
                VersionApi.registry(level.registryAccess(), Registries.BIOME), null);
        VersionApi.fillFromNoise(generator, Blender.empty(), randomState, noStructures, proto).join();
        int center = found.pos.getMinBlockX() + 8;
        int centerZ = found.pos.getMinBlockZ() + 8;
        int land = Mth.floor(logic.sampleSmoothLand(center, centerZ) / SECTION_BLOCKS) - STONE_SECTIONS_ABOVE_LAND;
        ListTag generated = VersionApi.writeChunk(level, proto).getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < generated.size(); i++) {
            CompoundTag section = generated.getCompound(i);
            int y = section.getByte("Y");
            if (y >= LOWEST_SECTION && y < floor && !taken.contains(y) && section.contains("block_states")) {
                section.remove("BlockLight");
                section.remove("SkyLight");
                if (y >= land) {
                    replaceBlock(section, "minecraft:air", "minecraft:stone");
                    replaceBlock(section, "minecraft:cave_air", "minecraft:stone");
                    replaceBlock(section, "minecraft:water", "minecraft:stone");
                }
                sections.add(section);
            }
        }
    }

    private static void replaceBlock(CompoundTag section, String from, String to) {
        ListTag palette = section.getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            if (entry.getString("Name").equals(from)) {
                entry.putString("Name", to);
                entry.remove("Properties");
            }
        }
    }

    private static ForeignMap.Chunk snapshot(Found found, boolean upgraded) {
        short[] ground = new short[found.ground.length];
        for (int i = 0; i < ground.length; i++) {
            ground[i] = (short) found.ground[i];
        }
        return new ForeignMap.Chunk(ground, found.water, found.biome, upgraded);
    }

    private void readSnapshot(Map<Long, ForeignMap.Chunk> chunks, Map<String, long[]> files) throws IOException {
        if (!Files.exists(snapshotFile)) {
            return;
        }
        CompoundTag tag = NbtFiles.read(snapshotFile);
        // Chunks this world made before an upgrade still carry the older version, but their files have not changed
        // since they were last looked at: all of them have to be read again to find those chunks
        boolean sameAlgorithm = tag.getInt("algorithm") == TerrainLogic.ALGORITHM_VERSION;
        if (!sameAlgorithm) {
            MtmWorldgen.LOGGER.info("Terrain algorithm is now version {}: reading all region files again for chunks of older versions",
                    TerrainLogic.ALGORITHM_VERSION);
        }
        ListTag list = tag.getList("chunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag chunk = list.getCompound(i);
            chunks.put(chunk.getLong("key"), new ForeignMap.Chunk(shorts(chunk.getIntArray("ground")), shorts(chunk.getIntArray("water")),
                    chunk.contains("biome") ? chunk.getString("biome") : null, chunk.getBoolean("upgraded")));
        }
        for (long key : tag.getLongArray("inner")) {
            chunks.put(key, ForeignMap.INNER);
        }
        for (long key : tag.getLongArray("upgraded_inner")) {
            chunks.put(key, ForeignMap.UPGRADED_INNER);
        }
        CompoundTag known = tag.getCompound("files");
        for (String name : sameAlgorithm ? known.getAllKeys() : Set.<String>of()) {
            files.put(name, known.getLongArray(name));
        }
    }

    /** Chunks with foreign chunks on all sides only have to be known: nothing blends toward them. */
    private static void dropInnerDetail(Map<Long, ForeignMap.Chunk> chunks) {
        List<Long> inner = new ArrayList<>();
        for (long key : chunks.keySet()) {
            int x = (int) (key >> 32);
            int z = (int) key;
            if (isInner(chunks, x, z)) {
                inner.add(key);
            }
        }
        inner.forEach(key -> chunks.put(key, ForeignMap.inner(chunks.get(key))));
    }

    private void writeSnapshot(Map<Long, ForeignMap.Chunk> chunks, Map<String, long[]> files) throws IOException {
        ListTag list = new ListTag();
        List<Long> inner = new ArrayList<>();
        List<Long> upgradedInner = new ArrayList<>();
        for (Map.Entry<Long, ForeignMap.Chunk> entry : new TreeMap<>(chunks).entrySet()) {
            if (ForeignMap.isInner(entry.getValue())) {
                (entry.getValue().upgraded() ? upgradedInner : inner).add(entry.getKey());
                continue;
            }
            CompoundTag chunk = new CompoundTag();
            chunk.putLong("key", entry.getKey());
            chunk.putIntArray("ground", ints(entry.getValue().ground()));
            chunk.putIntArray("water", ints(entry.getValue().water()));
            if (entry.getValue().biome() != null) {
                chunk.putString("biome", entry.getValue().biome());
            }
            chunk.putBoolean("upgraded", entry.getValue().upgraded());
            list.add(chunk);
        }
        CompoundTag known = new CompoundTag();
        files.forEach(known::putLongArray);
        CompoundTag tag = new CompoundTag();
        tag.putInt("format", 1);
        tag.putInt("algorithm", TerrainLogic.ALGORITHM_VERSION);
        tag.put("chunks", list);
        tag.putLongArray("inner", inner.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray("upgraded_inner", upgradedInner.stream().mapToLong(Long::longValue).toArray());
        tag.put("files", known);
        Files.createDirectories(snapshotFile.getParent());
        Path temporary = snapshotFile.resolveSibling("legacy.dat.tmp");
        NbtFiles.write(tag, temporary);
        Files.move(temporary, snapshotFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private static short[] shorts(int[] values) {
        short[] result = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (short) values[i];
        }
        return result;
    }

    private static int[] ints(short[] values) {
        int[] result = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }
}
