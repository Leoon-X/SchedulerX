package me.leon.scheduler.execution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.bukkit.plugin.Plugin;

import me.leon.scheduler.api.Task;
import me.leon.scheduler.core.ThreadPoolManager;
import me.leon.scheduler.core.impl.TaskImpl;
import me.leon.scheduler.util.ConcurrencyUtil;
import me.leon.scheduler.util.Debug;
import me.leon.scheduler.util.TickUtil;

/**
 * Execution strategy for tasks that run asynchronously.
 * Uses optimized thread pools to efficiently execute tasks off the main thread.
 */
public class AsyncExecutor implements ExecutionStrategy {

    private final Plugin plugin;
    private final ThreadPoolManager poolManager;
    private final Map<Long, Future<?>> taskFutures;
    private final ScheduledExecutorService scheduler;

    /**
     * Creates a new asynchronous executor.
     *
     * @param plugin The owning plugin
     * @param poolManager The thread pool manager
     */
    public AsyncExecutor(Plugin plugin, ThreadPoolManager poolManager) {
        this.plugin = plugin;
        this.poolManager = poolManager;
        this.taskFutures = new ConcurrentHashMap<>();
        this.scheduler = poolManager.getScheduledPool();
    }

    @Override
    public Task execute(Runnable runnable, long taskId) {
        TaskImpl task = new TaskImpl(taskId, runnable, false);

        try {
            // Use the appropriate pool for the task
            Future<?> future = poolManager.getPool(ThreadPoolManager.PoolType.CPU_BOUND)
                    .submit(() -> executeTaskSafely(task));

            taskFutures.put(taskId, future);
            return task;
        } catch (Exception e) {
            Debug.log(java.util.logging.Level.SEVERE, "Error scheduling async task: " + e.getMessage());
            task.completeExceptionally(e);
            return task;
        }
    }

    @Override
    public Task executeLater(Runnable runnable, long taskId, long delayTicks) {
        TaskImpl task = new TaskImpl(taskId, runnable, false);

        try {
            // Convert ticks to milliseconds for the scheduler
            long delayMillis = TickUtil.ticksToMilliseconds(delayTicks);

            ScheduledFuture<?> future = scheduler.schedule(
                    () -> executeTaskSafely(task),
                    delayMillis,
                    TimeUnit.MILLISECONDS);

            taskFutures.put(taskId, future);
            return task;
        } catch (Exception e) {
            Debug.log(java.util.logging.Level.SEVERE, "Error scheduling delayed async task: " + e.getMessage());
            task.completeExceptionally(e);
            return task;
        }
    }

    @Override
    public Task executeRepeating(Runnable runnable, long taskId, long delayTicks, long periodTicks) {
        TaskImpl task = new TaskImpl(taskId, runnable, true);

        try {
            // Convert ticks to milliseconds for the scheduler
            long delayMillis = TickUtil.ticksToMilliseconds(delayTicks);
            long periodMillis = TickUtil.ticksToMilliseconds(periodTicks);

            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                    () -> executeTaskSafely(task),
                    delayMillis,
                    periodMillis,
                    TimeUnit.MILLISECONDS);

            taskFutures.put(taskId, future);
            return task;
        } catch (Exception e) {
            Debug.log(java.util.logging.Level.SEVERE, "Error scheduling repeating async task: " + e.getMessage());
            task.completeExceptionally(e);
            return task;
        }
    }

    @Override
    public boolean cancelTask(long taskId) {
        Future<?> future = taskFutures.remove(taskId);
        if (future != null) {
            future.cancel(false); // Don't interrupt if running
            return true;
        }
        return false;
    }

    @Override
    public boolean isCurrentThread() {
        return !ConcurrencyUtil.isMainThread();
    }

    @Override
    public String getName() {
        return "AsyncExecutor";
    }

    /**
     * Executes a task with proper safety measures.
     *
     * @param task The task to execute
     */
    private void executeTaskSafely(TaskImpl task) {
        if (task.isCancelled()) {
            taskFutures.remove(task.getTaskId());
            return;
        }

        task.markRunning(true);
        long startTime = System.nanoTime();

        try {
            task.run();
            long executionTime = System.nanoTime() - startTime;
            task.recordExecution(executionTime);

            if (Debug.isDebugEnabled() && executionTime > 10_000_000) { // Log tasks taking more than 10ms
                Debug.debug("Async task " + task.getTaskId() + " executed in " +
                        (executionTime / 1_000_000.0) + "ms");
            }
        } catch (Throwable t) {
            Debug.log(java.util.logging.Level.SEVERE, "Error executing async task " + task.getTaskId() +
                    ": " + t.getMessage());
            task.completeExceptionally(t);
        } finally {
            task.markRunning(false);

            if (!task.isRepeating() && !task.isCancelled()) {
                task.complete();
                taskFutures.remove(task.getTaskId());
            }
        }
    }
}