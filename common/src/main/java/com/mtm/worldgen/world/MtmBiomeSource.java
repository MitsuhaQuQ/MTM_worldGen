package com.mtm.worldgen.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mtm.worldgen.logic.WorldgenSettings;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.LinkedHashMap;
import java.util.Map;
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

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        return holders.get(MtmGenerators.terrainLogic(sampler).biomeAt(QuartPos.toBlock(quartX), QuartPos.toBlock(quartY), QuartPos.toBlock(quartZ)));
    }
}
