package amidst.mojangapi.minecraftinterface.modern;

import java.util.Arrays;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import amidst.mojangapi.world.biome.BiomeList;
import amidst.mojangapi.world.icon.type.DefaultWorldIconTypes;

public class ModernBiomesTest {
	@Test
	public void shouldKeepTheIdsOfRenamedBiomes() {
		Map<String, Integer> ids = ModernBiomes.assignIds(Arrays.asList(
				"minecraft:snowy_plains", "minecraft:windswept_hills", "minecraft:old_growth_pine_taiga"));
		// Snowy Tundra, Mountains and Giant Tree Taiga in 1.17
		Assert.assertEquals(12, (int) ids.get("minecraft:snowy_plains"));
		Assert.assertEquals(3, (int) ids.get("minecraft:windswept_hills"));
		Assert.assertEquals(32, (int) ids.get("minecraft:old_growth_pine_taiga"));
	}

	@Test
	public void shouldGiveUnknownBiomesStableIds() {
		Map<String, Integer> ids = ModernBiomes.assignIds(Arrays.asList(
				"mod:zeta", "minecraft:plains", "mod:alpha"));
		Assert.assertEquals(1, (int) ids.get("minecraft:plains"));
		Assert.assertEquals(1000, (int) ids.get("mod:alpha"));
		Assert.assertEquals(1001, (int) ids.get("mod:zeta"));
	}

	@Test
	public void shouldNameBiomesLikeTheGame() {
		Assert.assertEquals("Old Growth Pine Taiga", ModernBiomes.displayName("minecraft:old_growth_pine_taiga"));
		Assert.assertEquals("Cherry Grove", ModernBiomes.displayName("minecraft:cherry_grove"));
		Assert.assertEquals("Mod Biome", ModernBiomes.displayName("other:mod_biome"));
	}

	@Test
	public void shouldListAllBiomes() throws Exception {
		BiomeList list = ModernBiomes.createBiomeList(ModernBiomes.assignIds(Arrays.asList(
				"minecraft:pale_garden", "minecraft:deep_dark")));
		Assert.assertEquals("Pale Garden", list.getById(185).getName());
		Assert.assertEquals("Deep Dark", list.getById(182).getName());
	}

	@Test
	public void shouldShowStructuresWithTheirIcons() {
		Assert.assertEquals(DefaultWorldIconTypes.VILLAGE, ModernStructures.iconOf("minecraft:village_savanna"));
		Assert.assertEquals(DefaultWorldIconTypes.WITCH, ModernStructures.iconOf("minecraft:swamp_hut"));
		Assert.assertEquals(DefaultWorldIconTypes.OCEAN_RUINS, ModernStructures.iconOf("minecraft:ocean_ruin_cold"));
		Assert.assertEquals(DefaultWorldIconTypes.RUINED_PORTAL, ModernStructures.iconOf("minecraft:ruined_portal_nether"));
		Assert.assertEquals(DefaultWorldIconTypes.ABANDONED_CAMP, ModernStructures.iconOf("minecraft:abandoned_camp_forest"));
		Assert.assertEquals(DefaultWorldIconTypes.TRIAL_CHAMBERS, ModernStructures.iconOf("minecraft:trial_chambers"));
		// Amidst finds end cities with its own code
		Assert.assertNull(ModernStructures.iconOf("minecraft:end_city"));
		Assert.assertNull(ModernStructures.iconOf("othermod:village_plains"));
	}
}
