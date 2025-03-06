package me.leon.scheduler.optimization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import me.leon.scheduler.api.Task;
import me.leon.scheduler.core.impl.TaskImpl;
import me.leon.scheduler.util.Debug;

/**
 * Combines similar small tasks into batches to reduce overhead.
 * Improves performance by reducing context switching and improving cache locality.
 */
public class TaskCoalescer {

    // Default settings
    private static final int DEFAULT_MAX_BATCH_SIZE = 32;
    private static final long DEFAULT_MAX_WAIT_MS = 50; // Max wait time for coalescing

    // Maps task types to their task buffers
    private final Map<String, TaskBuffer> taskBuffers;

    // Execution callback
    private final Consumer<List<Runnable>> batchExecutor;

    // Task ID counter
    private final AtomicInteger taskIdCounter;

    /**
     * Creates a new task coalescer.
     *
     * @param batchExecutor Callback function that executes a batch of tasks
     */
    public TaskCoalescer(Consumer<List<Runnable>> batchExecutor) {
        this.taskBuffers = new ConcurrentHashMap<>();
        this.batchExecutor = batchExecutor;
        this.taskIdCounter = new AtomicInteger(0);
    }

    /**
     * Schedules a task for coalescing with similar tasks.
     *
     * @param task The task to schedule
     * @param taskType The type of task (used for grouping similar tasks)
     * @return A task handle
     */
    public Task scheduleTask(Runnable task, String taskType) {
        return scheduleTask(task, taskType, DEFAULT_MAX_BATCH_SIZE, DEFAULT_MAX_WAIT_MS);
    }

    /**
     * Schedules a task for coalescing with similar tasks.
     *
     * @param task The task to schedule
     * @param taskType The type of task (used for grouping similar tasks)
     * @param maxBatchSize Maximum number of tasks to coalesce
     * @param maxWaitTimeMs Maximum time to wait for a full batch
     * @return A task handle
     */
    public Task scheduleTask(Runnable task, String taskType, int maxBatchSize, long maxWaitTimeMs) {
        // Create task implementation
        long taskId = taskIdCounter.incrementAndGet();
        TaskImpl taskImpl = new TaskImpl(taskId, task, false);

        // Get or create buffer for this task type
        TaskBuffer buffer = taskBuffers.computeIfAbsent(taskType,
                k -> new TaskBuffer(k, maxBatchSize, maxWaitTimeMs, batchExecutor));

        // Add task to buffer
        buffer.addTask(task, taskImpl);

        return taskImpl;
    }

    /**
     * Schedules a task for coalescing, with default settings.
     *
     * @param task The task to schedule
     * @param taskType The type of task
     * @param metadata Optional metadata to attach to the task
     * @return A task handle
     */
    public Task scheduleTask(Runnable task, String taskType, Map<String, Object> metadata) {
        // Create task implementation
        long taskId = taskIdCounter.incrementAndGet();
        TaskImpl taskImpl = new TaskImpl(taskId, task, false);

        // Get or create buffer for this task type
        TaskBuffer buffer = taskBuffers.computeIfAbsent(taskType,
                k -> new TaskBuffer(k, DEFAULT_MAX_BATCH_SIZE, DEFAULT_MAX_WAIT_MS, batchExecutor));

        // Add metadata to the underlying TaskImpl if available

        // Add task to buffer
        buffer.addTask(task, taskImpl);

        return taskImpl;
    }

    /**
     * Flushes all pending tasks immediately.
     */
    public void flushAllTasks() {
        for (TaskBuffer buffer : taskBuffers.values()) {
            buffer.flush();
        }
    }

    /**
     * Gets the number of pending tasks.
     *
     * @return The total number of pending tasks
     */
    public int getPendingTaskCount() {
        int count = 0;
        for (TaskBuffer buffer : taskBuffers.values()) {
            count += buffer.getPendingCount();
        }
        return count;
    }

    /**
     * Class representing a buffer of tasks of the same type.
     */
    private static class TaskBuffer {
        private final String taskType;
        private final int maxBatchSize;
        private final long maxWaitTimeMs;
        private final Consumer<List<Runnable>> batchExecutor;

        private final List<QueuedTask> pendingTasks;
        private long firstTaskTime;
        private boolean flushScheduled;

        /**
         * Creates a new task buffer.
         *
         * @param taskType The type of tasks in this buffer
         * @param maxBatchSize Maximum batch size
         * @param maxWaitTimeMs Maximum wait time
         * @param batchExecutor Callback to execute batched tasks
         */
        public TaskBuffer(String taskType, int maxBatchSize, long maxWaitTimeMs,
                          Consumer<List<Runnable>> batchExecutor) {
            this.taskType = taskType;
            this.maxBatchSize = maxBatchSize;
            this.maxWaitTimeMs = maxWaitTimeMs;
            this.batchExecutor = batchExecutor;

            this.pendingTasks = new ArrayList<>();
            this.firstTaskTime = 0;
            this.flushScheduled = false;
        }

        /**
         * Adds a task to the buffer.
         *
         * @param task The task to add
         * @param taskImpl The task implementation
         */
        public synchronized void addTask(Runnable task, TaskImpl taskImpl) {
            QueuedTask queuedTask = new QueuedTask(task, taskImpl);

            // If this is the first task, record the time
            if (pendingTasks.isEmpty()) {
                firstTaskTime = System.currentTimeMillis();
            }

            pendingTasks.add(queuedTask);

            // If we've reached the batch size, execute immediately
            if (pendingTasks.size() >= maxBatchSize) {
                flush();
                return;
            }

            // If we haven't scheduled a delayed flush yet, do so
            if (!flushScheduled) {
                flushScheduled = true;

                // Schedule a flush after the maximum wait time
                Thread flushThread = new Thread(() -> {
                    try {
                        Thread.sleep(maxWaitTimeMs);
                        flush();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                flushThread.setDaemon(true);
                flushThread.start();
            }
        }

        /**
         * Flushes all pending tasks.
         */
        public synchronized void flush() {
            if (pendingTasks.isEmpty()) {
                return;
            }

            // Create the batch
            List<Runnable> batch = new ArrayList<>(pendingTasks.size());
            List<TaskImpl> taskImpls = new ArrayList<>(pendingTasks.size());

            for (QueuedTask queuedTask : pendingTasks) {
                if (!queuedTask.taskImpl.isCancelled()) {
                    batch.add(queuedTask.task);
                    taskImpls.add(queuedTask.taskImpl);
                }
            }

            // Clear the buffer
            pendingTasks.clear();
            firstTaskTime = 0;
            flushScheduled = false;

            // Execute the batch
            if (!batch.isEmpty()) {
                Thread executorThread = new Thread(() -> {
                    long startTime = System.nanoTime();

                    try {
                        // Mark tasks as running
                        for (TaskImpl taskImpl : taskImpls) {
                            taskImpl.markRunning(true);
                        }

                        // Execute the batch
                        batchExecutor.accept(batch);

                        long execTime = System.nanoTime() - startTime;

                        // Complete all tasks
                        for (TaskImpl taskImpl : taskImpls) {
                            taskImpl.recordExecution(execTime / batch.size());
                            taskImpl.markRunning(false);
                            taskImpl.complete();
                        }

                        Debug.debug("Executed batch of " + batch.size() + " " + taskType +
                                " tasks in " + (execTime / 1_000_000.0) + "ms");
                    } catch (Exception e) {
                        Debug.log(java.util.logging.Level.SEVERE,
                                "Error executing coalesced batch: " + e.getMessage());

                        // Fail all tasks
                        for (TaskImpl taskImpl : taskImpls) {
                            taskImpl.markRunning(false);
                            taskImpl.completeExceptionally(e);
                        }
                    }
                });

                executorThread.start();
            }
        }

        /**
         * Gets the number of pending tasks in this buffer.
         *
         * @return The number of pending tasks
         */
        public synchronized int getPendingCount() {
            return pendingTasks.size();
        }

        /**
         * Gets the time elapsed since the first task was added.
         *
         * @return Time in milliseconds, or 0 if no tasks are pending
         */
        public synchronized long getElapsedTimeMs() {
            if (firstTaskTime == 0) {
                return 0;
            }
            return System.currentTimeMillis() - firstTaskTime;
        }
    }

    /**
     * Class representing a queued task.
     */
    private static class QueuedTask {
        private final Runnable task;
        private final TaskImpl taskImpl;

        /**
         * Creates a new queued task.
         *
         * @param task The task to run
         * @param taskImpl The task implementation
         */
        public QueuedTask(Runnable task, TaskImpl taskImpl) {
            this.task = task;
            this.taskImpl = taskImpl;
        }
    }
}