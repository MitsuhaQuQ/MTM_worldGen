package amidst.mojangapi.minecraftinterface.modern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import amidst.documentation.ThreadSafe;
import amidst.logging.AmidstLogger;
import amidst.mojangapi.minecraftinterface.modern.Mc.McException;
import amidst.mojangapi.world.icon.type.DefaultWorldIconTypes;

/**
 * The generator of one dimension of a world, as Minecraft sets it up for a
 * new world with the given seed.
 */
@ThreadSafe
public class ModernDimension {
	private static final String RANDOM_SPREAD = "net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement";
	private static final String CONCENTRIC_RINGS = "net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement";
	private static final String NOISE_BASED_GENERATOR = "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator";
	private static final String REGISTRIES = "net.minecraft.core.registries.Registries";

	private final Mc mc;
	private final Object registryAccess;
	private final Supplier<Object> templateManager;
	private final long seed;
	private final Object generator;
	private final Object biomeSource;
	private final Object randomState;
	private final Object structureState;
	private final Object heightAccessor;
	private final List<StructureSet> structureSets;
	private final ThreadLocal<Object> climateSampler;
	private final Set<String> failedStructures = ConcurrentHashMap.newKeySet();
	private final Map<StructureSet, List<int[]>> ringChunks = new ConcurrentHashMap<>();

	public ModernDimension(Mc mc, Object registryAccess, Object levelStem, long seed, Supplier<Object> templateManager) {
		this.mc = mc;
		this.registryAccess = registryAccess;
		this.templateManager = templateManager;
		this.seed = seed;
		this.generator = mc.call(levelStem, "generator");
		this.biomeSource = mc.call(generator, "getBiomeSource");
		this.randomState = createRandomState();
		this.structureState = mc.call(generator, "createState", registry("STRUCTURE_SET"), randomState, seed);
		mc.call(structureState, "ensureStructuresGenerated");
		Object dimensionType = mc.call(mc.call(levelStem, "type"), "value");
		this.heightAccessor = mc.callStatic("net.minecraft.world.level.LevelHeightAccessor", "create",
				mc.call(dimensionType, "minY"), mc.call(dimensionType, "height"));
		this.structureSets = readStructureSets();
		this.climateSampler = ThreadLocal.withInitial(() -> mc.call(randomState, "createClimateSampler",
				mc.call(mc.call(mc.callStatic("net.minecraft.world.level.levelgen.densityfunction.SamplerContext", "builder"),
						"enableCaches"), "build")));
	}

	private Object registry(String name) {
		return mc.call(registryAccess, "lookupOrThrow", mc.get(REGISTRIES, name));
	}

	/**
	 * Like ChunkMap does it for the generator of a level.
	 */
	private Object createRandomState() {
		String randomState = "net.minecraft.world.level.levelgen.RandomState";
		if (mc.type(NOISE_BASED_GENERATOR).isInstance(generator)) {
			return mc.callStatic(randomState, "create",
					registry("NOISE"), seed, mc.call(mc.call(generator, "generatorSettings"), "value"));
		} else {
			// Flat worlds have no noise of their own
			Object stone = mc.call(mc.get("net.minecraft.world.level.block.Blocks", "STONE"), "defaultBlockState");
			return mc.callStatic(randomState, "create",
					registry("NOISE"), seed, false, stone, 63, mc.callStatic("net.minecraft.world.level.levelgen.NoiseRouterData", "none"));
		}
	}

	private List<StructureSet> readStructureSets() {
		List<StructureSet> result = new ArrayList<>();
		for (Object holder : (List<?>) mc.call(structureState, "possibleStructureSets")) {
			Object set = mc.call(holder, "value");
			List<StructureEntry> entries = new ArrayList<>();
			EnumSet<DefaultWorldIconTypes> types = EnumSet.noneOf(DefaultWorldIconTypes.class);
			for (Object entry : (List<?>) mc.call(set, "structures")) {
				Object structure = mc.call(entry, "structure");
				String key = keyOf(structure);
				DefaultWorldIconTypes type = ModernStructures.iconOf(key);
				if (type != null) {
					types.add(type);
				}
				entries.add(new StructureEntry(structure, key, (Integer) mc.call(entry, "weight"), type));
			}
			result.add(new StructureSet(keyOf(holder), mc.call(set, "placement"), entries, types));
		}
		return Collections.unmodifiableList(result);
	}

	private String keyOf(Object holder) {
		Optional<?> key = (Optional<?>) mc.call(holder, "unwrapKey");
		return key.map(k -> mc.call(k, "identifier").toString()).orElse("unregistered");
	}

	public long getSeed() {
		return seed;
	}

	/**
	 * A new resolver of the noise biomes, which are at quarter resolution.
	 * Each thread needs its own.
	 */
	public Object createBiomeResolver() {
		return mc.call(biomeSource, "createUncachedResolver", randomState);
	}

	/**
	 * The chunk that Minecraft starts its search for the world spawn from.
	 */
	public int[] getOriginChunk() {
		Object chunkPos = mc.call(generator, "getOrigin", randomState);
		return new int[] { (Integer) mc.call(chunkPos, "x"), (Integer) mc.call(chunkPos, "z") };
	}

	public List<ConcentricRings> getConcentricRings(Set<DefaultWorldIconTypes> types) {
		List<ConcentricRings> result = new ArrayList<>();
		for (StructureSet set : structureSets) {
			if (mc.type(CONCENTRIC_RINGS).isInstance(set.placement) && !Collections.disjoint(set.types, types)) {
				result.add(new ConcentricRings(set, getRingChunks(set)));
			}
		}
		return result;
	}

	/**
	 * Minecraft searches for biomes to place the rings, so the chunks are only
	 * computed once.
	 */
	private List<int[]> getRingChunks(StructureSet set) {
		return ringChunks.computeIfAbsent(set, s -> {
			List<int[]> result = new ArrayList<>();
			List<?> positions = (List<?>) mc.call(structureState, "getRingPositionsFor", s.placement);
			for (Object chunkPos : positions == null ? Collections.emptyList() : positions) {
				result.add(new int[] { (Integer) mc.call(chunkPos, "x"), (Integer) mc.call(chunkPos, "z") });
			}
			return Collections.unmodifiableList(result);
		});
	}

	/**
	 * Finds the structures of the given types whose start chunk is in the
	 * given square of chunks. The coordinates passed to the consumer are those
	 * that /locate reports.
	 */
	public void findStructures(
			int chunkX,
			int chunkZ,
			int size,
			Set<DefaultWorldIconTypes> types,
			StructureConsumer consumer) {
		for (StructureSet set : structureSets) {
			if (Collections.disjoint(set.types, types)) {
				continue;
			}
			for (int[] chunk : getCandidateChunks(set, chunkX, chunkZ, size)) {
				findStructure(set, chunk[0], chunk[1], types, consumer);
			}
		}
	}

	public void findStructure(ConcentricRings rings, int[] chunk, Set<DefaultWorldIconTypes> types, StructureConsumer consumer) {
		findStructure(rings.set, chunk[0], chunk[1], types, consumer);
	}

	private List<int[]> getCandidateChunks(StructureSet set, int chunkX, int chunkZ, int size) {
		List<int[]> result = new ArrayList<>();
		if (mc.type(RANDOM_SPREAD).isInstance(set.placement)) {
			int spacing = (Integer) mc.call(set.placement, "spacing");
			for (int regionX = Math.floorDiv(chunkX, spacing); regionX <= Math.floorDiv(chunkX + size - 1, spacing); regionX++) {
				for (int regionZ = Math.floorDiv(chunkZ, spacing); regionZ <= Math.floorDiv(chunkZ + size - 1, spacing); regionZ++) {
					Object chunkPos = mc.call(set.placement, "getPotentialStructureChunk", seed, regionX * spacing, regionZ * spacing);
					int x = (Integer) mc.call(chunkPos, "x");
					int z = (Integer) mc.call(chunkPos, "z");
					if (isInSquare(x, z, chunkX, chunkZ, size)) {
						result.add(new int[] { x, z });
					}
				}
			}
		} else if (mc.type(CONCENTRIC_RINGS).isInstance(set.placement)) {
			for (int[] chunk : getRingChunks(set)) {
				if (isInSquare(chunk[0], chunk[1], chunkX, chunkZ, size)) {
					result.add(chunk);
				}
			}
		} else {
			// A kind of placement Amidst does not know, so ask about every chunk
			for (int x = chunkX; x < chunkX + size; x++) {
				for (int z = chunkZ; z < chunkZ + size; z++) {
					result.add(new int[] { x, z });
				}
			}
		}
		return result;
	}

	private static boolean isInSquare(int x, int z, int chunkX, int chunkZ, int size) {
		return x >= chunkX && x < chunkX + size && z >= chunkZ && z < chunkZ + size;
	}

	/**
	 * Does what ChunkGenerator.createStructures does for one structure set
	 * and chunk, but without building the pieces of the structure.
	 */
	private void findStructure(StructureSet set, int chunkX, int chunkZ, Set<DefaultWorldIconTypes> types, StructureConsumer consumer) {
		if (!(Boolean) mc.call(set.placement, "isStructureChunk", structureState, chunkX, chunkZ)) {
			return;
		}
		Object chunkPos = mc.create("net.minecraft.world.level.ChunkPos", chunkX, chunkZ);
		StructureEntry found = null;
		if (set.entries.size() == 1) {
			if (isValidStart(set.entries.get(0), chunkPos)) {
				found = set.entries.get(0);
			}
		} else {
			List<StructureEntry> options = new ArrayList<>(set.entries);
			Object random = mc.create("net.minecraft.world.level.levelgen.WorldgenRandom",
					mc.create("net.minecraft.world.level.levelgen.LegacyRandomSource", 0L));
			mc.call(random, "setLargeFeatureSeed", seed, chunkX, chunkZ);
			int total = options.stream().mapToInt(o -> o.weight).sum();
			while (!options.isEmpty()) {
				int choice = (Integer) mc.call(random, "nextInt", total);
				int index = 0;
				for (StructureEntry option : options) {
					choice -= option.weight;
					if (choice < 0) {
						break;
					}
					index++;
				}
				StructureEntry selected = options.get(index);
				if (isValidStart(selected, chunkPos)) {
					found = selected;
					break;
				}
				options.remove(index);
				total -= selected.weight;
			}
		}
		if (found != null && found.type != null && types.contains(found.type)) {
			Object locatePos = mc.call(set.placement, "getLocatePos", chunkPos);
			consumer.accept(found.type, (Integer) mc.call(locatePos, "getX"), (Integer) mc.call(locatePos, "getZ"));
		}
	}

	private boolean isValidStart(StructureEntry entry, Object chunkPos) {
		try {
			Object structure = mc.call(entry.holder, "value");
			Object biomes = mc.call(structure, "biomes");
			Predicate<Object> validBiome = biome -> (Boolean) mc.call(biomes, "contains", biome);
			Object context = mc.create("net.minecraft.world.level.levelgen.structure.Structure$GenerationContext",
					registryAccess, generator, biomeSource, climateSampler.get(), randomState, templateManager.get(),
					seed, chunkPos, heightAccessor, validBiome);
			return ((Optional<?>) mc.call(structure, "findValidGenerationPoint", context)).isPresent();
		} catch (McException e) {
			if (failedStructures.add(entry.key)) {
				AmidstLogger.warn(e, "Unable to check where Minecraft places {}", entry.key);
			}
			return false;
		}
	}

	@FunctionalInterface
	public interface StructureConsumer {
		void accept(DefaultWorldIconTypes type, int blockX, int blockZ);
	}

	public static class ConcentricRings {
		private final StructureSet set;
		private final List<int[]> chunks;

		private ConcentricRings(StructureSet set, List<int[]> chunks) {
			this.set = set;
			this.chunks = Collections.unmodifiableList(chunks);
		}

		public List<int[]> getChunks() {
			return chunks;
		}
	}

	private static class StructureSet {
		private final String key;
		private final Object placement;
		private final List<StructureEntry> entries;
		private final Set<DefaultWorldIconTypes> types;

		private StructureSet(String key, Object placement, List<StructureEntry> entries, Set<DefaultWorldIconTypes> types) {
			this.key = key;
			this.placement = placement;
			this.entries = Collections.unmodifiableList(entries);
			this.types = Collections.unmodifiableSet(types);
		}

		@Override
		public String toString() {
			return key;
		}
	}

	private static class StructureEntry {
		private final Object holder;
		private final String key;
		private final int weight;
		private final DefaultWorldIconTypes type;

		private StructureEntry(Object holder, String key, int weight, DefaultWorldIconTypes type) {
			this.holder = holder;
			this.key = key;
			this.weight = weight;
			this.type = type;
		}
	}
}
