package me.leon.scheduler.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import me.leon.scheduler.api.Task;
import me.leon.scheduler.util.Debug;

/**
 * Tracks performance of individual task instances.
 * Provides detailed insight into task behavior and performance patterns.
 */
public class TaskTracker {

    private final Map<Long, TaskRecord> taskRecords;
    private final AtomicInteger recordIdCounter;
    private final List<TaskExecutionRecord> recentExecutions;
    private final int maxRecentExecutions;
    private final long recordRetentionTimeMs;

    /**
     * Creates a new task tracker.
     *
     * @param maxRecentExecutions The maximum number of recent executions to track
     * @param recordRetentionTimeMs The time to retain task records in milliseconds
     */
    public TaskTracker(int maxRecentExecutions, long recordRetentionTimeMs) {
        this.taskRecords = new ConcurrentHashMap<>();
        this.recordIdCounter = new AtomicInteger(0);
        this.recentExecutions = Collections.synchronizedList(new ArrayList<>());
        this.maxRecentExecutions = maxRecentExecutions;
        this.recordRetentionTimeMs = recordRetentionTimeMs;
    }

    /**
     * Creates a new task tracker with default settings.
     */
    public TaskTracker() {
        this(100, TimeUnit.MINUTES.toMillis(10));
    }

    /**
     * Registers a task for tracking.
     *
     * @param task The task to track
     * @param category The category of the task
     * @param description A description of the task
     * @return The task record ID
     */
    public int trackTask(Task task, String category, String description) {
        if (task == null) {
            return -1;
        }

        int recordId = recordIdCounter.incrementAndGet();
        TaskRecord record = new TaskRecord(recordId, task.getTaskId(), category, description);
        taskRecords.put(task.getTaskId(), record);

        return recordId;
    }

    /**
     * Records the execution of a task.
     *
     * @param taskId The task ID
     * @param executionTimeNanos The execution time in nanoseconds
     * @param successful Whether the execution was successful
     */
    public void recordExecution(long taskId, long executionTimeNanos, boolean successful) {
        TaskRecord record = taskRecords.get(taskId);
        if (record == null) {
            return;
        }

        // Update task record
        record.executionCount++;
        record.lastExecutionTimeNanos = executionTimeNanos;
        record.totalExecutionTimeNanos += executionTimeNanos;
        record.lastExecutionTime = System.currentTimeMillis();

        if (successful) {
            record.successCount++;
        } else {
            record.failureCount++;
        }

        if (executionTimeNanos > record.maxExecutionTimeNanos) {
            record.maxExecutionTimeNanos = executionTimeNanos;
        }

        // Add to recent executions
        TaskExecutionRecord execution = new TaskExecutionRecord(
                taskId, record.category, executionTimeNanos, successful, System.currentTimeMillis());

        synchronized (recentExecutions) {
            recentExecutions.add(0, execution);
            if (recentExecutions.size() > maxRecentExecutions) {
                recentExecutions.remove(recentExecutions.size() - 1);
            }
        }

        // Log slow tasks
        if (Debug.isDebugEnabled() && executionTimeNanos > TimeUnit.MILLISECONDS.toNanos(100)) {
            Debug.debug(String.format("Slow task detected (ID: %d, Category: %s): %.2fms",
                    taskId, record.category, executionTimeNanos / 1_000_000.0));
        }
    }

    /**
     * Gets a task record by task ID.
     *
     * @param taskId The task ID
     * @return The task record, or null if not found
     */
    public TaskRecord getTaskRecord(long taskId) {
        return taskRecords.get(taskId);
    }

    /**
     * Gets all task records.
     *
     * @return A list of all task records
     */
    public List<TaskRecord> getAllTaskRecords() {
        return new ArrayList<>(taskRecords.values());
    }

    /**
     * Gets task records filtered by category.
     *
     * @param category The category to filter by
     * @return A list of matching task records
     */
    public List<TaskRecord> getTaskRecordsByCategory(String category) {
        List<TaskRecord> result = new ArrayList<>();

        for (TaskRecord record : taskRecords.values()) {
            if (record.category.equals(category)) {
                result.add(record);
            }
        }

        return result;
    }

    /**
     * Gets the most recent task executions.
     *
     * @param limit The maximum number of executions to return
     * @return A list of recent task executions
     */
    public List<TaskExecutionRecord> getRecentExecutions(int limit) {
        synchronized (recentExecutions) {
            int size = Math.min(limit, recentExecutions.size());
            return new ArrayList<>(recentExecutions.subList(0, size));
        }
    }

    /**
     * Gets the slowest task executions.
     *
     * @param limit The maximum number of executions to return
     * @return A list of the slowest task executions
     */
    public List<TaskExecutionRecord> getSlowestExecutions(int limit) {
        List<TaskExecutionRecord> executions;

        synchronized (recentExecutions) {
            executions = new ArrayList<>(recentExecutions);
        }

        // Sort by execution time (descending)
        executions.sort(Comparator.comparingLong(
                (TaskExecutionRecord r) -> r.executionTimeNanos).reversed());

        int size = Math.min(limit, executions.size());
        return executions.subList(0, size);
    }

    /**
     * Gets the most frequently executed tasks.
     *
     * @param limit The maximum number of tasks to return
     * @return A list of the most frequently executed tasks
     */
    public List<TaskRecord> getMostFrequentTasks(int limit) {
        List<TaskRecord> records = new ArrayList<>(taskRecords.values());

        // Sort by execution count (descending)
        records.sort(Comparator.comparingInt(
                (TaskRecord r) -> r.executionCount).reversed());

        int size = Math.min(limit, records.size());
        return records.subList(0, size);
    }

    /**
     * Gets the tasks with the highest failure rate.
     *
     * @param limit The maximum number of tasks to return
     * @param minExecutions The minimum number of executions required
     * @return A list of tasks with the highest failure rates
     */
    public List<TaskRecord> getHighestFailureRateTasks(int limit, int minExecutions) {
        List<TaskRecord> records = new ArrayList<>();

        // Filter tasks with minimum executions
        for (TaskRecord record : taskRecords.values()) {
            if (record.executionCount >= minExecutions) {
                records.add(record);
            }
        }

        // Sort by failure rate (descending)
        records.sort(Comparator.comparingDouble(
                (TaskRecord r) -> (double) r.failureCount / r.executionCount).reversed());

        int size = Math.min(limit, records.size());
        return records.subList(0, size);
    }

    /**
     * Removes old task records that haven't been executed recently.
     */
    public void cleanupOldRecords() {
        long cutoffTime = System.currentTimeMillis() - recordRetentionTimeMs;

        // Remove old task records
        taskRecords.entrySet().removeIf(entry ->
                entry.getValue().lastExecutionTime < cutoffTime);

        // Remove old execution records
        synchronized (recentExecutions) {
            recentExecutions.removeIf(execution ->
                    execution.timestamp < cutoffTime);
        }
    }

    /**
     * Class representing a tracked task.
     */
    public static class TaskRecord {
        private final int recordId;
        private final long taskId;
        private final String category;
        private final String description;
        private int executionCount;
        private int successCount;
        private int failureCount;
        private long lastExecutionTimeNanos;
        private long maxExecutionTimeNanos;
        private long totalExecutionTimeNanos;
        private long lastExecutionTime;

        /**
         * Creates a new task record.
         *
         * @param recordId The record ID
         * @param taskId The task ID
         * @param category The category
         * @param description The description
         */
        public TaskRecord(int recordId, long taskId, String category, String description) {
            this.recordId = recordId;
            this.taskId = taskId;
            this.category = category != null ? category : "unknown";
            this.description = description != null ? description : "";
            this.executionCount = 0;
            this.successCount = 0;
            this.failureCount = 0;
            this.lastExecutionTimeNanos = 0;
            this.maxExecutionTimeNanos = 0;
            this.totalExecutionTimeNanos = 0;
            this.lastExecutionTime = System.currentTimeMillis();
        }

        /**
         * Gets the record ID.
         *
         * @return The record ID
         */
        public int getRecordId() {
            return recordId;
        }

        /**
         * Gets the task ID.
         *
         * @return The task ID
         */
        public long getTaskId() {
            return taskId;
        }

        /**
         * Gets the category.
         *
         * @return The category
         */
        public String getCategory() {
            return category;
        }

        /**
         * Gets the description.
         *
         * @return The description
         */
        public String getDescription() {
            return description;
        }

        /**
         * Gets the execution count.
         *
         * @return The execution count
         */
        public int getExecutionCount() {
            return executionCount;
        }

        /**
         * Gets the success count.
         *
         * @return The success count
         */
        public int getSuccessCount() {
            return successCount;
        }

        /**
         * Gets the failure count.
         *
         * @return The failure count
         */
        public int getFailureCount() {
            return failureCount;
        }

        /**
         * Gets the last execution time.
         *
         * @return The last execution time in nanoseconds
         */
        public long getLastExecutionTimeNanos() {
            return lastExecutionTimeNanos;
        }

        /**
         * Gets the maximum execution time.
         *
         * @return The maximum execution time in nanoseconds
         */
        public long getMaxExecutionTimeNanos() {
            return maxExecutionTimeNanos;
        }

        /**
         * Gets the total execution time.
         *
         * @return The total execution time in nanoseconds
         */
        public long getTotalExecutionTimeNanos() {
            return totalExecutionTimeNanos;
        }

        /**
         * Gets the average execution time.
         *
         * @return The average execution time in nanoseconds
         */
        public long getAverageExecutionTimeNanos() {
            return executionCount > 0 ? totalExecutionTimeNanos / executionCount : 0;
        }

        /**
         * Gets the failure rate.
         *
         * @return The failure rate (0.0 to 1.0)
         */
        public double getFailureRate() {
            return executionCount > 0 ? (double) failureCount / executionCount : 0.0;
        }

        /**
         * Gets the timestamp of the last execution.
         *
         * @return The timestamp in milliseconds
         */
        public long getLastExecutionTime() {
            return lastExecutionTime;
        }
    }

    /**
     * Class representing a single task execution.
     */
    public static class TaskExecutionRecord {
        private final long taskId;
        private final String category;
        private final long executionTimeNanos;
        private final boolean successful;
        private final long timestamp;

        /**
         * Creates a new task execution record.
         *
         * @param taskId The task ID
         * @param category The category
         * @param executionTimeNanos The execution time in nanoseconds
         * @param successful Whether the execution was successful
         * @param timestamp The timestamp in milliseconds
         */
        public TaskExecutionRecord(long taskId, String category,
                                   long executionTimeNanos, boolean successful, long timestamp) {
            this.taskId = taskId;
            this.category = category;
            this.executionTimeNanos = executionTimeNanos;
            this.successful = successful;
            this.timestamp = timestamp;
        }

        /**
         * Gets the task ID.
         *
         * @return The task ID
         */
        public long getTaskId() {
            return taskId;
        }

        /**
         * Gets the category.
         *
         * @return The category
         */
        public String getCategory() {
            return category;
        }

        /**
         * Gets the execution time.
         *
         * @return The execution time in nanoseconds
         */
        public long getExecutionTimeNanos() {
            return executionTimeNanos;
        }

        /**
         * Checks if the execution was successful.
         *
         * @return true if the execution was successful
         */
        public boolean isSuccessful() {
            return successful;
        }

        /**
         * Gets the timestamp.
         *
         * @return The timestamp in milliseconds
         */
        public long getTimestamp() {
            return timestamp;
        }
    }
}