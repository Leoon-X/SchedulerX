package me.leon.scheduler.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.plugin.Plugin;

import me.leon.scheduler.util.Debug;

/**
 * Dynamically manages and scales thread pools based on server load conditions.
 * Provides intelligent work distribution across pools and automatic scaling.
 */
public class ThreadPoolOrchestrator {

    private final Plugin plugin;
    private final ThreadPoolManager poolManager;
    private final TPSMonitor tpsMonitor;
    private final MemoryMonitor memoryMonitor;

    // Track utilization statistics for each pool
    private final Map<ThreadPoolManager.PoolType, PoolStats> poolStats;

    // Scaling parameters
    private int minCpuThreads = 2;
    private int maxCpuThreads = Runtime.getRuntime().availableProcessors() * 2;
    private int minIoThreads = 4;
    private int maxIoThreads = 32;
    private double scalingThreshold = 0.7; // Scale up when pool utilization exceeds this value
    private long scalingCooldown = 30000; // Milliseconds to wait between scaling operations

    // Last scaling operation timestamp
    private final AtomicLong lastScalingTime = new AtomicLong(0);

    /**
     * Creates a new thread pool orchestrator.
     *
     * @param plugin The owning plugin
     * @param poolManager The thread pool manager to orchestrate
     * @param tpsMonitor The TPS monitor for server performance tracking
     * @param memoryMonitor The memory monitor for heap usage tracking
     */
    public ThreadPoolOrchestrator(Plugin plugin, ThreadPoolManager poolManager,
                                  TPSMonitor tpsMonitor, MemoryMonitor memoryMonitor) {
        this.plugin = plugin;
        this.poolManager = poolManager;
        this.tpsMonitor = tpsMonitor;
        this.memoryMonitor = memoryMonitor;
        this.poolStats = new ConcurrentHashMap<>();

        // Initialize stats for all pool types
        for (ThreadPoolManager.PoolType type : ThreadPoolManager.PoolType.values()) {
            poolStats.put(type, new PoolStats());
        }
    }

    /**
     * Gets the most appropriate executor for a task based on current server conditions
     * and task characteristics.
     *
     * @param isCpuIntensive Whether the task is CPU-intensive
     * @param isIoIntensive Whether the task is I/O-intensive
     * @param requiresLowLatency Whether the task requires low latency
     * @return The best executor for the task
     */
    public ExecutorService getOptimalExecutor(boolean isCpuIntensive, boolean isIoIntensive,
                                              boolean requiresLowLatency) {
        ThreadPoolManager.PoolType selectedType;

        // First determine the basic pool type based on task characteristics
        if (requiresLowLatency) {
            selectedType = ThreadPoolManager.PoolType.LOW_LATENCY;
        } else if (isCpuIntensive) {
            selectedType = ThreadPoolManager.PoolType.CPU_BOUND;
        } else if (isIoIntensive) {
            selectedType = ThreadPoolManager.PoolType.IO_BOUND;
        } else {
            // For general tasks, select based on current pool utilization
            selectedType = getLeastUtilizedPool(ThreadPoolManager.PoolType.IO_BOUND,
                    ThreadPoolManager.PoolType.CPU_BOUND);
        }

        // Adjust selection based on server conditions
        if (tpsMonitor != null && isCpuIntensive) {
            double tps = tpsMonitor.getCurrentTps();

            // If server is under heavy load, avoid using CPU-bound pool for CPU-intensive tasks
            if (tps < 15.0 && selectedType == ThreadPoolManager.PoolType.CPU_BOUND) {
                selectedType = ThreadPoolManager.PoolType.IO_BOUND;
                Debug.debug("Redirecting CPU-intensive task to IO pool due to low TPS: " + tps);
            }
        }

        if (memoryMonitor != null && memoryMonitor.isMemoryHigh()) {
            // Under high memory conditions, favor IO pool which is often less memory-intensive
            if (selectedType == ThreadPoolManager.PoolType.CPU_BOUND) {
                selectedType = ThreadPoolManager.PoolType.IO_BOUND;
                Debug.debug("Redirecting task to IO pool due to high memory usage");
            }
        }

        // Record the task assignment for statistics
        poolStats.get(selectedType).recordTaskSubmitted();

        return poolManager.getPool(selectedType);
    }

    /**
     * Updates pool statistics when a task completes execution.
     *
     * @param poolType The pool type where the task executed
     * @param executionTimeNanos The task execution time in nanoseconds
     */
    public void recordTaskCompletion(ThreadPoolManager.PoolType poolType, long executionTimeNanos) {
        PoolStats stats = poolStats.get(poolType);
        if (stats != null) {
            stats.recordTaskCompleted(executionTimeNanos);
        }
    }

    /**
     * Evaluates current conditions and adjusts thread pool sizes if needed.
     * This should be called periodically to adapt to changing server conditions.
     */
    public void adjustPoolSizes() {
        long now = System.currentTimeMillis();

        // Enforce cooldown period between scaling operations
        if (now - lastScalingTime.get() < scalingCooldown) {
            return;
        }

        boolean scaled = false;

        // Check CPU-bound pool utilization
        PoolStats cpuStats = poolStats.get(ThreadPoolManager.PoolType.CPU_BOUND);
        if (cpuStats.getUtilization() > scalingThreshold) {
            // High utilization, consider scaling up if TPS is healthy
            if (tpsMonitor == null || tpsMonitor.getCurrentTps() >= 18.0) {
                int currentSize = poolManager.getPoolSize(ThreadPoolManager.PoolType.CPU_BOUND);
                int newSize = Math.min(maxCpuThreads, currentSize + 2);

                if (newSize > currentSize) {
                    poolManager.resizePool(ThreadPoolManager.PoolType.CPU_BOUND, newSize);
                    Debug.log(java.util.logging.Level.INFO,
                            "Scaled up CPU pool from " + currentSize + " to " + newSize +
                                    " threads (utilization: " + cpuStats.getUtilization() + ")");
                    scaled = true;
                }
            }
        } else if (cpuStats.getUtilization() < 0.3) {
            // Low utilization, consider scaling down
            int currentSize = poolManager.getPoolSize(ThreadPoolManager.PoolType.CPU_BOUND);
            int newSize = Math.max(minCpuThreads, currentSize - 1);

            if (newSize < currentSize) {
                poolManager.resizePool(ThreadPoolManager.PoolType.CPU_BOUND, newSize);
                Debug.log(java.util.logging.Level.INFO,
                        "Scaled down CPU pool from " + currentSize + " to " + newSize +
                                " threads (utilization: " + cpuStats.getUtilization() + ")");
                scaled = true;
            }
        }

        // Check I/O-bound pool utilization
        PoolStats ioStats = poolStats.get(ThreadPoolManager.PoolType.IO_BOUND);
        if (ioStats.getUtilization() > scalingThreshold) {
            int currentSize = poolManager.getPoolSize(ThreadPoolManager.PoolType.IO_BOUND);
            int newSize = Math.min(maxIoThreads, currentSize + 4);

            if (newSize > currentSize) {
                poolManager.resizePool(ThreadPoolManager.PoolType.IO_BOUND, newSize);
                Debug.log(java.util.logging.Level.INFO,
                        "Scaled up IO pool from " + currentSize + " to " + newSize +
                                " threads (utilization: " + ioStats.getUtilization() + ")");
                scaled = true;
            }
        } else if (ioStats.getUtilization() < 0.3) {
            int currentSize = poolManager.getPoolSize(ThreadPoolManager.PoolType.IO_BOUND);
            int newSize = Math.max(minIoThreads, currentSize - 2);

            if (newSize < currentSize) {
                poolManager.resizePool(ThreadPoolManager.PoolType.IO_BOUND, newSize);
                Debug.log(java.util.logging.Level.INFO,
                        "Scaled down IO pool from " + currentSize + " to " + newSize +
                                " threads (utilization: " + ioStats.getUtilization() + ")");
                scaled = true;
            }
        }

        if (scaled) {
            lastScalingTime.set(now);
        }
    }

    /**
     * Gets the least utilized pool between two options.
     *
     * @param option1 First pool option
     * @param option2 Second pool option
     * @return The pool type with lower utilization
     */
    private ThreadPoolManager.PoolType getLeastUtilizedPool(ThreadPoolManager.PoolType option1,
                                                            ThreadPoolManager.PoolType option2) {
        PoolStats stats1 = poolStats.get(option1);
        PoolStats stats2 = poolStats.get(option2);

        return stats1.getUtilization() <= stats2.getUtilization() ? option1 : option2;
    }

    /**
     * Resets statistics for all pools.
     */
    public void resetStats() {
        for (PoolStats stats : poolStats.values()) {
            stats.reset();
        }
    }

    /**
     * Configures thread pool scaling parameters.
     *
     * @param minCpuThreads Minimum number of CPU-bound threads
     * @param maxCpuThreads Maximum number of CPU-bound threads
     * @param minIoThreads Minimum number of IO-bound threads
     * @param maxIoThreads Maximum number of IO-bound threads
     * @param scalingThreshold Utilization threshold for scaling (0.0-1.0)
     * @param scalingCooldownMs Milliseconds to wait between scaling operations
     */
    public void configureScaling(int minCpuThreads, int maxCpuThreads, int minIoThreads, int maxIoThreads,
                                 double scalingThreshold, long scalingCooldownMs) {
        this.minCpuThreads = minCpuThreads;
        this.maxCpuThreads = maxCpuThreads;
        this.minIoThreads = minIoThreads;
        this.maxIoThreads = maxIoThreads;
        this.scalingThreshold = scalingThreshold;
        this.scalingCooldown = scalingCooldownMs;
    }

    /**
     * Inner class for tracking pool statistics.
     */
    private static class PoolStats {
        private final AtomicInteger activeTasks = new AtomicInteger(0);
        private final AtomicInteger completedTasks = new AtomicInteger(0);
        private final AtomicLong totalExecutionTimeNanos = new AtomicLong(0);
        private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());

        // Exponential moving average of task execution rate
        private double avgTasksPerSecond = 0.0;
        private double avgExecutionTimeNanos = 0.0;
        private double utilization = 0.0;

        // Weight for exponential moving average (lower = more smoothing)
        private static final double EMA_WEIGHT = 0.3;

        /**
         * Records that a task has been submitted to the pool.
         */
        public void recordTaskSubmitted() {
            activeTasks.incrementAndGet();
            updateUtilization();
        }

        /**
         * Records that a task has completed execution.
         *
         * @param executionTimeNanos The execution time in nanoseconds
         */
        public void recordTaskCompleted(long executionTimeNanos) {
            activeTasks.decrementAndGet();
            completedTasks.incrementAndGet();
            totalExecutionTimeNanos.addAndGet(executionTimeNanos);

            // Update exponential moving average of execution time
            if (avgExecutionTimeNanos == 0) {
                avgExecutionTimeNanos = executionTimeNanos;
            } else {
                avgExecutionTimeNanos = (1 - EMA_WEIGHT) * avgExecutionTimeNanos +
                        EMA_WEIGHT * executionTimeNanos;
            }

            updateUtilization();
        }

        /**
         * Updates the pool utilization metrics.
         */
        private void updateUtilization() {
            long now = System.currentTimeMillis();
            long windowMs = now - windowStartTime.get();

            if (windowMs >= 1000) { // Update metrics every second
                // Calculate tasks per second
                double tps = 1000.0 * completedTasks.get() / windowMs;

                // Update exponential moving average
                if (avgTasksPerSecond == 0) {
                    avgTasksPerSecond = tps;
                } else {
                    avgTasksPerSecond = (1 - EMA_WEIGHT) * avgTasksPerSecond + EMA_WEIGHT * tps;
                }

                // Estimate utilization based on active tasks and completion rate
                double estimatedUtilization = activeTasks.get() > 0 ?
                        Math.min(1.0, (activeTasks.get() / (avgTasksPerSecond + 1.0))) : 0.0;

                // Update exponential moving average of utilization
                utilization = (1 - EMA_WEIGHT) * utilization + EMA_WEIGHT * estimatedUtilization;

                // Reset window
                completedTasks.set(0);
                windowStartTime.set(now);
            }
        }

        /**
         * Gets the current pool utilization (0.0-1.0).
         *
         * @return The pool utilization
         */
        public double getUtilization() {
            updateUtilization();
            return utilization;
        }

        /**
         * Gets the average task execution time in nanoseconds.
         *
         * @return The average execution time
         */
        public double getAvgExecutionTimeNanos() {
            return avgExecutionTimeNanos;
        }

        /**
         * Gets the current number of active tasks.
         *
         * @return The number of active tasks
         */
        public int getActiveTasks() {
            return activeTasks.get();
        }

        /**
         * Resets all statistics.
         */
        public void reset() {
            activeTasks.set(0);
            completedTasks.set(0);
            totalExecutionTimeNanos.set(0);
            windowStartTime.set(System.currentTimeMillis());
            avgTasksPerSecond = 0.0;
            avgExecutionTimeNanos = 0.0;
            utilization = 0.0;
        }
    }
}