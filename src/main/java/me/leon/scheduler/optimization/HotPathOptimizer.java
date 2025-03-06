package me.leon.scheduler.optimization;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import me.leon.scheduler.api.Task;
import me.leon.scheduler.util.Debug;

/**
 * Identifies and optimizes frequently executed tasks ("hot paths").
 * Enhances performance by applying specialized optimizations to critical code.
 */
public class HotPathOptimizer {

    // Configuration parameters
    private static final int HOT_PATH_THRESHOLD = 50; // Executions to consider a path "hot"
    private static final long OPTIMIZATION_INTERVAL_MS = 60000; // Check for hot paths every minute
    private static final int MAX_HOT_PATHS = 100; // Maximum number of hot paths to track

    // Maps task signatures to execution counts
    private final Map<String, HotPathInfo> hotPaths;
    private final Map<String, OptimizedTaskInfo> optimizedTasks;
    private long lastOptimizationTime;

    /**
     * Creates a new hot path optimizer.
     */
    public HotPathOptimizer() {
        this.hotPaths = new ConcurrentHashMap<>();
        this.optimizedTasks = new ConcurrentHashMap<>();
        this.lastOptimizationTime = System.currentTimeMillis();
    }

    /**
     * Records an execution of a task and identifies if it's a hot path.
     *
     * @param taskSignature A unique signature identifying the task type
     * @param executionTimeNanos The execution time in nanoseconds
     */
    public void recordExecution(String taskSignature, long executionTimeNanos) {
        if (taskSignature == null || taskSignature.isEmpty()) {
            return;
        }

        // Update or create execution stats for this task
        hotPaths.computeIfAbsent(taskSignature, k -> new HotPathInfo())
                .recordExecution(executionTimeNanos);

        // Check if it's time to evaluate hot paths
        long now = System.currentTimeMillis();
        if (now - lastOptimizationTime > OPTIMIZATION_INTERVAL_MS) {
            identifyAndOptimizeHotPaths();
            lastOptimizationTime = now;
        }
    }

    /**
     * Identifies hot paths and applies optimizations.
     */
    private void identifyAndOptimizeHotPaths() {
        if (hotPaths.isEmpty()) {
            return;
        }

        // Limit the size of the tracking map to prevent memory issues
        if (hotPaths.size() > MAX_HOT_PATHS * 2) {
            pruneHotPaths();
        }

        for (Map.Entry<String, HotPathInfo> entry : hotPaths.entrySet()) {
            String taskSignature = entry.getKey();
            HotPathInfo info = entry.getValue();

            // If this is already optimized, update its stats
            if (optimizedTasks.containsKey(taskSignature)) {
                OptimizedTaskInfo optInfo = optimizedTasks.get(taskSignature);
                optInfo.updateStatistics(info);
                continue;
            }

            // Check if this path meets the hot path criteria
            if (info.getExecutionCount() >= HOT_PATH_THRESHOLD) {
                // This is a hot path, try to optimize it
                if (optimizePath(taskSignature, info)) {
                    Debug.log(java.util.logging.Level.INFO,
                            "Hot path optimized: " + taskSignature +
                                    " (executions: " + info.getExecutionCount() +
                                    ", avg time: " + String.format("%.2f", info.getAverageExecutionTimeNanos() / 1_000_000.0) + "ms)");
                }
            }
        }
    }

    /**
     * Applies optimization strategies to a hot path.
     *
     * @param taskSignature The task signature
     * @param info The hot path information
     * @return true if optimization was applied
     */
    private boolean optimizePath(String taskSignature, HotPathInfo info) {
        // Apply optimizations based on task characteristics

        // Note: The actual optimization strategy depends on the task's nature
        OptimizationStrategy strategy = determineOptimizationStrategy(info);

        if (strategy != OptimizationStrategy.NONE) {
            optimizedTasks.put(taskSignature, new OptimizedTaskInfo(info, strategy));
            return true;
        }

        return false;
    }

    /**
     * Determines the most appropriate optimization strategy for a hot path.
     *
     * @param info The hot path information
     * @return The selected optimization strategy
     */
    private OptimizationStrategy determineOptimizationStrategy(HotPathInfo info) {
        // Decision logic for optimization strategy based on execution patterns

        double avgExecutionTimeMs = info.getAverageExecutionTimeNanos() / 1_000_000.0;

        if (avgExecutionTimeMs < 0.1) {
            // Very fast tasks benefit from caching and inline execution
            return OptimizationStrategy.INLINE;
        } else if (avgExecutionTimeMs < 1.0) {
            // Fast tasks benefit from specialized handling
            return OptimizationStrategy.SPECIALIZED_QUEUE;
        } else if (info.getExecutionCount() > HOT_PATH_THRESHOLD * 10) {
            // Extremely frequent tasks benefit from batch processing
            return OptimizationStrategy.BATCH;
        } else {
            // Default to no special optimization
            return OptimizationStrategy.NONE;
        }
    }

    /**
     * Gets optimization recommendations for a task.
     *
     * @param taskSignature The task signature
     * @return Optimization information, or null if the task is not optimized
     */
    public OptimizedTaskInfo getOptimizationInfo(String taskSignature) {
        return optimizedTasks.get(taskSignature);
    }

    /**
     * Creates a task signature from task details.
     *
     * @param category The task category
     * @param description A description of the task (optional)
     * @return A unique task signature
     */
    public static String createTaskSignature(String category, String description) {
        if (description != null && !description.isEmpty()) {
            return category + ":" + description.hashCode();
        }
        return category;
    }

    /**
     * Checks if a task is on a hot path and should be specially optimized.
     *
     * @param taskSignature The task signature
     * @return true if the task is on a hot path
     */
    public boolean isHotPath(String taskSignature) {
        return optimizedTasks.containsKey(taskSignature);
    }

    /**
     * Prunes the hot paths map to prevent memory issues.
     * Keeps only the most frequently executed paths.
     */
    private void pruneHotPaths() {
        // Find entries with low execution counts and remove them
        hotPaths.entrySet().removeIf(entry ->
                entry.getValue().getExecutionCount() < HOT_PATH_THRESHOLD / 2);
    }

    /**
     * Recommended execution mode for a task based on its optimization status.
     *
     * @param taskSignature The task signature
     * @return The recommended execution mode
     */
    public ExecutionMode getRecommendedMode(String taskSignature) {
        OptimizedTaskInfo info = optimizedTasks.get(taskSignature);

        if (info == null) {
            return ExecutionMode.NORMAL;
        }

        switch (info.getStrategy()) {
            case INLINE:
                return ExecutionMode.INLINE;
            case SPECIALIZED_QUEUE:
                return ExecutionMode.PRIORITY;
            case BATCH:
                return ExecutionMode.BATCH;
            default:
                return ExecutionMode.NORMAL;
        }
    }

    /**
     * Possible optimization strategies for hot paths.
     */
    public enum OptimizationStrategy {
        NONE,            // No special optimization
        INLINE,          // Execute inline with minimal overhead
        SPECIALIZED_QUEUE, // Use specialized high-performance queue
        BATCH            // Batch processing for very frequent tasks
    }

    /**
     * Recommended execution modes for tasks.
     */
    public enum ExecutionMode {
        NORMAL,   // Standard execution
        INLINE,   // Execute with minimal overhead
        PRIORITY, // Execute with high priority
        BATCH     // Execute in batch with similar tasks
    }

    /**
     * Class for tracking hot path information.
     */
    public static class HotPathInfo {
        private final AtomicInteger executionCount;
        private final AtomicLong totalExecutionTimeNanos;
        private volatile double avgExecutionTimeNanos;

        public HotPathInfo() {
            this.executionCount = new AtomicInteger(0);
            this.totalExecutionTimeNanos = new AtomicLong(0);
            this.avgExecutionTimeNanos = 0;
        }

        /**
         * Records an execution of this path.
         *
         * @param executionTimeNanos The execution time in nanoseconds
         */
        public void recordExecution(long executionTimeNanos) {
            int count = executionCount.incrementAndGet();
            totalExecutionTimeNanos.addAndGet(executionTimeNanos);

            // Update exponential moving average (EMA) for more responsive tracking
            double alpha = 0.2; // Weight for the most recent observation
            if (count == 1) {
                avgExecutionTimeNanos = executionTimeNanos;
            } else {
                avgExecutionTimeNanos = (1 - alpha) * avgExecutionTimeNanos + alpha * executionTimeNanos;
            }
        }

        /**
         * Gets the total number of executions.
         *
         * @return The execution count
         */
        public int getExecutionCount() {
            return executionCount.get();
        }

        /**
         * Gets the total execution time in nanoseconds.
         *
         * @return The total execution time
         */
        public long getTotalExecutionTimeNanos() {
            return totalExecutionTimeNanos.get();
        }

        /**
         * Gets the average execution time in nanoseconds.
         *
         * @return The average execution time
         */
        public double getAverageExecutionTimeNanos() {
            return avgExecutionTimeNanos;
        }
    }

    /**
     * Class for tracking information about optimized tasks.
     */
    public static class OptimizedTaskInfo {
        private final AtomicInteger executionCount;
        private final AtomicLong totalExecutionTimeNanos;
        private final OptimizationStrategy strategy;
        private volatile double avgExecutionTimeNanos;
        private volatile long lastUpdateTime;

        /**
         * Creates a new optimized task info.
         *
         * @param baseInfo The hot path information
         * @param strategy The optimization strategy
         */
        public OptimizedTaskInfo(HotPathInfo baseInfo, OptimizationStrategy strategy) {
            this.executionCount = new AtomicInteger(baseInfo.getExecutionCount());
            this.totalExecutionTimeNanos = new AtomicLong(baseInfo.getTotalExecutionTimeNanos());
            this.avgExecutionTimeNanos = baseInfo.getAverageExecutionTimeNanos();
            this.strategy = strategy;
            this.lastUpdateTime = System.currentTimeMillis();
        }

        /**
         * Updates statistics from the hot path information.
         *
         * @param info The hot path information
         */
        public void updateStatistics(HotPathInfo info) {
            this.executionCount.set(info.getExecutionCount());
            this.totalExecutionTimeNanos.set(info.getTotalExecutionTimeNanos());
            this.avgExecutionTimeNanos = info.getAverageExecutionTimeNanos();
            this.lastUpdateTime = System.currentTimeMillis();
        }

        /**
         * Gets the optimization strategy.
         *
         * @return The optimization strategy
         */
        public OptimizationStrategy getStrategy() {
            return strategy;
        }

        /**
         * Gets the execution count.
         *
         * @return The execution count
         */
        public int getExecutionCount() {
            return executionCount.get();
        }

        /**
         * Gets the average execution time in nanoseconds.
         *
         * @return The average execution time
         */
        public double getAverageExecutionTimeNanos() {
            return avgExecutionTimeNanos;
        }

        /**
         * Gets the last update time.
         *
         * @return The last time statistics were updated
         */
        public long getLastUpdateTime() {
            return lastUpdateTime;
        }
    }
}