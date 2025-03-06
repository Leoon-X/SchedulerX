package me.leon.scheduler.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import me.leon.scheduler.util.Debug;

/**
 * Manages thread pools for different types of tasks to optimize performance.
 * Provides specialized pools for various workloads.
 */
public final class ThreadPoolManager {

    // Pool types for different workloads
    public enum PoolType {
        IO_BOUND,       // For I/O operations like file access, network
        CPU_BOUND,      // For computation-intensive tasks
        SCHEDULED,      // For tasks that need to run at specific times
        LOW_LATENCY     // For tasks that need minimal startup delay
    }

    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();

    private final Map<PoolType, ExecutorService> pools;
    private final ScheduledExecutorService scheduledPool;

    // Track initial pool sizes for resizing
    private final Map<PoolType, Integer> poolSizes;

    /**
     * Creates a new ThreadPoolManager with optimized pools.
     */
    public ThreadPoolManager() {
        pools = new ConcurrentHashMap<>();
        poolSizes = new ConcurrentHashMap<>();

        // Initialize pools with appropriate configurations

        // I/O-bound pool: More threads since they spend time waiting
        int ioBoundSize = CPU_CORES * 4;
        pools.put(PoolType.IO_BOUND, createIoBoundPool());
        poolSizes.put(PoolType.IO_BOUND, ioBoundSize / 2); // Initial core pool size

        // CPU-bound pool: Limited to CPU core count to avoid oversubscription
        int cpuBoundSize = CPU_CORES;
        pools.put(PoolType.CPU_BOUND, createCpuBoundPool());
        poolSizes.put(PoolType.CPU_BOUND, cpuBoundSize);

        // Low-latency pool: Thread-per-task to minimize startup delay
        pools.put(PoolType.LOW_LATENCY, createLowLatencyPool());
        poolSizes.put(PoolType.LOW_LATENCY, 0); // Dynamic size for cached pool

        // Scheduled pool for timed tasks
        int scheduledSize = Math.max(2, CPU_CORES / 2);
        this.scheduledPool = createScheduledPool();
        pools.put(PoolType.SCHEDULED, scheduledPool);
        poolSizes.put(PoolType.SCHEDULED, scheduledSize);

        // Start monitoring thread
        startMonitoring();
    }

    /**
     * Gets the appropriate executor for the given pool type.
     *
     * @param type The type of pool to use
     * @return The corresponding executor service
     */
    public ExecutorService getPool(PoolType type) {
        return pools.get(type);
    }

    /**
     * Gets the scheduled executor service for timed tasks.
     *
     * @return The scheduled executor service
     */
    public ScheduledExecutorService getScheduledPool() {
        return scheduledPool;
    }

    /**
     * Gets the current size of a thread pool.
     *
     * @param type The pool type
     * @return The current pool size, or -1 if unknown
     */
    public int getPoolSize(PoolType type) {
        ExecutorService pool = pools.get(type);
        if (pool instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) pool).getPoolSize();
        } else {
            return poolSizes.getOrDefault(type, -1);
        }
    }

    /**
     * Resizes a thread pool.
     *
     * @param type The pool type to resize
     * @param newSize The new core pool size
     * @return true if resized successfully, false otherwise
     */
    public boolean resizePool(PoolType type, int newSize) {
        ExecutorService pool = pools.get(type);
        if (pool instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) pool;

            // Store the new size
            poolSizes.put(type, newSize);

            // Adjust core pool size - this affects the minimum number of threads kept alive
            executor.setCorePoolSize(newSize);

            // If it's an I/O pool, we might want to adjust max size too
            if (type == PoolType.IO_BOUND) {
                executor.setMaximumPoolSize(Math.max(newSize, newSize * 2)); // Allow 2x growth
            }

            Debug.debug("Resized " + type + " pool to " + newSize + " threads");
            return true;
        }
        return false;
    }

    /**
     * Creates an optimized pool for I/O-bound operations.
     *
     * @return An executor service optimized for I/O operations
     */
    private ExecutorService createIoBoundPool() {
        int poolSize = CPU_CORES * 4; // More threads for I/O operations

        ThreadFactory threadFactory = createThreadFactory("IO-Pool");

        return new ThreadPoolExecutor(
                poolSize / 2,                    // Core pool size
                poolSize,                        // Maximum pool size
                60L, TimeUnit.SECONDS,          // Keep-alive time
                new LinkedBlockingQueue<>(1000), // Work queue with bounded capacity
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy() // Prevents task rejection
        );
    }

    /**
     * Creates an optimized pool for CPU-bound operations.
     *
     * @return An executor service optimized for CPU-intensive operations
     */
    private ExecutorService createCpuBoundPool() {
        int poolSize = CPU_CORES; // Limit to available cores

        ThreadFactory threadFactory = createThreadFactory("CPU-Pool");

        return new ThreadPoolExecutor(
                poolSize,                       // Core pool size
                poolSize,                       // Maximum pool size
                60L, TimeUnit.SECONDS,         // Keep-alive time
                new LinkedBlockingQueue<>(500), // Work queue
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Creates a low-latency pool for tasks that need minimal startup delay.
     *
     * @return An executor service optimized for low latency
     */
    private ExecutorService createLowLatencyPool() {
        ThreadFactory threadFactory = createThreadFactory("Fast-Pool");

        return Executors.newCachedThreadPool(threadFactory);
    }

    /**
     * Creates a scheduled executor for timed tasks.
     *
     * @return A scheduled executor service
     */
    private ScheduledExecutorService createScheduledPool() {
        int poolSize = Math.max(2, CPU_CORES / 2); // At least 2 threads

        ThreadFactory threadFactory = createThreadFactory("Schedule-Pool");

        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(poolSize, threadFactory);
        executor.setRemoveOnCancelPolicy(true); // Reduce memory pressure
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

        return executor;
    }

    /**
     * Creates a thread factory with the specified name prefix.
     *
     * @param namePrefix The prefix for thread names
     * @return A thread factory
     */
    private ThreadFactory createThreadFactory(String namePrefix) {
        AtomicInteger counter = new AtomicInteger(1);

        return r -> {
            Thread thread = new Thread(r, namePrefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);

            // Set to low priority to avoid impacting game performance
            thread.setPriority(Thread.NORM_PRIORITY - 1);

            // Add uncaught exception handler
            thread.setUncaughtExceptionHandler((t, e) -> {
                Debug.log(java.util.logging.Level.SEVERE,
                        "Uncaught exception in " + t.getName() + ": " + e.getMessage());
            });

            return thread;
        };
    }

    /**
     * Starts a monitoring thread to track pool performance.
     */
    private void startMonitoring() {
        scheduledPool.scheduleAtFixedRate(() -> {
            if (!Debug.isDebugEnabled()) {
                return;
            }

            for (Map.Entry<PoolType, ExecutorService> entry : pools.entrySet()) {
                PoolType type = entry.getKey();
                ExecutorService pool = entry.getValue();

                if (pool instanceof ThreadPoolExecutor) {
                    ThreadPoolExecutor executor = (ThreadPoolExecutor) pool;
                    Debug.debug(type + " pool stats: active=" + executor.getActiveCount() +
                            ", poolSize=" + executor.getPoolSize() +
                            ", queueSize=" + executor.getQueue().size());
                }
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Shuts down all thread pools gracefully.
     */
    public void shutdown() {
        Debug.log(java.util.logging.Level.INFO, "Shutting down thread pools");

        for (ExecutorService pool : pools.values()) {
            pool.shutdown();
        }

        try {
            // Wait for tasks to terminate
            for (ExecutorService pool : pools.values()) {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            // Restore interrupted status
            Thread.currentThread().interrupt();

            // Force shutdown
            for (ExecutorService pool : pools.values()) {
                pool.shutdownNow();
            }
        }
    }
}