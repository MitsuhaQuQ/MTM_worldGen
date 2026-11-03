package amidst.mojangapi.minecraftinterface.modern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import amidst.documentation.ThreadSafe;
import amidst.fragment.Fragment;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.coordinates.Resolution;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.icon.producer.CachedWorldIconProducer;
import amidst.mojangapi.world.icon.producer.WorldIconProducer;
import amidst.mojangapi.world.icon.type.DefaultWorldIconTypes;

/**
 * Icons for the structures that Minecraft itself places, so they follow the
 * structure sets, biome tags and placement rules of the loaded version.
 */
public enum ModernStructures {
	;

	/**
	 * The icon for a structure of the vanilla data pack, or null for
	 * structures that Amidst does not show, such as end cities, which Amidst
	 * finds with its own code.
	 */
	public static DefaultWorldIconTypes iconOf(String structureKey) {
		if (!structureKey.startsWith("minecraft:")) {
			return null;
		}
		String path = structureKey.substring("minecraft:".length());
		if (path.startsWith("village_")) {
			return DefaultWorldIconTypes.VILLAGE;
		} else if (path.startsWith("ocean_ruin")) {
			return DefaultWorldIconTypes.OCEAN_RUINS;
		} else if (path.startsWith("shipwreck")) {
			return DefaultWorldIconTypes.SHIPWRECK;
		} else if (path.startsWith("mineshaft")) {
			return DefaultWorldIconTypes.MINESHAFT;
		} else if (path.startsWith("ruined_portal")) {
			return DefaultWorldIconTypes.RUINED_PORTAL;
		} else if (path.startsWith("abandoned_camp")) {
			return DefaultWorldIconTypes.ABANDONED_CAMP;
		}
		switch (path) {
		case "pillager_outpost":
			return DefaultWorldIconTypes.PILLAGER_OUTPOST;
		case "desert_pyramid":
			return DefaultWorldIconTypes.DESERT;
		case "jungle_pyramid":
			return DefaultWorldIconTypes.JUNGLE;
		case "swamp_hut":
			return DefaultWorldIconTypes.WITCH;
		case "igloo":
			return DefaultWorldIconTypes.IGLOO;
		case "monument":
			return DefaultWorldIconTypes.OCEAN_MONUMENT;
		case "mansion":
			return DefaultWorldIconTypes.WOODLAND_MANSION;
		case "buried_treasure":
			return DefaultWorldIconTypes.BURIED_TREASURE;
		case "stronghold":
			return DefaultWorldIconTypes.STRONGHOLD;
		case "ancient_city":
			return DefaultWorldIconTypes.ANCIENT_CITY;
		case "trail_ruins":
			return DefaultWorldIconTypes.TRAIL_RUINS;
		case "trial_chambers":
			return DefaultWorldIconTypes.TRIAL_CHAMBERS;
		case "fortress":
			return DefaultWorldIconTypes.NETHER_FORTRESS;
		case "bastion_remnant":
			return DefaultWorldIconTypes.BASTION_REMNANT;
		case "nether_fossil":
			return DefaultWorldIconTypes.NETHER_FOSSIL;
		default:
			return null;
		}
	}

	/**
	 * Shows the structures of some types in one dimension. Nether structures
	 * are shown on the Overworld map, at their Overworld coordinates, like
	 * the other nether structures of Amidst.
	 */
	@ThreadSafe
	public static class Producer extends WorldIconProducer<Void> {
		private final ModernDimension dimension;
		private final Dimension amidstDimension;
		private final Set<DefaultWorldIconTypes> types;

		public Producer(ModernDimension dimension, Dimension amidstDimension, DefaultWorldIconTypes... types) {
			this.dimension = dimension;
			this.amidstDimension = amidstDimension;
			this.types = Collections.unmodifiableSet(EnumSet.of(types[0], types));
		}

		@Override
		public void produce(CoordinatesInWorld corner, Consumer<WorldIcon> consumer, Void additionalData) {
			Resolution chunks = amidstDimension == Dimension.NETHER ? Resolution.NETHER_CHUNK : Resolution.CHUNK;
			dimension.findStructures(
					(int) corner.getXAs(chunks),
					(int) corner.getYAs(chunks),
					chunks.getStepsPerFragment(),
					types,
					(type, x, z) -> {
						CoordinatesInWorld coordinates = toWorld(x, z);
						if (coordinates.isInBoundsOf(corner, Fragment.SIZE)) {
							consumer.accept(createIcon(type, coordinates));
						}
					});
		}

		private CoordinatesInWorld toWorld(int blockX, int blockZ) {
			if (amidstDimension == Dimension.NETHER) {
				return CoordinatesInWorld.from(
						Resolution.NETHER.convertFromThisToWorld(blockX),
						Resolution.NETHER.convertFromThisToWorld(blockZ));
			}
			return CoordinatesInWorld.from(blockX, blockZ);
		}

		private WorldIcon createIcon(DefaultWorldIconTypes type, CoordinatesInWorld coordinates) {
			return new WorldIcon(coordinates, type.getLabel(), type.getImage(), amidstDimension, amidstDimension != Dimension.OVERWORLD);
		}
	}

	/**
	 * The strongholds, which Minecraft places on rings around the origin of
	 * the world.
	 */
	@ThreadSafe
	public static class StrongholdProducer extends CachedWorldIconProducer {
		private static final Set<DefaultWorldIconTypes> TYPES = Collections.unmodifiableSet(EnumSet.of(DefaultWorldIconTypes.STRONGHOLD));

		private final ModernDimension dimension;

		public StrongholdProducer(ModernDimension dimension) {
			this.dimension = dimension;
		}

		@Override
		protected List<WorldIcon> doCreateCache() {
			List<WorldIcon> result = new ArrayList<>();
			for (ModernDimension.ConcentricRings rings : dimension.getConcentricRings(TYPES)) {
				for (int[] chunk : rings.getChunks()) {
					dimension.findStructure(rings, chunk, TYPES, (type, x, z) -> result.add(new WorldIcon(
							CoordinatesInWorld.from(x, z),
							type.getLabel(),
							type.getImage(),
							Dimension.OVERWORLD,
							false)));
				}
			}
			return result;
		}
	}
}
