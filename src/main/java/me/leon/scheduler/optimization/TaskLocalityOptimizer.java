package me.leon.scheduler.optimization;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import me.leon.scheduler.api.Task;
import me.leon.scheduler.util.Debug;

/**
 * Optimizer that improves cache locality by scheduling related tasks together.
 * Reduces cache misses by grouping tasks that operate on the same data.
 */
public class TaskLocalityOptimizer {

    private static final int DEFAULT_LOCALITY_GROUP_SIZE = 8;
    private static final long GROUP_TIMEOUT_MS = 10; // Max wait time for grouping

    // Maps locality keys to their task groups
    private final Map<String, LocalityGroup> localityGroups;

    // Tracks last access time for data regions
    private final Map<String, AtomicLong> lastAccessTimes;

    /**
     * Creates a new task locality optimizer.
     */
    public TaskLocalityOptimizer() {
        this.localityGroups = new ConcurrentHashMap<>();
        this.lastAccessTimes = new ConcurrentHashMap<>();
    }

    /**
     * Adds a task to a locality group for optimized execution.
     *
     * @param task The task to execute
     * @param localityKey The data locality key this task operates on
     * @param maxGroupSize Maximum group size before execution
     * @return A new task that handles the grouped execution
     */
    public Task scheduleLocalityAware(Runnable task, String localityKey, int maxGroupSize) {
        if (localityKey == null || localityKey.isEmpty()) {
            // No locality key, execute normally
            return new TaskWrapper(task);
        }

        // Update last access time for this locality key
        lastAccessTimes.computeIfAbsent(localityKey, k -> new AtomicLong())
                .set(System.currentTimeMillis());

        // Get or create locality group
        LocalityGroup group = localityGroups.computeIfAbsent(localityKey,
                k -> new LocalityGroup(maxGroupSize > 0 ? maxGroupSize : DEFAULT_LOCALITY_GROUP_SIZE));

        // Add task to group and get a task wrapper
        return group.addTask(task);
    }

    /**
     * Adds a task to a locality group and schedules execution when the group is full.
     *
     * @param task The task to execute
     * @param localityKey The data locality key this task operates on
     * @return A new task that handles the grouped execution
     */
    public Task scheduleLocalityAware(Runnable task, String localityKey) {
        return scheduleLocalityAware(task, localityKey, DEFAULT_LOCALITY_GROUP_SIZE);
    }

    /**
     * Records an access to a data region to influence scheduling.
     * This helps the optimizer track data access patterns.
     *
     * @param localityKey The data locality key being accessed
     */
    public void recordDataAccess(String localityKey) {
        if (localityKey != null && !localityKey.isEmpty()) {
            lastAccessTimes.computeIfAbsent(localityKey, k -> new AtomicLong())
                    .set(System.currentTimeMillis());
        }
    }

    /**
     * Gets the time since the last access to a specific data region.
     *
     * @param localityKey The data locality key
     * @return Time in milliseconds since last access, or Long.MAX_VALUE if never accessed
     */
    public long getTimeSinceLastAccess(String localityKey) {
        AtomicLong lastAccess = lastAccessTimes.get(localityKey);
        if (lastAccess == null) {
            return Long.MAX_VALUE;
        }
        return System.currentTimeMillis() - lastAccess.get();
    }

    /**
     * Checks if a data region was recently accessed.
     *
     * @param localityKey The data locality key
     * @param thresholdMs The recency threshold in milliseconds
     * @return true if the data was accessed within the threshold period
     */
    public boolean wasRecentlyAccessed(String localityKey, long thresholdMs) {
        return getTimeSinceLastAccess(localityKey) <= thresholdMs;
    }

    /**
     * Cleans up old locality groups that haven't been accessed recently.
     */
    public void cleanupStaleGroups() {
        long currentTime = System.currentTimeMillis();
        long staleThreshold = 60000; // 1 minute

        // Clean up locality groups for data that hasn't been accessed recently
        localityGroups.entrySet().removeIf(entry -> {
            String localityKey = entry.getKey();
            LocalityGroup group = entry.getValue();

            AtomicLong lastAccess = lastAccessTimes.get(localityKey);
            boolean isStale = lastAccess == null ||
                    (currentTime - lastAccess.get() > staleThreshold);

            if (isStale && group.isEmpty()) {
                return true; // Remove stale empty groups
            } else if (isStale) {
                // Execute any remaining tasks in stale groups
                group.executeNow();
            }

            return false;
        });

        // Clean up old access times
        lastAccessTimes.entrySet().removeIf(entry ->
                currentTime - entry.getValue().get() > staleThreshold);
    }

    /**
     * Executes all pending tasks in all locality groups.
     * Useful during shutdown or when immediate execution is needed.
     */
    public void executeAllPending() {
        for (LocalityGroup group : localityGroups.values()) {
            group.executeNow();
        }
    }

    /**
     * Creates a locality key based on an object's identity.
     * Useful for tasks that operate on specific objects.
     *
     * @param object The object to create a locality key for
     * @return A locality key string
     */
    public static String createLocalityKey(Object object) {
        if (object == null) {
            return "";
        }
        return object.getClass().getSimpleName() + "@" + System.identityHashCode(object);
    }

    /**
     * Creates a locality key based on a specific region (e.g., chunk coordinates).
     *
     * @param regionType The type of region
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return A locality key string
     */
    public static String createRegionLocalityKey(String regionType, int x, int y, int z) {
        return regionType + ":" + x + "," + y + "," + z;
    }

    /**
     * Class representing a group of tasks with the same locality.
     */
    private class LocalityGroup {
        private final Queue<TaskEntry> tasks;
        private final int maxSize;
        private final Object lock = new Object();
        private volatile boolean executionScheduled = false;
        private long firstTaskAddedTime = 0;

        /**
         * Creates a new locality group.
         *
         * @param maxSize Maximum number of tasks in the group before execution
         */
        public LocalityGroup(int maxSize) {
            this.tasks = new LinkedList<>();
            this.maxSize = maxSize;
        }

        /**
         * Adds a task to this locality group.
         *
         * @param task The task to add
         * @return A task wrapper that represents this task in the group
         */
        public Task addTask(Runnable task) {
            TaskWrapper wrapper = new TaskWrapper(task);

            synchronized (lock) {
                TaskEntry entry = new TaskEntry(task, wrapper);
                tasks.add(entry);

                if (tasks.size() == 1) {
                    firstTaskAddedTime = System.currentTimeMillis();
                }

                // If we've reached max size, execute the group
                if (tasks.size() >= maxSize && !executionScheduled) {
                    scheduleExecution();
                }
                // If we've been waiting too long, execute the group
                else if (!executionScheduled &&
                        System.currentTimeMillis() - firstTaskAddedTime > GROUP_TIMEOUT_MS) {
                    scheduleExecution();
                }
            }

            return wrapper;
        }

        /**
         * Schedules the execution of all tasks in this group.
         */
        private void scheduleExecution() {
            synchronized (lock) {
                if (executionScheduled || tasks.isEmpty()) {
                    return;
                }

                executionScheduled = true;

                // Execute on the current thread for better locality
                new Thread(() -> executeNow()).start();
            }
        }

        /**
         * Executes all pending tasks in this group immediately.
         */
        public void executeNow() {
            Queue<TaskEntry> tasksToExecute;

            synchronized (lock) {
                if (tasks.isEmpty()) {
                    return;
                }

                tasksToExecute = new LinkedList<>(tasks);
                tasks.clear();
                executionScheduled = false;
                firstTaskAddedTime = 0;
            }

            // Execute all tasks in sequence
            for (TaskEntry entry : tasksToExecute) {
                try {
                    long startTime = System.nanoTime();
                    entry.task.run();
                    entry.wrapper.complete(System.nanoTime() - startTime);
                } catch (Throwable t) {
                    entry.wrapper.completeExceptionally(t);
                    Debug.log(java.util.logging.Level.SEVERE,
                            "Error executing task in locality group: " + t.getMessage());
                }
            }
        }

        /**
         * Checks if this group has no pending tasks.
         *
         * @return true if there are no tasks in this group
         */
        public boolean isEmpty() {
            synchronized (lock) {
                return tasks.isEmpty();
            }
        }
    }

    /**
     * Class representing an entry in a locality group.
     */
    private static class TaskEntry {
        private final Runnable task;
        private final TaskWrapper wrapper;

        /**
         * Creates a new task entry.
         *
         * @param task The task to execute
         * @param wrapper The task wrapper
         */
        public TaskEntry(Runnable task, TaskWrapper wrapper) {
            this.task = task;
            this.wrapper = wrapper;
        }
    }

    /**
     * Class that wraps a task for locality-aware execution.
     */
    private static class TaskWrapper implements Task {
        private final Runnable originalTask;
        private final Map<String, Object> metadata;
        private volatile boolean cancelled = false;
        private volatile boolean completed = false;
        private volatile boolean running = false;
        private volatile Throwable exception = null;
        private volatile long executionTimeNanos = 0;
        private final long taskId;
        private final Consumer<Throwable>[] exceptionHandlers;
        private final Runnable[] completionHandlers;

        /**
         * Creates a new task wrapper.
         *
         * @param task The original task to wrap
         */
        @SuppressWarnings("unchecked")
        public TaskWrapper(Runnable task) {
            this.originalTask = task;
            this.metadata = new HashMap<>();
            this.taskId = System.nanoTime(); // Use timestamp as task ID
            this.exceptionHandlers = new Consumer[1];
            this.completionHandlers = new Runnable[1];
        }

        /**
         * Marks the task as complete.
         *
         * @param executionTimeNanos The execution time in nanoseconds
         */
        public void complete(long executionTimeNanos) {
            this.executionTimeNanos = executionTimeNanos;
            this.completed = true;

            if (completionHandlers[0] != null) {
                try {
                    completionHandlers[0].run();
                } catch (Exception e) {
                    // Ignore exceptions in completion handler
                }
            }
        }

        /**
         * Marks the task as failed.
         *
         * @param t The exception that caused the failure
         */
        public void completeExceptionally(Throwable t) {
            this.exception = t;
            this.completed = true;

            if (exceptionHandlers[0] != null) {
                try {
                    exceptionHandlers[0].accept(t);
                } catch (Exception e) {
                    // Ignore exceptions in exception handler
                }
            }
        }

        @Override
        public boolean cancel() {
            if (!completed) {
                cancelled = true;
                return true;
            }
            return false;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public boolean isDone() {
            return completed;
        }

        @Override
        public long getTaskId() {
            return taskId;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> getCompletionFuture() {
            java.util.concurrent.CompletableFuture<Void> future = new java.util.concurrent.CompletableFuture<>();
            if (completed) {
                if (exception != null) {
                    future.completeExceptionally(exception);
                } else {
                    future.complete(null);
                }
            } else {
                thenRun(() -> future.complete(null));
                exceptionally(future::completeExceptionally);
            }
            return future;
        }

        @Override
        public Task thenRun(Runnable action) {
            if (completed) {
                if (exception == null) {
                    action.run();
                }
            } else {
                completionHandlers[0] = action;
            }
            return this;
        }

        @Override
        public Task exceptionally(Consumer<Throwable> action) {
            if (completed && exception != null) {
                action.accept(exception);
            } else {
                exceptionHandlers[0] = action;
            }
            return this;
        }

        @Override
        public long getLastExecutionTimeNanos() {
            return executionTimeNanos;
        }

        @Override
        public long getAverageExecutionTimeNanos() {
            return executionTimeNanos;
        }

        @Override
        public int getExecutionCount() {
            return completed ? 1 : 0;
        }
    }
}