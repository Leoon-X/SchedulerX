package me.leon.scheduler.optimization;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import me.leon.scheduler.core.MemoryMonitor;
import me.leon.scheduler.core.TPSMonitor;
import me.leon.scheduler.util.Debug;
import me.leon.scheduler.util.TickUtil;

/**
 * Adapts task timing based on server performance.
 * Automatically adjusts delays and intervals to maintain server health.
 */
public class AdaptiveTiming {

    // Default scaling factors for different performance scenarios
    private static final double OPTIMAL_SCALE = 1.0;      // No scaling when server is running well
    private static final double MINOR_LAG_SCALE = 1.5;    // 50% longer delays during minor lag
    private static final double MODERATE_LAG_SCALE = 2.0; // Double delays during moderate lag
    private static final double SEVERE_LAG_SCALE = 3.0;   // Triple delays during severe lag

    private final TPSMonitor tpsMonitor;
    private final MemoryMonitor memoryMonitor;
    private final Map<String, TaskTimingInfo> taskTimings;
    private volatile double currentScaleFactor;

    // Thresholds for different performance levels
    private double optimalTpsThreshold = 19.0;
    private double minorLagTpsThreshold = 17.0;
    private double moderateLagTpsThreshold = 14.0;
    // Below moderate is considered severe

    private int highMemoryThreshold = 80; // 80% memory usage is high

    /**
     * Creates a new adaptive timing system.
     *
     * @param tpsMonitor The TPS monitor
     * @param memoryMonitor The memory monitor
     */
    public AdaptiveTiming(TPSMonitor tpsMonitor, MemoryMonitor memoryMonitor) {
        this.tpsMonitor = tpsMonitor;
        this.memoryMonitor = memoryMonitor;
        this.taskTimings = new ConcurrentHashMap<>();
        this.currentScaleFactor = OPTIMAL_SCALE;

        // Initial update
        updateScaleFactor();
    }

    /**
     * Adjusts a delay based on current server conditions.
     *
     * @param baseDelay The base delay in ticks
     * @param taskType The type of task (for tracking purposes)
     * @return The adjusted delay in ticks
     */
    public long getAdjustedDelay(long baseDelay, String taskType) {
        // Update timing info for this task type
        TaskTimingInfo timingInfo = taskTimings.computeIfAbsent(
                taskType, t -> new TaskTimingInfo(baseDelay));

        // Get current scale factor
        double scaleFactor = getCurrentScaleFactor();

        // Calculate adjusted delay
        long adjustedDelay = Math.round(baseDelay * scaleFactor);

        // Apply additional adjustment based on task timing history
        if (timingInfo.getExecutionCount() > 5) {
            long avgExecTimeMs = TimeUnit.NANOSECONDS.toMillis(timingInfo.getAverageExecutionTime());

            // If the task takes a long time to execute, increase its delay more aggressively
            if (avgExecTimeMs > 50) { // More than 50ms is considered expensive
                double expensiveTaskScale = 1.0 + (Math.min(avgExecTimeMs, 500) / 500.0);
                adjustedDelay = Math.round(adjustedDelay * expensiveTaskScale);

                if (Debug.isDebugEnabled()) {
                    Debug.debug(String.format("Task type '%s' is expensive (avg: %dms), " +
                            "additional scale: %.2f", taskType, avgExecTimeMs, expensiveTaskScale));
                }
            }
        }

        // Ensure the delay is at least 1 tick
        return Math.max(1, adjustedDelay);
    }

    /**
     * Adjusts a period (interval) based on current server conditions.
     *
     * @param basePeriod The base period in ticks
     * @param taskType The type of task (for tracking purposes)
     * @return The adjusted period in ticks
     */
    public long getAdjustedPeriod(long basePeriod, String taskType) {
        // For intervals, we use a more conservative approach than for delays
        double scaleFactor = Math.sqrt(getCurrentScaleFactor()); // Square root for less aggressive scaling

        // Calculate adjusted period
        long adjustedPeriod = Math.round(basePeriod * scaleFactor);

        // Ensure the period is at least 1 tick
        return Math.max(1, adjustedPeriod);
    }

    /**
     * Records execution time for a task type.
     *
     * @param taskType The type of task
     * @param executionTimeNanos The execution time in nanoseconds
     */
    public void recordExecutionTime(String taskType, long executionTimeNanos) {
        TaskTimingInfo timingInfo = taskTimings.computeIfAbsent(
                taskType, t -> new TaskTimingInfo(0));

        timingInfo.recordExecution(executionTimeNanos);
    }

    /**
     * Updates the scale factor based on current server conditions.
     * Call this periodically to adapt to changing conditions.
     */
    public void updateScaleFactor() {
        double scaleFactor = OPTIMAL_SCALE;

        if (tpsMonitor != null) {
            double currentTps = tpsMonitor.getCurrentTps();

            // Determine scale factor based on TPS
            if (currentTps >= optimalTpsThreshold) {
                scaleFactor = OPTIMAL_SCALE;
            } else if (currentTps >= minorLagTpsThreshold) {
                scaleFactor = MINOR_LAG_SCALE;
            } else if (currentTps >= moderateLagTpsThreshold) {
                scaleFactor = MODERATE_LAG_SCALE;
            } else {
                scaleFactor = SEVERE_LAG_SCALE;
            }

            if (Debug.isDebugEnabled() && scaleFactor > OPTIMAL_SCALE) {
                Debug.debug(String.format("Adjusting timing scale to %.2f due to TPS: %.2f",
                        scaleFactor, currentTps));
            }
        }

        // Check memory usage and further adjust if needed
        if (memoryMonitor != null && memoryMonitor.getMemoryUsage() > highMemoryThreshold / 100.0) {
            // Increase scale factor if memory usage is high
            scaleFactor *= 1.5;

            if (Debug.isDebugEnabled()) {
                Debug.debug(String.format("Further adjusting timing scale to %.2f due to high memory usage: %.1f%%",
                        scaleFactor, memoryMonitor.getMemoryUsage() * 100.0));
            }
        }

        this.currentScaleFactor = scaleFactor;
    }

    /**
     * Gets the current scale factor.
     *
     * @return The current timing scale factor
     */
    public double getCurrentScaleFactor() {
        return currentScaleFactor;
    }

    /**
     * Converts a real-time duration to server ticks, adjusted for current conditions.
     *
     * @param duration The duration
     * @param unit The time unit
     * @return The adjusted number of ticks
     */
    public long toAdjustedTicks(long duration, TimeUnit unit) {
        long milliseconds = unit.toMillis(duration);
        long baseTicks = TickUtil.millisecondsToTicks(milliseconds);

        // Apply current scaling
        return Math.round(baseTicks * getCurrentScaleFactor());
    }

    /**
     * Sets the TPS thresholds for different performance levels.
     *
     * @param optimal Threshold for optimal performance
     * @param minorLag Threshold for minor lag
     * @param moderateLag Threshold for moderate lag
     */
    public void setTpsThresholds(double optimal, double minorLag, double moderateLag) {
        this.optimalTpsThreshold = optimal;
        this.minorLagTpsThreshold = minorLag;
        this.moderateLagTpsThreshold = moderateLag;
    }

    /**
     * Sets the memory usage threshold for high memory condition.
     *
     * @param highMemoryPercent The threshold percentage (0-100)
     */
    public void setHighMemoryThreshold(int highMemoryPercent) {
        this.highMemoryThreshold = highMemoryPercent;
    }

    /**
     * Class for tracking timing information for a task type.
     */
    private static class TaskTimingInfo {
        private final long baseDelay;
        private final AtomicLong totalExecutionTime;
        private final AtomicLong executionCount;
        private volatile long lastExecutionTime;

        public TaskTimingInfo(long baseDelay) {
            this.baseDelay = baseDelay;
            this.totalExecutionTime = new AtomicLong(0);
            this.executionCount = new AtomicLong(0);
            this.lastExecutionTime = 0;
        }

        public void recordExecution(long executionTimeNanos) {
            totalExecutionTime.addAndGet(executionTimeNanos);
            executionCount.incrementAndGet();
            lastExecutionTime = executionTimeNanos;
        }

        public long getBaseDelay() {
            return baseDelay;
        }

        public long getLastExecutionTime() {
            return lastExecutionTime;
        }

        public long getAverageExecutionTime() {
            long count = executionCount.get();
            return count > 0 ? totalExecutionTime.get() / count : 0;
        }

        public long getExecutionCount() {
            return executionCount.get();
        }
    }
}