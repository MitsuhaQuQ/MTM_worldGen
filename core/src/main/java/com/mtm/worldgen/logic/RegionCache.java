package com.mtm.worldgen.logic;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache of per-region results, dropping the least recently used. Each region is computed exactly once
 * even when several worldgen threads request it at the same time: the first caller computes, the others wait on its
 * future.
 * <p>
 * Lookups of regions already there take no lock: every column samples several caches, from many threads at once, and
 * a single lock had them queue up behind each other. Use is tracked by a clock that only ticks when a region is added,
 * which is enough to tell old regions from recent ones without every lookup writing to shared memory.
 */
final class RegionCache<T> {
    interface Generator<T> {
        T generate(int regionX, int regionZ);
    }

    private static final class Entry<T> {
        final CompletableFuture<T> future = new CompletableFuture<>();
        volatile long used;
    }

    private final Generator<T> generator;
    private final int capacity;
    private final Object lock = new Object();
    private final ConcurrentHashMap<Long, Entry<T>> entries;
    /** Regions held in the cache however old they get, with how many holders each has. Guarded by the lock. */
    private final Map<Long, Integer> pins = new HashMap<>();
    private volatile long clock;

    RegionCache(Generator<T> generator, int capacity) {
        this.generator = generator;
        this.capacity = capacity;
        this.entries = new ConcurrentHashMap<>(capacity * 2);
    }

    /** Forgets everything computed so far. */
    void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }

    /**
     * Computes a region if needed and keeps it in the cache until {@link #release}. A computation spread over the logic
     * threads holds what its tasks read: a region dropped meanwhile would be computed again inside one of those tasks,
     * where it waits on the same threads it is running on.
     */
    T hold(int regionX, int regionZ) {
        long key = key(regionX, regionZ);
        synchronized (lock) {
            pins.merge(key, 1, Integer::sum);
        }
        return get(regionX, regionZ);
    }

    /** Lets a region held with {@link #hold} go again. */
    void release(int regionX, int regionZ) {
        long key = key(regionX, regionZ);
        synchronized (lock) {
            pins.computeIfPresent(key, (k, count) -> count > 1 ? count - 1 : null);
        }
    }

    private static long key(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    T get(int regionX, int regionZ) {
        long key = key(regionX, regionZ);
        Entry<T> entry = entries.get(key);
        if (entry != null) {
            touch(entry);
            return entry.future.join();
        }
        boolean owner = false;
        synchronized (lock) {
            entry = entries.get(key);
            if (entry == null) {
                entry = new Entry<>();
                entry.used = ++clock;
                entries.put(key, entry);
                owner = true;
                evict();
            }
        }
        if (!owner) {
            touch(entry);
            return entry.future.join();
        }
        try {
            entry.future.complete(generator.generate(regionX, regionZ));
        } catch (Throwable t) {
            synchronized (lock) {
                entries.remove(key, entry);
            }
            entry.future.completeExceptionally(t);
        }
        return entry.future.join();
    }

    private void touch(Entry<T> entry) {
        long now = clock;
        if (entry.used != now) {
            entry.used = now;
        }
    }

    /** Drops the least recently used region nobody holds, once there are too many. Held ones stay over the capacity for a while. */
    private void evict() {
        if (entries.size() <= capacity) {
            return;
        }
        Long oldest = null;
        long oldestUse = Long.MAX_VALUE;
        for (Map.Entry<Long, Entry<T>> candidate : entries.entrySet()) {
            if (candidate.getValue().used < oldestUse && !pins.containsKey(candidate.getKey())) {
                oldest = candidate.getKey();
                oldestUse = candidate.getValue().used;
            }
        }
        if (oldest != null) {
            entries.remove(oldest);
        }
    }
}
