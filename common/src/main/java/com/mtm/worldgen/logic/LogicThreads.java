package com.mtm.worldgen.logic;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * Worker pool for splitting expensive logic computations. Only pure functions of seed and coordinates run on it, so
 * the number of threads and their timing cannot change any result. A fork-join pool is used because tasks may wait
 * for each other through the region caches, and nested tasks are helped along rather than starving the pool.
 */
public final class LogicThreads {
    private static final ForkJoinPool POOL = new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            pool -> {
                var thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                thread.setName("MTM logic " + thread.getPoolIndex());
                thread.setDaemon(true);
                return thread;
            }, null, false);

    private LogicThreads() {
    }

    /**
     * Runs a task for every index below count and returns when all are done. The caller blocks without helping: a
     * fork-join worker of another pool (Minecraft's own worldgen threads, say) would otherwise run its pool's other
     * tasks while it waits, and one of those may need the very region the caller is computing.
     */
    public static void forEach(int count, IntConsumer task) {
        // On one of these threads already, the task runs right here. Waiting on the pool from inside it would let this
        // thread pick up other queued work while it waits, and that work can need the very region this thread is
        // computing: it then waits on itself, and the pool spawns thread after thread to make up for it
        if (Thread.currentThread() instanceof ForkJoinWorkerThread worker && worker.getPool() == POOL) {
            for (int i = 0; i < count; i++) {
                task.accept(i);
            }
            return;
        }
        CompletableFuture.runAsync(() -> IntStream.range(0, count).parallel().forEach(task), POOL).join();
    }
}
