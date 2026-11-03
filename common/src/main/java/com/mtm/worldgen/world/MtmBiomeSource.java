package com.mtm.worldgen.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mtm.worldgen.logic.WorldgenSettings;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Biomes chosen by the terrain logic from terrain and climate, instead of vanilla's climate parameter lookup. Biome
 * sources no longer receive the seed, so the terrain logic comes with the climate sampler. A source without settings
 * is the placeholder of the world preset, replaced from the config file when a world is created.
 */
public final class MtmBiomeSource extends BiomeSource {
    public static final Codec<MtmBiomeSource> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RegistryOps.retrieveGetter(Registries.BIOME),
            RegistryOps.retrieveGetter(Registries.DIMENSION_TYPE),
            RegistryOps.retrieveGetter(Registries.DENSITY_FUNCTION),
            RegistryOps.retrieveGetter(Registries.NOISE),
            Codec.STRING.optionalFieldOf("settings", "").forGetter(source -> source.settingsJson)
    ).apply(instance, (biomes, dimensionTypes, densityFunctions, noises, settings) ->
            new MtmBiomeSource(new MtmGenerators.Lookups(biomes, dimensionTypes, densityFunctions, noises), settings)));

    private final MtmGenerators.Lookups lookups;
    private final String settingsJson;
    private final Map<String, Holder<Biome>> holders;

    public MtmBiomeSource(MtmGenerators.Lookups lookups, WorldgenSettings settings) {
        this(lookups, settings.toJson());
    }

    private MtmBiomeSource(MtmGenerators.Lookups lookups, String settingsJson) {
        this.lookups = lookups;
        this.settingsJson = settingsJson;
        this.holders = settingsJson.isEmpty() ? Map.of() : resolve(lookups.biomes(), WorldgenSettings.fromJson(settingsJson));
    }

    /** Whether this is the world preset's stand-in for a source built from the config file. */
    public boolean isPlaceholder() {
        return settingsJson.isEmpty();
    }

    public MtmGenerators.Lookups lookups() {
        return lookups;
    }

    /** Looks up every configured biome, in configuration order, which also fixes the feature order vanilla derives. */
    private static Map<String, Holder<Biome>> resolve(HolderGetter<Biome> biomes, WorldgenSettings settings) {
        Map<String, Holder<Biome>> holders = new LinkedHashMap<>();
        for (String id : settings.biomes.allBiomes()) {
            ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, new ResourceLocation(id));
            holders.put(id, biomes.get(key).orElseThrow(() -> new IllegalStateException("Unknown biome in config: " + id)));
        }
        return holders;
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return holders.values().stream();
    }

    /**
     * Vanilla's horizontal biome search, on estimated biomes. It finds where strongholds go, thousands of samples each
     * over tens of thousands of blocks, and exact biomes there would erode every region on the way; the estimate
     * differs only in detail, such as along rivers and in eroded valleys. The search itself is vanilla's, so the
     * random choices among matches stay the same.
     */
    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius, int increment,
                                                            Predicate<Holder<Biome>> predicate, RandomSource random,
                                                            boolean findClosest, Climate.Sampler sampler) {
        var logic = MtmGenerators.terrainLogic(sampler);
        int centerX = QuartPos.fromBlock(x);
        int centerZ = QuartPos.fromBlock(z);
        int reach = QuartPos.fromBlock(radius);
        Pair<BlockPos, Holder<Biome>> found = null;
        int matches = 0;
        for (int ring = findClosest ? 0 : reach; ring <= reach; ring += increment) {
            for (int dz = -ring; dz <= ring; dz += increment) {
                boolean edgeZ = Math.abs(dz) == ring;
                for (int dx = -ring; dx <= ring; dx += increment) {
                    if (findClosest && Math.abs(dx) != ring && !edgeZ) {
                        continue;
                    }
                    int quartX = centerX + dx;
                    int quartZ = centerZ + dz;
                    Holder<Biome> biome = holders.get(logic.estimateBiome(QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ)));
                    if (predicate.test(biome)) {
                        if (found == null || random.nextInt(matches + 1) == 0) {
                            BlockPos position = new BlockPos(QuartPos.toBlock(quartX), y, QuartPos.toBlock(quartZ));
                            if (findClosest) {
                                return Pair.of(position, biome);
                            }
                            found = Pair.of(position, biome);
                        }
                        matches++;
                    }
                }
            }
        }
        return found;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        return holders.get(MtmGenerators.terrainLogic(sampler).biomeAt(QuartPos.toBlock(quartX), QuartPos.toBlock(quartY), QuartPos.toBlock(quartZ)));
    }
}
