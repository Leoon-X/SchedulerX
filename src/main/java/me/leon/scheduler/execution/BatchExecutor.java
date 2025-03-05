package me.leon.scheduler.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.plugin.Plugin;

import me.leon.scheduler.api.Task;
import me.leon.scheduler.core.ThreadPoolManager;
import me.leon.scheduler.core.impl.TaskImpl;
import me.leon.scheduler.util.Debug;
import me.leon.scheduler.util.TickUtil;

/**
 * Execution strategy for batching similar tasks together.
 * Optimizes execution by grouping tasks to reduce overhead.
 */
public class BatchExecutor implements ExecutionStrategy {

    private static final int DEFAULT_BATCH_SIZE = 16;
    private static final long DEFAULT_BATCH_INTERVAL_MILLIS = 50; // Process batches every 50ms (1 tick)

    private final Plugin plugin;
    private final ThreadPoolManager poolManager;
    private final ScheduledExecutorService scheduler;
    private final Map<String, TaskBatch> batches;
    private final Map<Long, BatchTaskInfo> taskInfoMap;

    /**
     * Creates a new batch executor.
     *
     * @param plugin The owning plugin
     * @param poolManager The thread pool manager
     */
    public BatchExecutor(Plugin plugin, ThreadPoolManager poolManager) {
        this.plugin = plugin;
        this.poolManager = poolManager;
        this.scheduler = poolManager.getScheduledPool();
        this.batches = new ConcurrentHashMap<>();
        this.taskInfoMap = new ConcurrentHashMap<>();
    }

    /**
     * Executes a task as part of a specific batch.
     *
     * @param runnable The task to execute
     * @param taskId The unique ID of the task
     * @param batchName The name of the batch to add this task to
     * @return A cancellable task handle
     */
    public Task executeInBatch(Runnable runnable, long taskId, String batchName) {
        TaskImpl task = new TaskImpl(taskId, runnable, false);

        try {
            // Get or create the batch
            TaskBatch batch = batches.computeIfAbsent(batchName,
                    name -> new TaskBatch(name, DEFAULT_BATCH_SIZE));

            // Add task to the batch
            batch.addTask(task);
            taskInfoMap.put(taskId, new BatchTaskInfo(batchName, task));

            return task;
        } catch (Exception e) {
            Debug.log(java.util.logging.Level.SEVERE, "Error adding task to batch: " + e.getMessage());
            task.completeExceptionally(e);
            return task;
        }
    }

    @Override
    public Task execute(Runnable runnable, long taskId) {
        // For individual execution, create a unique batch per task
        return executeInBatch(runnable, taskId, "single-" + taskId);
    }

    @Override
    public Task executeLater(Runnable runnable, long taskId, long delayTicks) {
        TaskImpl task = new TaskImpl(taskId, runnable, false);

        try {
            // Convert ticks to milliseconds
            long delayMillis = TickUtil.ticksToMilliseconds(delayTicks);

            // Schedule a one-time execution
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                try {
                    task.markRunning(true);
                    long startTime = System.nanoTime();

                    task.run();

                    long executionTime = System.nanoTime() - startTime;
                    task.recordExecution(executionTime);
                    task.complete();
                } catch (Throwable t) {
                    task.completeExceptionally(t);
                } finally {
                    task.markRunning(false);
                }
            }, delayMillis, TimeUnit.MILLISECONDS);

            // Store the future for possible cancellation
            taskInfoMap.put(taskId, new BatchTaskInfo("delayed-" + taskId, task, future));

            return task;
        } catch (Exception e) {
            Debug.log(java.util.logging.Level.SEVERE, "Error scheduling delayed batch task: " + e.getMessage());
            task.completeExceptionally(e);
            return task;
        }
    }

    @Override
    public Task executeRepeating(Runnable runnable, long taskId, long delayTicks, long periodTicks) {
        // For repeating tasks, we use a standard scheduled executor
        TaskImpl task = new TaskImpl(taskId, runnable, true);

        try {
            // Convert ticks to milliseconds
            long delayMillis = TickUtil.ticksToMilliseconds(delayTicks);
            long periodMillis = TickUtil.ticksToMilliseconds(periodTicks);

            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (task.isCancelled()) {
                        return;
                    }

                    task.markRunning(true);
                    long startTime = System.nanoTime();

                    task.run();

                    long executionTime = System.nanoTime() - startTime;
                    task.recordExecution(executionTime);
                } catch (Throwable t) {
                    task.completeExceptionally(t);
                } finally {
                    task.markRunning(false);
                }
            }, delayMillis, periodMillis, TimeUnit.MILLISECONDS);

            // Store the future for possible cancellation
            taskInfoMap.put(taskId, new BatchTaskInfo("repeating-" + taskId, task, future));

            return task;
        } catch (Exception e) {
            Debug.log(java.util.logging.Level.SEVERE, "Error scheduling repeating batch task: " + e.getMessage());
            task.completeExceptionally(e);
            return task;
        }
    }

    @Override
    public boolean cancelTask(long taskId) {
        BatchTaskInfo info = taskInfoMap.remove(taskId);
        if (info == null) {
            return false;
        }

        // Cancel the task
        info.task.markCancelled();

        // If it has a dedicated future, cancel it
        if (info.future != null) {
            info.future.cancel(false);
        }

        // If it's in a batch, the batch will skip it on execution
        return true;
    }

    @Override
    public boolean isCurrentThread() {
        // Batch executor can run on any thread
        return true;
    }

    @Override
    public String getName() {
        return "BatchExecutor";
    }

    /**
     * Starts the batch processing scheduler.
     */
    public void start() {
        // Schedule periodic batch processing
        scheduler.scheduleAtFixedRate(this::processBatches,
                DEFAULT_BATCH_INTERVAL_MILLIS,
                DEFAULT_BATCH_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);

        Debug.log(java.util.logging.Level.INFO, "Batch executor started");
    }

    /**
     * Stops the batch executor and cancels all pending batches.
     */
    public void shutdown() {
        for (TaskBatch batch : batches.values()) {
            batch.clear();
        }
        batches.clear();
        taskInfoMap.clear();
    }

    /**
     * Processes all pending task batches.
     */
    private void processBatches() {
        if (batches.isEmpty()) {
            return;
        }

        // Process each batch
        for (TaskBatch batch : batches.values()) {
            if (!batch.hasPendingTasks()) {
                continue;
            }

            // Execute the batch on an appropriate thread pool
            poolManager.getPool(ThreadPoolManager.PoolType.CPU_BOUND).execute(() -> {
                try {
                    batch.execute();
                } catch (Throwable t) {
                    Debug.log(java.util.logging.Level.SEVERE,
                            "Error processing batch " + batch.getName() + ": " + t.getMessage());
                }
            });
        }
    }

    /**
     * Class representing a batch of related tasks.
     */
    private static class TaskBatch {
        private final String name;
        private final int maxBatchSize;
        private final List<TaskImpl> pendingTasks;
        private final AtomicInteger processedTaskCount;

        public TaskBatch(String name, int maxBatchSize) {
            this.name = name;
            this.maxBatchSize = maxBatchSize;
            this.pendingTasks = new ArrayList<>(maxBatchSize);
            this.processedTaskCount = new AtomicInteger(0);
        }

        /**
         * Gets the name of this batch.
         *
         * @return The batch name
         */
        public String getName() {
            return name;
        }

        /**
         * Adds a task to this batch.
         *
         * @param task The task to add
         */
        public void addTask(TaskImpl task) {
            synchronized (pendingTasks) {
                pendingTasks.add(task);
            }
        }

        /**
         * Checks if this batch has pending tasks.
         *
         * @return true if there are pending tasks
         */
        public boolean hasPendingTasks() {
            synchronized (pendingTasks) {
                return !pendingTasks.isEmpty();
            }
        }

        /**
         * Clears all pending tasks in this batch.
         */
        public void clear() {
            synchronized (pendingTasks) {
                pendingTasks.clear();
            }
        }

        /**
         * Executes all pending tasks in this batch.
         */
        public void execute() {
            List<TaskImpl> tasksToExecute;

            // Get the tasks to execute in this batch
            synchronized (pendingTasks) {
                if (pendingTasks.isEmpty()) {
                    return;
                }

                tasksToExecute = new ArrayList<>(pendingTasks);
                pendingTasks.clear();
            }

            // Execute each task
            long batchStartTime = System.nanoTime();
            int successCount = 0;

            for (TaskImpl task : tasksToExecute) {
                if (task.isCancelled()) {
                    continue;
                }

                try {
                    task.markRunning(true);
                    long taskStartTime = System.nanoTime();

                    task.run();

                    long executionTime = System.nanoTime() - taskStartTime;
                    task.recordExecution(executionTime);
                    task.complete();

                    successCount++;
                } catch (Throwable t) {
                    Debug.log(java.util.logging.Level.SEVERE,
                            "Error executing task in batch " + name + ": " + t.getMessage());
                    task.completeExceptionally(t);
                } finally {
                    task.markRunning(false);
                }
            }

            // Track batch metrics
            long batchTotalTime = System.nanoTime() - batchStartTime;
            int totalProcessed = processedTaskCount.addAndGet(tasksToExecute.size());

            if (Debug.isDebugEnabled()) {
                Debug.debug(String.format("Batch %s processed %d/%d tasks in %.2fms (avg: %.2fms per task)",
                        name, successCount, tasksToExecute.size(),
                        batchTotalTime / 1_000_000.0,
                        tasksToExecute.isEmpty() ? 0 : (batchTotalTime / tasksToExecute.size()) / 1_000_000.0));
            }
        }
    }

    /**
     * Class for tracking information about tasks in batches.
     */
    private static class BatchTaskInfo {
        private final String batchName;
        private final TaskImpl task;
        private final Future<?> future;

        public BatchTaskInfo(String batchName, TaskImpl task) {
            this(batchName, task, null);
        }

        public BatchTaskInfo(String batchName, TaskImpl task, Future<?> future) {
            this.batchName = batchName;
            this.task = task;
            this.future = future;
        }
    }
}