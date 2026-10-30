package com.mtm.worldgen.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Biomes chosen by the terrain logic from terrain and climate, instead of vanilla's climate parameter lookup. */
public final class MtmBiomeSource extends BiomeSource {
    public static final Codec<MtmBiomeSource> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RegistryOps.retrieveRegistry(Registry.BIOME_REGISTRY).forGetter(source -> source.registry),
            Codec.LONG.fieldOf("seed").forGetter(source -> source.seed),
            Codec.STRING.fieldOf("settings").forGetter(source -> source.settingsJson)
    ).apply(instance, MtmBiomeSource::new));

    private final Registry<Biome> registry;
    private final long seed;
    private final String settingsJson;
    private final Map<String, Holder<Biome>> holders;
    private final TerrainLogic logic;

    public MtmBiomeSource(Registry<Biome> registry, long seed, WorldgenSettings settings) {
        this(registry, seed, settings.toJson());
    }

    private MtmBiomeSource(Registry<Biome> registry, long seed, String settingsJson) {
        this(registry, seed, settingsJson, resolve(registry, WorldgenSettings.fromJson(settingsJson)));
    }

    private MtmBiomeSource(Registry<Biome> registry, long seed, String settingsJson, Map<String, Holder<Biome>> holders) {
        super(new ArrayList<>(holders.values()));
        this.registry = registry;
        this.seed = seed;
        this.settingsJson = settingsJson;
        this.holders = holders;
        this.logic = TerrainLogic.forWorld(seed, WorldgenSettings.fromJson(settingsJson));
    }

    /** Looks up every configured biome, in configuration order, which also fixes the feature order vanilla derives. */
    private static Map<String, Holder<Biome>> resolve(Registry<Biome> registry, WorldgenSettings settings) {
        Map<String, Holder<Biome>> holders = new LinkedHashMap<>();
        for (String id : settings.biomes.allBiomes()) {
            ResourceKey<Biome> key = ResourceKey.create(Registry.BIOME_REGISTRY, new ResourceLocation(id));
            if (!registry.containsKey(key)) {
                throw new IllegalStateException("Unknown biome in config: " + id);
            }
            holders.put(id, registry.getHolderOrThrow(key));
        }
        return holders;
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    public BiomeSource withSeed(long newSeed) {
        return new MtmBiomeSource(registry, newSeed, settingsJson);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        return holders.get(logic.biomeAt(QuartPos.toBlock(quartX), QuartPos.toBlock(quartY), QuartPos.toBlock(quartZ)));
    }
}
