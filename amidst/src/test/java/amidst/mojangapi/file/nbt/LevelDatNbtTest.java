package amidst.mojangapi.file.nbt;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.IntArrayTag;

import amidst.mojangapi.world.WorldType;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

public class LevelDatNbtTest {
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/**
	 * Since 26.1, the seed and the dimensions are no longer in level.dat.
	 */
	@Test
	public void shouldReadWorldsOf26_1() throws Exception {
		Path world = folder.newFolder("world").toPath();
		CompoundTag spawn = new CompoundTag();
		spawn.put("pos", new IntArrayTag(new int[] { 96, 102, 144 }));
		CompoundTag data = new CompoundTag();
		data.put("spawn", spawn);
		writeRoot(world.resolve("level.dat"), "Data", data);

		CompoundTag generator = new CompoundTag();
		generator.putString("type", "minecraft:noise");
		generator.putString("settings", "minecraft:large_biomes");
		CompoundTag overworld = new CompoundTag();
		overworld.put("generator", generator);
		CompoundTag dimensions = new CompoundTag();
		dimensions.put("minecraft:overworld", overworld);
		CompoundTag settings = new CompoundTag();
		settings.putLong("seed", 2732658232566880066L);
		settings.put("dimensions", dimensions);
		Path settingsFile = world.resolve("data/minecraft/world_gen_settings.dat");
		Files.createDirectories(settingsFile.getParent());
		writeRoot(settingsFile, "data", settings);

		LevelDatNbt levelDat = LevelDatNbt.from(world.resolve("level.dat"));
		Assert.assertEquals(2732658232566880066L, levelDat.getSeed());
		Assert.assertEquals(CoordinatesInWorld.from(96, 144), levelDat.getWorldSpawn());
		Assert.assertEquals(WorldType.LARGE_BIOMES, levelDat.getWorldType());
	}

	@Test
	public void shouldStillReadOlderWorlds() throws Exception {
		Path world = folder.newFolder("old").toPath();
		CompoundTag data = new CompoundTag();
		data.putLong("RandomSeed", 42L);
		data.putInt("SpawnX", -10);
		data.putInt("SpawnZ", 20);
		data.putString("generatorName", "default");
		writeRoot(world.resolve("level.dat"), "Data", data);

		LevelDatNbt levelDat = LevelDatNbt.from(world.resolve("level.dat"));
		Assert.assertEquals(42L, levelDat.getSeed());
		Assert.assertEquals(CoordinatesInWorld.from(-10, 20), levelDat.getWorldSpawn());
		Assert.assertEquals(WorldType.DEFAULT, levelDat.getWorldType());
	}

	private static void writeRoot(Path file, String key, CompoundTag value) throws Exception {
		CompoundTag root = new CompoundTag();
		root.put(key, value);
		NBTUtils.writeTagToFile(file, root);
	}
}
