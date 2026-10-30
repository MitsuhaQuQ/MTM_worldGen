package com.mtm.worldgen.logic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Thread-safe LRU cache of per-region results. Each region is computed exactly once even when several
 * worldgen threads request it at the same time: the first caller computes, the others wait on its future.
 */
final class RegionCache<T> {
    interface Generator<T> {
        T generate(int regionX, int regionZ);
    }

    private final Generator<T> generator;
    private final Object lock = new Object();
    private final LinkedHashMap<Long, CompletableFuture<T>> entries;

    RegionCache(Generator<T> generator, int capacity) {
        this.generator = generator;
        this.entries = new LinkedHashMap<>(capacity * 2, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<T>> eldest) {
                return size() > capacity;
            }
        };
    }

    T get(int regionX, int regionZ) {
        long key = ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
        CompletableFuture<T> future;
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
