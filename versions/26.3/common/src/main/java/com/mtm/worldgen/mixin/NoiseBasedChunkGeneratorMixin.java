package com.mtm.worldgen.mixin;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.MtmCarving;
import com.mtm.worldgen.world.MtmGenerators;
import com.mtm.worldgen.world.MtmOasis;
import com.mtm.worldgen.world.MtmWater;
import com.mtm.worldgen.world.TerrainCondition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Exposes the generator's terrain logic to MTM material rule conditions while a chunk's surface is built, protects the
 * surface from carvers, and stops chunk filling at the surface of MTM worlds.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Shadow
    @Final
    private Supplier<Aquifer.FluidPicker> globalFluidPicker;

    @Inject(method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Ljava/util/Set;Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;)V",
            at = @At("HEAD"))
    private void mtm_worldgen$beginSurface(ChunkAccess chunk, NoiseChunk noiseChunk, RandomState random, BiomeManager biomes,
                                           Set<Holder<Biome>> possibleBiomes, MaterialRule rule, CallbackInfo ci) {
        TerrainLogic logic = MtmGenerators.terrainLogic(random);
        if (logic != null) {
            MtmWater.fill(logic, chunk);
            TerrainCondition.begin(logic);
        }
    }

    @Inject(method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Ljava/util/Set;Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;)V",
            at = @At("RETURN"))
    private void mtm_worldgen$endSurface(ChunkAccess chunk, NoiseChunk noiseChunk, RandomState random, BiomeManager biomes,
                                         Set<Holder<Biome>> possibleBiomes, MaterialRule rule, CallbackInfo ci) {
        TerrainCondition.end();
        TerrainLogic logic = MtmGenerators.terrainLogic(random);
        if (logic != null) {
            MtmOasis.plant(logic, chunk);
        }
    }

    @Inject(method = "generateCarvers", at = @At("HEAD"), cancellable = true)
    private void mtm_worldgen$beginCarving(ChunkAccess chunk, Blender blender, NoiseChunk noiseChunk, RandomState random,
                                           BiomeManager biomes, WorldGenRegion region, MaterialRule rule, CallbackInfo ci) {
        TerrainLogic logic = MtmGenerators.terrainLogic(random);
        if (logic == null) {
            return;
        }
        // A world without caves skips carving altogether, rather than turning every carved block down
        if (!logic.settings().general.caves) {
            ci.cancel();
            return;
        }
        MtmCarving.begin(logic, chunk.getPos());
    }

    @Inject(method = "generateCarvers", at = @At("RETURN"))
    private void mtm_worldgen$endCarving(ChunkAccess chunk, Blender blender, NoiseChunk noiseChunk, RandomState random,
                                         BiomeManager biomes, WorldGenRegion region, MaterialRule rule, CallbackInfo ci) {
        MtmCarving.end();
    }

    /** Every block carvers remove passes the mask's filter, which makes it the one place to protect the surface. */
    @ModifyVariable(method = "applyCarvingMask", at = @At("HEAD"), argsOnly = true)
    private CarvingMask.Filter mtm_worldgen$protectSurface(CarvingMask.Filter filter) {
        return MtmCarving.protect(filter);
    }

    /**
     * Limits the volume filled. Most of a tall world is air above the surface, and filling it runs the interpolators,
     * aquifer and material rules on every block for nothing.
     */
    @Inject(method = "createNoiseChunk", at = @At("HEAD"), cancellable = true)
    private void mtm_worldgen$limitToSurface(ChunkAccess chunk, StructureManager structures, Blender blender, RandomState random,
                                             NoiseSettings noise, CallbackInfoReturnable<NoiseChunk> cir) {
        TerrainLogic logic = MtmGenerators.terrainLogic(random);
        if (logic == null) {
            return;
        }
        int top = Math.min(noise.minY() + noise.height() - 1, logic.highestBlockInChunk(chunk.getPos().x(), chunk.getPos().z()));
        DensityVolume volume = new DensityVolume(16, top - noise.minY() + 1, 16,
                chunk.getPos().getMinBlockX(), noise.minY(), chunk.getPos().getMinBlockZ());
        NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        cir.setReturnValue(new NoiseChunk(random, Beardifier.forStructuresInChunk(structures, chunk.getPos()),
                self.generatorSettings().value(), globalFluidPicker.get(), blender, volume));
    }
}
