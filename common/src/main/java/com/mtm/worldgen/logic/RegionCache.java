package com.mtm.worldgen.logic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Thread-safe LRU cache of logic regions. Each region is computed exactly once even when several
 * worldgen threads request it at the same time: the first caller computes, the others wait on its future.
 */
final class RegionCache {
    interface Generator {
        LogicRegion generate(int regionX, int regionZ);
    }

    private final Generator generator;
    private final Object lock = new Object();
    private final LinkedHashMap<Long, CompletableFuture<LogicRegion>> entries;

    RegionCache(Generator generator, int capacity) {
        this.generator = generator;
        this.entries = new LinkedHashMap<>(capacity * 2, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<LogicRegion>> eldest) {
                return size() > capacity;
            }
        };
    }

    LogicRegion get(int regionX, int regionZ) {
        long key = ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
        CompletableFuture<LogicRegion> future;
        boolean owner = false;
        synchronized (lock) {
            future = entries.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                entries.put(key, future);
                owner = true;
            }
        }
        if (owner) {
            try {
                future.complete(generator.generate(regionX, regionZ));
            } catch (Throwable t) {
                synchronized (lock) {
                    entries.remove(key, future);
                }
                future.completeExceptionally(t);
            }
        }
        return future.join();
    }
}
