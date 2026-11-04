package com.mtm.worldgen.command;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.logic.TerrainLogic;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Computes the logic regions around a position ahead of time, nearest first, on a thread of its own, so that chunks
 * there later generate without waiting for them. Eroded regions go to the world's cache directory as usual, which is
 * what makes this outlast the memory cache. One run at a time per server process.
 */
final class RegionPrecompute implements Runnable {
    static final int MAX_RADIUS = 5;
    /** Reports progress about this often, as a share of all regions. */
    private static final int REPORTS = 4;
    private static final AtomicReference<RegionPrecompute> RUNNING = new AtomicReference<>();

    private final CommandSourceStack source;
    private final MinecraftServer server;
    private final TerrainLogic logic;
    private final List<int[]> regions;
    private final int radius;
    private volatile boolean cancelled;

    private RegionPrecompute(CommandSourceStack source, TerrainLogic logic, int centerX, int centerZ, int radius) {
        this.source = source;
        this.server = source.getServer();
        this.logic = logic;
        this.radius = radius;
        this.regions = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                regions.add(new int[]{centerX + dx, centerZ + dz, dx * dx + dz * dz});
            }
        }
        regions.sort(Comparator.comparingInt(region -> region[2]));
    }

    /** Starts a run around a block position, unless one is already going. */
    static boolean start(CommandSourceStack source, TerrainLogic logic, double x, double z, int radius) {
        int size = logic.settings().general.regionSize;
        RegionPrecompute run = new RegionPrecompute(source, logic, Math.floorDiv((int) Math.floor(x), size),
                Math.floorDiv((int) Math.floor(z), size), radius);
        if (!RUNNING.compareAndSet(null, run)) {
            return false;
        }
        Thread thread = new Thread(run, "MTM precompute");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        return true;
    }

    /** Asks the running run to stop after the region it is on. */
    static boolean cancel() {
        RegionPrecompute run = RUNNING.get();
        if (run == null) {
            return false;
        }
        run.cancelled = true;
        return true;
    }

    @Override
    public void run() {
        long start = System.nanoTime();
        int done = 0;
        try {
            report(Component.translatable("commands.mtm_worldgen.precompute.started", regions.size(), radius));
            int nextReport = 1;
            for (int[] region : regions) {
                if (cancelled) {
                    report(Component.translatable("commands.mtm_worldgen.precompute.cancelled", done, regions.size()));
                    return;
                }
                logic.precomputeRegion(region[0], region[1]);
                done++;
                if (done < regions.size() && done * REPORTS >= nextReport * regions.size()) {
                    nextReport = done * REPORTS / regions.size() + 1;
                    report(Component.translatable("commands.mtm_worldgen.precompute.progress", done, regions.size()));
                }
            }
            String seconds = String.format(Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e9);
            MtmWorldgen.LOGGER.info("Precomputed {} logic regions in {} s", done, seconds);
            report(Component.translatable("commands.mtm_worldgen.precompute.done", done, seconds));
        } catch (RuntimeException e) {
            MtmWorldgen.LOGGER.error("Precompute failed", e);
            String message = String.valueOf(e.getMessage());
            server.execute(() -> source.sendFailure(Component.translatable("commands.mtm_worldgen.precompute.failed", message)));
        } finally {
            RUNNING.compareAndSet(this, null);
        }
    }

    private void report(Component message) {
        server.execute(() -> source.sendSuccess(() -> message, false));
    }
}
