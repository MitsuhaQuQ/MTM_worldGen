package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

/** Sea stacks off rocky coasts. They stand on the finished sea floor, so no other step sees them. */
final class SeaStackLayer {
    private static final long STACK_SALT = 0x535441434BL;
    /** Slots of the per-thread site cache, a power of two. */
    private static final int CACHE_SIZE = 256;
    /** Outer radius of the skirt around a stack, as a multiple of its radius. */
    private static final double SKIRT_REACH = 1.8;
    /** Share of the stack height the skirt rises at the foot of the walls. */
    private static final double SKIRT_SHARE = 0.2;
    /** Where the walls start, as a share of the radius. */
    private static final double WALL_START = 0.7;
    /** How far a stack strays from the center of its cell, as a share of the cell. */
    private static final double JITTER = 0.9;

    private final WorldgenSettings.SeaStacks config;
    private final TerrainLogic logic;
    private final long seed;
    private final double scale;
    private final Fbm warp;
    private final ThreadLocal<Stack[]> stacks = ThreadLocal.withInitial(() -> new Stack[CACHE_SIZE]);

    SeaStackLayer(long seed, WorldgenSettings settings, TerrainLogic logic) {
        this.config = settings.seaStacks;
        this.logic = logic;
        this.seed = Hashing.derive(seed, STACK_SALT);
        this.scale = settings.heightScale();
        this.warp = new Fbm(Hashing.derive(seed, STACK_SALT + 1), 3, config.warpWavelength);
    }

    /** Height at a position with any stack standing on the land. */
    double raise(double x, double z, double land) {
        int cellX = (int) Math.floor(x / config.spacing);
        int cellZ = (int) Math.floor(z / config.spacing);
        Stack[] cache = stacks.get();
        double raised = land;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Stack stack = stack(cache, cellX + dx, cellZ + dz);
                if (stack.radius > 0.0) {
                    double share = share(stack, x, z);
                    if (share > 0.0) {
                        raised = Math.max(raised, land + Math.max(0.0, stack.top - land) * share);
                    }
                }
            }
        }
        return raised;
    }

    /** Whether a position lies on the walls or top of a stack, as opposed to its skirt or open water. */
    boolean contains(double x, double z) {
        int cellX = (int) Math.floor(x / config.spacing);
        int cellZ = (int) Math.floor(z / config.spacing);
        Stack[] cache = stacks.get();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Stack stack = stack(cache, cellX + dx, cellZ + dz);
                if (stack.radius > 0.0 && share(stack, x, z) > SKIRT_SHARE) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Share of the way from the land up to the top of a stack. */
    private double share(Stack stack, double x, double z) {
        double distance = Math.hypot(x - stack.x, z - stack.z);
        if (distance >= stack.radius * SKIRT_REACH * (1.0 + config.warp)) {
            return 0.0;
        }
        double u = distance / (stack.radius * (1.0 + config.warp * warp.sample(x, z)));
        double wall = 1.0 - smooth((u - WALL_START) / (1.0 - WALL_START));
        double skirt = SKIRT_SHARE * (1.0 - smooth((u - 1.0) / (SKIRT_REACH - 1.0)));
        return Math.min(1.0, Math.max(wall, skirt));
    }

    private Stack stack(Stack[] cache, int cellX, int cellZ) {
        int slot = (cellX * 31 + cellZ) & (CACHE_SIZE - 1);
        Stack stack = cache[slot];
        if (stack == null || stack.cellX != cellX || stack.cellZ != cellZ) {
            stack = createStack(cellX, cellZ);
            cache[slot] = stack;
        }
        return stack;
    }

    private Stack createStack(int cellX, int cellZ) {
        long hash = Hashing.hash(seed, cellX, cellZ);
        double x = (cellX + 0.5 + JITTER * (Hashing.unit(hash) - 0.5)) * config.spacing;
        double z = (cellZ + 0.5 + JITTER * (Hashing.unit(Hashing.mix(hash)) - 0.5)) * config.spacing;
        if (Hashing.unit(Hashing.derive(hash, 2)) >= config.chance || !logic.isStackSite(x, z)) {
            return new Stack(cellX, cellZ, x, z, 0.0, 0.0);
        }
        double size = Hashing.unit(Hashing.derive(hash, 3));
        double girth = Hashing.unit(Hashing.derive(hash, 4));
        double height = scale * (config.heightMin + size * (config.heightMax - config.heightMin));
        double radius = config.radiusMin + girth * (config.radiusMax - config.radiusMin);
        return new Stack(cellX, cellZ, x, z, radius, logic.seaSurface() + height);
    }

    private static double smooth(double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    private record Stack(int cellX, int cellZ, double x, double z, double radius, double top) {
    }
}
