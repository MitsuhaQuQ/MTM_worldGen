package com.mtm.worldgen.world;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.logic.ForeignMap;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import net.minecraft.Util;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Finds chunks MTM did not generate when a world starts, such as region files merged in from another world, and fits
 * them in: superflat maps are raised, what lies below a map is generated, and their edges go into the snapshot that
 * new terrain blends toward. Changed region files are backed up first. A chunk is only ever handled once.
 */
public final class ForeignChunks {
    /** Chunk tag with the algorithm version of the MTM that generated the chunk. */
    public static final String VERSION_TAG = "mtm_version";
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

    private ForeignChunks(ServerLevel level, TerrainLogic logic, Path root) {
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
            new ForeignChunks(level, logic, level.getServer().getWorldPath(LevelResource.ROOT)).run();
        } catch (IOException | RuntimeException e) {
            // A world must still start, and a stale snapshot only costs a seam
            MtmWorldgen.LOGGER.error("Could not process foreign chunks", e);
        }
    }

    /** Runs on the world folder at a path, with the given terrain. For tests. */
    public static void run(ServerLevel level, TerrainLogic logic, Path root) throws IOException {
        new ForeignChunks(level, logic, root).run();
    }

    private void run() throws IOException {
        Map<Long, ForeignMap.Chunk> chunks = new HashMap<>();
        Map<String, long[]> files = new TreeMap<>();
        readSnapshot(chunks, files);
        List<Path> changed = changedFiles(files);
        if (!changed.isEmpty()) {
            try (ChunkStorage storage = new ChunkStorage(regions, level.getServer().getFixerUpper(), false)) {
                fit(storage, changed, chunks);
                storage.flushWorker();
            }
            for (Path file : changed) {
                files.put(file.getFileName().toString(), stamp(file));
            }
            writeSnapshot(chunks, files);
        }
        logic.setForeign(new ForeignMap(chunks, settings.foreign));
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
        final CompoundTag tag;
        final Path file;
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

    private void fit(ChunkStorage storage, List<Path> files, Map<Long, ForeignMap.Chunk> chunks) throws IOException {
        Map<Long, Found> foreign = new HashMap<>();
        for (Path file : files) {
            String[] name = file.getFileName().toString().split("\\.");
            int regionX = Integer.parseInt(name[1]);
            int regionZ = Integer.parseInt(name[2]);
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    ChunkPos pos = new ChunkPos(regionX * 32 + x, regionZ * 32 + z);
                    Optional<CompoundTag> stored = storage.read(pos).join();
                    long key = ForeignMap.key(pos.x, pos.z);
                    Origin origin = stored.isEmpty() ? Origin.OWN : origin(stored.get());
                    // The snapshot only says what stood here: a new map at the same place is told by its tags
                    if (origin == Origin.OWN || origin != Origin.FOREIGN && chunks.containsKey(key)) {
                        continue;
                    }
                    CompoundTag tag = storage.upgradeChunkTag(Level.OVERWORLD, level::getDataStorage, stored.get(), Optional.empty());
                    if (!tag.getString("Status").endsWith("full")) {
                        continue;
                    }
                    Found found = new Found(pos, tag, file);
                    read(found);
                    if (origin == Origin.FOREIGN) {
                        foreign.put(key, found);
                    } else {
                        chunks.put(key, snapshot(found, origin == Origin.UPGRADED));
                    }
                }
            }
        }
        Set<Path> backedUp = new HashSet<>();
        for (List<Found> island : islands(foreign)) {
            int lift = lift(island);
            int sea = seaOf(island, lift);
            for (Found found : island) {
                if (backedUp.add(found.file)) {
                    backUp(found.file);
                }
                rebuild(found, lift);
                alignSea(found, sea);
                storage.write(found.pos, found.tag);
                chunks.put(ForeignMap.key(found.pos.x, found.pos.z), snapshot(found, false));
            }
            MtmWorldgen.LOGGER.info("Fitted {} foreign chunks, raised by {}", island.size(), lift);
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

    /** Keeps each state a region file was fitted in, since a map can be merged in at the same place again. */
    private void backUp(Path file) throws IOException {
        Path backup = root.resolve(MtmWorldgen.MOD_ID).resolve("backup")
                .resolve(file.getFileName() + "." + Files.getLastModifiedTime(file).toMillis());
        Files.createDirectories(backup.getParent());
        if (!Files.exists(backup)) {
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
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
            for (int ground : found.ground) {
                counts.merge(ground, 1, Integer::sum);
                total++;
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
        int floor = found.lowestSection == Integer.MAX_VALUE ? 0 : found.lowestSection + liftSections;
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

    /**
     * Sections below a map, from a chunk of the world's own terrain. MTM land may lie lower than the map's floor there,
     * so the sections above that land are made solid: only rock should meet the bottom of the map.
     */
    private void fillBelow(Found found, ListTag sections, Set<Integer> taken, int floor) {
        ProtoChunk proto = new ProtoChunk(found.pos, UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        level.getChunkSource().getGenerator().fillFromNoise(Util.backgroundExecutor(), Blender.empty(),
                level.getChunkSource().randomState(), level.structureManager(), proto).join();
        int center = found.pos.getMinBlockX() + 8;
        int centerZ = found.pos.getMinBlockZ() + 8;
        int land = Mth.floor(logic.sampleSmoothLand(center, centerZ) / SECTION_BLOCKS) - STONE_SECTIONS_ABOVE_LAND;
        ListTag generated = ChunkSerializer.write(level, proto).getList("sections", Tag.TAG_COMPOUND);
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
        CompoundTag tag = NbtIo.readCompressed(snapshotFile.toFile());
        ListTag list = tag.getList("chunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag chunk = list.getCompound(i);
            chunks.put(chunk.getLong("key"), new ForeignMap.Chunk(shorts(chunk.getIntArray("ground")), shorts(chunk.getIntArray("water")),
                    chunk.contains("biome") ? chunk.getString("biome") : null, chunk.getBoolean("upgraded")));
        }
        CompoundTag known = tag.getCompound("files");
        for (String name : known.getAllKeys()) {
            files.put(name, known.getLongArray(name));
        }
    }

    private void writeSnapshot(Map<Long, ForeignMap.Chunk> chunks, Map<String, long[]> files) throws IOException {
        ListTag list = new ListTag();
        for (Map.Entry<Long, ForeignMap.Chunk> entry : new TreeMap<>(chunks).entrySet()) {
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
        tag.put("chunks", list);
        tag.put("files", known);
        Files.createDirectories(snapshotFile.getParent());
        Path temporary = snapshotFile.resolveSibling("legacy.dat.tmp");
        NbtIo.writeCompressed(tag, temporary.toFile());
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
