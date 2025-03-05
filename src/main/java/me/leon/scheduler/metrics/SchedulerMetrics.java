package me.leon.scheduler.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.bukkit.plugin.Plugin;

import me.leon.scheduler.util.Debug;

/**
 * Collects and maintains metrics about scheduler performance.
 * Provides methods to access and analyze performance statistics.
 */
public class SchedulerMetrics {

    private final Plugin plugin;
    private final Map<String, CategoryMetrics> categorizedMetrics;
    private final AtomicLong totalTasksExecuted;
    private final AtomicLong totalExecutionTimeNanos;
    private final AtomicInteger activeTaskCount;
    private final AtomicInteger peakTaskCount;
    private final AtomicLong lastResetTime;

    private int metricsRetentionMinutes = 60; // Store detailed metrics for 1 hour by default
    private boolean detailedTimingEnabled = true;

    /**
     * Creates a new metrics collector.
     *
     * @param plugin The owning plugin
     */
    public SchedulerMetrics(Plugin plugin) {
        this.plugin = plugin;
        this.categorizedMetrics = new ConcurrentHashMap<>();
        this.totalTasksExecuted = new AtomicLong(0);
        this.totalExecutionTimeNanos = new AtomicLong(0);
        this.activeTaskCount = new AtomicInteger(0);
        this.peakTaskCount = new AtomicInteger(0);
        this.lastResetTime = new AtomicLong(System.currentTimeMillis());
    }

    /**
     * Records the start of a task execution.
     *
     * @param category The category of the task
     * @return A completion callback that should be called when the task completes
     */
    public Consumer<Long> taskStarted(String category) {
        activeTaskCount.incrementAndGet();
        updatePeakTaskCount();

        if (!detailedTimingEnabled || category == null) {
            return null; // Skip detailed metrics if disabled
        }

        CategoryMetrics metrics = getOrCreateCategoryMetrics(category);
        metrics.activeCount.incrementAndGet();
        metrics.updatePeakActiveCount();

        final long startTime = System.nanoTime();

        // Return a callback that will record completion metrics
        return executionTimeNanos -> {
            if (executionTimeNanos == null) {
                // If no time provided, calculate it
                recordTaskCompleted(category, System.nanoTime() - startTime);
            } else {
                recordTaskCompleted(category, executionTimeNanos);
            }
        };
    }

    /**
     * Records the completion of a task execution.
     *
     * @param category The category of the task
     * @param executionTimeNanos The execution time in nanoseconds
     */
    public void recordTaskCompleted(String category, long executionTimeNanos) {
        activeTaskCount.decrementAndGet();
        totalTasksExecuted.incrementAndGet();
        totalExecutionTimeNanos.addAndGet(executionTimeNanos);

        if (!detailedTimingEnabled || category == null) {
            return; // Skip detailed metrics if disabled
        }

        CategoryMetrics metrics = getOrCreateCategoryMetrics(category);
        metrics.activeCount.decrementAndGet();
        metrics.completedCount.incrementAndGet();
        metrics.totalExecutionTimeNanos.addAndGet(executionTimeNanos);

        // Update min/max execution times
        metrics.updateMinMaxExecutionTime(executionTimeNanos);

        // Log slow tasks for debugging
        if (Debug.isDebugEnabled() && executionTimeNanos > TimeUnit.MILLISECONDS.toNanos(100)) {
            Debug.debug(String.format("Slow task in category '%s': %.2fms",
                    category, executionTimeNanos / 1_000_000.0));
        }
    }

    /**
     * Records a task failure.
     *
     * @param category The category of the task
     * @param error The error that occurred
     */
    public void recordTaskFailed(String category, Throwable error) {
        activeTaskCount.decrementAndGet();

        if (!detailedTimingEnabled || category == null) {
            return; // Skip detailed metrics if disabled
        }

        CategoryMetrics metrics = getOrCreateCategoryMetrics(category);
        metrics.activeCount.decrementAndGet();
        metrics.failedCount.incrementAndGet();

        // Log the error
        Debug.log(java.util.logging.Level.WARNING,
                String.format("Task in category '%s' failed: %s",
                        category, error.getMessage()));
    }

    /**
     * Gets the average execution time across all tasks.
     *
     * @return The average execution time in nanoseconds, or 0 if no tasks have completed
     */
    public long getAverageExecutionTimeNanos() {
        long completed = totalTasksExecuted.get();
        return completed > 0 ? totalExecutionTimeNanos.get() / completed : 0;
    }

    /**
     * Gets the average execution time for a specific category.
     *
     * @param category The category name
     * @return The average execution time in nanoseconds, or 0 if no tasks have completed
     */
    public long getAverageExecutionTimeNanos(String category) {
        CategoryMetrics metrics = categorizedMetrics.get(category);
        if (metrics == null) {
            return 0;
        }

        long completed = metrics.completedCount.get();
        return completed > 0 ? metrics.totalExecutionTimeNanos.get() / completed : 0;
    }

    /**
     * Gets the current number of active tasks.
     *
     * @return The number of active tasks
     */
    public int getActiveTaskCount() {
        return activeTaskCount.get();
    }

    /**
     * Gets the current number of active tasks in a specific category.
     *
     * @param category The category name
     * @return The number of active tasks
     */
    public int getActiveTaskCount(String category) {
        CategoryMetrics metrics = categorizedMetrics.get(category);
        return metrics != null ? metrics.activeCount.get() : 0;
    }

    /**
     * Gets the peak number of concurrent tasks.
     *
     * @return The peak task count
     */
    public int getPeakTaskCount() {
        return peakTaskCount.get();
    }

    /**
     * Gets the total number of completed tasks.
     *
     * @return The number of completed tasks
     */
    public long getCompletedTaskCount() {
        return totalTasksExecuted.get();
    }

    /**
     * Gets the number of completed tasks in a specific category.
     *
     * @param category The category name
     * @return The number of completed tasks
     */
    public long getCompletedTaskCount(String category) {
        CategoryMetrics metrics = categorizedMetrics.get(category);
        return metrics != null ? metrics.completedCount.get() : 0;
    }

    /**
     * Gets the number of failed tasks in a specific category.
     *
     * @param category The category name
     * @return The number of failed tasks
     */
    public long getFailedTaskCount(String category) {
        CategoryMetrics metrics = categorizedMetrics.get(category);
        return metrics != null ? metrics.failedCount.get() : 0;
    }

    /**
     * Gets the minimum execution time for a specific category.
     *
     * @param category The category name
     * @return The minimum execution time in nanoseconds, or 0 if no tasks have completed
     */
    public long getMinExecutionTimeNanos(String category) {
        CategoryMetrics metrics = categorizedMetrics.get(category);
        return metrics != null ? metrics.minExecutionTimeNanos.get() : 0;
    }

    /**
     * Gets the maximum execution time for a specific category.
     *
     * @param category The category name
     * @return The maximum execution time in nanoseconds, or 0 if no tasks have completed
     */
    public long getMaxExecutionTimeNanos(String category) {
        CategoryMetrics metrics = categorizedMetrics.get(category);
        return metrics != null ? metrics.maxExecutionTimeNanos.get() : 0;
    }

    /**
     * Resets all metrics.
     */
    public void reset() {
        categorizedMetrics.clear();
        totalTasksExecuted.set(0);
        totalExecutionTimeNanos.set(0);
        activeTaskCount.set(0);
        peakTaskCount.set(0);
        lastResetTime.set(System.currentTimeMillis());
    }

    /**
     * Gets the time since the last metrics reset.
     *
     * @param unit The time unit for the result
     * @return The time since the last reset
     */
    public long getTimeSinceReset(TimeUnit unit) {
        return unit.convert(System.currentTimeMillis() - lastResetTime.get(), TimeUnit.MILLISECONDS);
    }

    /**
     * Gets a summary of metrics for all categories.
     *
     * @return A map containing category names and their metrics
     */
    public Map<String, CategoryMetrics> getCategorizedMetrics() {
        return new ConcurrentHashMap<>(categorizedMetrics);
    }

    /**
     * Gets or creates metrics for a specific category.
     *
     * @param category The category name
     * @return The metrics for the category
     */
    private CategoryMetrics getOrCreateCategoryMetrics(String category) {
        return categorizedMetrics.computeIfAbsent(category, c -> new CategoryMetrics());
    }

    /**
     * Updates the peak task count if necessary.
     */
    private void updatePeakTaskCount() {
        int current = activeTaskCount.get();
        int peak = peakTaskCount.get();

        while (current > peak) {
            if (peakTaskCount.compareAndSet(peak, current)) {
                break;
            }
            peak = peakTaskCount.get();
        }
    }

    /**
     * Sets whether detailed timing metrics are enabled.
     *
     * @param enabled true to enable detailed timing, false to disable
     */
    public void setDetailedTimingEnabled(boolean enabled) {
        this.detailedTimingEnabled = enabled;
    }

    /**
     * Sets the retention period for detailed metrics.
     *
     * @param minutes The number of minutes to retain metrics
     */
    public void setMetricsRetentionMinutes(int minutes) {
        this.metricsRetentionMinutes = minutes;
    }

    /**
     * Class for tracking metrics for a specific category.
     */
    public static class CategoryMetrics {
        private final AtomicInteger activeCount = new AtomicInteger(0);
        private final AtomicInteger peakActiveCount = new AtomicInteger(0);
        private final AtomicLong completedCount = new AtomicLong(0);
        private final AtomicLong failedCount = new AtomicLong(0);
        private final AtomicLong totalExecutionTimeNanos = new AtomicLong(0);
        private final AtomicLong minExecutionTimeNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxExecutionTimeNanos = new AtomicLong(0);

        /**
         * Updates the peak active count if necessary.
         */
        private void updatePeakActiveCount() {
            int current = activeCount.get();
            int peak = peakActiveCount.get();

            while (current > peak) {
                if (peakActiveCount.compareAndSet(peak, current)) {
                    break;
                }
                peak = peakActiveCount.get();
            }
        }

        /**
         * Updates the minimum and maximum execution times if necessary.
         *
         * @param executionTimeNanos The execution time in nanoseconds
         */
        private void updateMinMaxExecutionTime(long executionTimeNanos) {
            // Update min (if smaller than current min)
            long currentMin = minExecutionTimeNanos.get();
            while (executionTimeNanos < currentMin) {
                if (minExecutionTimeNanos.compareAndSet(currentMin, executionTimeNanos)) {
                    break;
                }
                currentMin = minExecutionTimeNanos.get();
            }

            // Update max (if larger than current max)
            long currentMax = maxExecutionTimeNanos.get();
            while (executionTimeNanos > currentMax) {
                if (maxExecutionTimeNanos.compareAndSet(currentMax, executionTimeNanos)) {
                    break;
                }
                currentMax = maxExecutionTimeNanos.get();
            }
        }

        /**
         * Gets the current number of active tasks.
         *
         * @return The number of active tasks
         */
        public int getActiveCount() {
            return activeCount.get();
        }

        /**
         * Gets the peak number of concurrent tasks.
         *
         * @return The peak task count
         */
        public int getPeakActiveCount() {
            return peakActiveCount.get();
        }

        /**
         * Gets the number of completed tasks.
         *
         * @return The number of completed tasks
         */
        public long getCompletedCount() {
            return completedCount.get();
        }

        /**
         * Gets the number of failed tasks.
         *
         * @return The number of failed tasks
         */
        public long getFailedCount() {
            return failedCount.get();
        }

        /**
         * Gets the total execution time for all tasks.
         *
         * @return The total execution time in nanoseconds
         */
        public long getTotalExecutionTimeNanos() {
            return totalExecutionTimeNanos.get();
        }

        /**
         * Gets the minimum execution time.
         *
         * @return The minimum execution time in nanoseconds
         */
        public long getMinExecutionTimeNanos() {
            return minExecutionTimeNanos.get() == Long.MAX_VALUE ? 0 : minExecutionTimeNanos.get();
        }

        /**
         * Gets the maximum execution time.
         *
         * @return The maximum execution time in nanoseconds
         */
        public long getMaxExecutionTimeNanos() {
            return maxExecutionTimeNanos.get();
        }

        /**
         * Gets the average execution time.
         *
         * @return The average execution time in nanoseconds, or 0 if no tasks have completed
         */
        public long getAverageExecutionTimeNanos() {
            long completed = completedCount.get();
            return completed > 0 ? totalExecutionTimeNanos.get() / completed : 0;
        }
    }
}