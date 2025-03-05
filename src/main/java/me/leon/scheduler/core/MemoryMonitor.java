package me.leon.scheduler.core;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.plugin.Plugin;

import me.leon.scheduler.util.Debug;

/**
 * Monitors memory usage to optimize scheduling decisions.
 * Tracks heap usage and provides methods to check memory status.
 */
public final class MemoryMonitor {

    private static final int DEFAULT_CHECK_INTERVAL_SECONDS = 10;
    private static final double HIGH_MEMORY_THRESHOLD = 0.85; // 85% usage is high
    private static final double CRITICAL_MEMORY_THRESHOLD = 0.95; // 95% usage is critical

    private final AtomicReference<MemoryStats> currentStats;
    private final MemoryMXBean memoryBean;
    private final ScheduledExecutorService executor;
    private volatile boolean running;

    /**
     * Creates a new memory monitor.
     *
     * @param executor The executor service to use for monitoring tasks
     */
    public MemoryMonitor(ScheduledExecutorService executor) {
        this.executor = executor;
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.currentStats = new AtomicReference<>(new MemoryStats(0, 0, 0));
        this.running = false;
    }

    /**
     * Starts monitoring memory usage.
     */
    public void start() {
        if (running) {
            return;
        }

        running = true;
        Debug.log(java.util.logging.Level.INFO, "Starting memory monitoring");

        // Schedule periodic memory checks
        executor.scheduleAtFixedRate(
                this::checkMemory,
                1,
                DEFAULT_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    /**
     * Stops monitoring memory usage.
     */
    public void stop() {
        running = false;
    }

    /**
     * Checks current memory usage and updates stats.
     */
    private void checkMemory() {
        if (!running) {
            return;
        }

        try {
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();

            long used = heapUsage.getUsed();
            long max = heapUsage.getMax();
            double usageRatio = (double) used / max;

            MemoryStats stats = new MemoryStats(used, max, usageRatio);
            currentStats.set(stats);

            if (Debug.isDebugEnabled() && Math.random() < 0.1) { // Log occasionally
                Debug.debug(String.format("Memory usage: %.1f%% (used: %d MB, max: %d MB)",
                        usageRatio * 100, used / (1024 * 1024), max / (1024 * 1024)));
            }

            // Suggest GC if memory is critically high
            if (usageRatio > CRITICAL_MEMORY_THRESHOLD) {
                Debug.log(java.util.logging.Level.WARNING,
                        String.format("Critical memory usage: %.1f%%. Suggesting garbage collection.",
                                usageRatio * 100));

                // Request garbage collection
                System.gc();
            }
        } catch (Exception e) {
            Debug.log(java.util.logging.Level.WARNING, "Error checking memory: " + e.getMessage());
        }
    }

    /**
     * Gets the current memory usage as a ratio between 0.0 and 1.0.
     *
     * @return Memory usage ratio
     */
    public double getMemoryUsage() {
        return currentStats.get().usageRatio;
    }

    /**
     * Checks if memory usage is high.
     *
     * @return true if memory usage is above the high threshold
     */
    public boolean isMemoryHigh() {
        return currentStats.get().usageRatio >= HIGH_MEMORY_THRESHOLD;
    }

    /**
     * Checks if memory usage is critical.
     *
     * @return true if memory usage is above the critical threshold
     */
    public boolean isMemoryCritical() {
        return currentStats.get().usageRatio >= CRITICAL_MEMORY_THRESHOLD;
    }

    /**
     * Gets detailed memory statistics.
     *
     * @return Current memory statistics
     */
    public MemoryStats getStats() {
        return currentStats.get();
    }

    /**
     * Performs an immediate memory check and updates stats.
     *
     * @return Updated memory statistics
     */
    public MemoryStats checkNow() {
        checkMemory();
        return currentStats.get();
    }

    /**
     * Data class containing memory statistics.
     */
    public static class MemoryStats {
        private final long usedBytes;
        private final long maxBytes;
        private final double usageRatio;

        /**
         * Creates a new memory stats instance.
         *
         * @param usedBytes Memory used in bytes
         * @param maxBytes Maximum memory in bytes
         * @param usageRatio Usage ratio (0.0-1.0)
         */
        public MemoryStats(long usedBytes, long maxBytes, double usageRatio) {
            this.usedBytes = usedBytes;
            this.maxBytes = maxBytes;
            this.usageRatio = usageRatio;
        }

        /**
         * Gets the amount of used memory in bytes.
         *
         * @return Used memory in bytes
         */
        public long getUsedBytes() {
            return usedBytes;
        }

        /**
         * Gets the maximum available memory in bytes.
         *
         * @return Maximum memory in bytes
         */
        public long getMaxBytes() {
            return maxBytes;
        }

        /**
         * Gets the memory usage ratio.
         *
         * @return Memory usage ratio (0.0-1.0)
         */
        public double getUsageRatio() {
            return usageRatio;
        }

        /**
         * Gets the free memory in bytes.
         *
         * @return Free memory in bytes
         */
        public long getFreeBytes() {
            return maxBytes - usedBytes;
        }

        /**
         * Gets the memory usage percentage.
         *
         * @return Memory usage percentage (0-100)
         */
        public double getUsagePercentage() {
            return usageRatio * 100.0;
        }
    }
}