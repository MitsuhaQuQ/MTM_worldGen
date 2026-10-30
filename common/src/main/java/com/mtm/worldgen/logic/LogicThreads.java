package com.mtm.worldgen.logic;

import java.util.concurrent.ForkJoinPool;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * Worker pool for splitting expensive logic computations. Only pure functions of seed and coordinates run on it, so
 * the number of threads and their timing cannot change any result. A fork-join pool is used because tasks may wait
 * for each other through the region caches, and nested tasks are helped along rather than starving the pool.
 */
final class LogicThreads {
    private static final ForkJoinPool POOL = new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            pool -> {
                var thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                thread.setName("MTM logic " + thread.getPoolIndex());
                thread.setDaemon(true);
                return thread;
            }, null, false);

    private LogicThreads() {
    }

    /** Runs a task for every index below count and returns when all are done. */
    static void forEach(int count, IntConsumer task) {
        POOL.submit(() -> IntStream.range(0, count).parallel().forEach(task)).join();
    }
}
