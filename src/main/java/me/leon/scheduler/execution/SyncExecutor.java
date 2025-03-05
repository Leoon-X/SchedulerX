package me.leon.scheduler.execution;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import me.leon.scheduler.api.Task;
import me.leon.scheduler.core.impl.TaskImpl;
import me.leon.scheduler.util.ConcurrencyUtil;
import me.leon.scheduler.util.Debug;

/**
 * Execution strategy for tasks that need to run on the main server thread.
 * Uses Bukkit's scheduler to ensure tasks execute synchronously.
 */
public class SyncExecutor implements ExecutionStrategy {

    private final Plugin plugin;
    private final BukkitScheduler scheduler;

    /**
     * Creates a new synchronous executor.
     *
     * @param plugin The owning plugin
     */
    public SyncExecutor(Plugin plugin) {
        this.plugin = plugin;
        this.scheduler = Bukkit.getScheduler();
    }

    @Override
    public Task execute(Runnable runnable, long taskId) {
        TaskImpl task = new TaskImpl(taskId, runnable, false);

        try {
            BukkitTask bukkitTask = scheduler.runTask(plugin, () -> executeTaskSafely(task));
            task.setBukkitTaskId(bukkitTask.getTaskId());
            return task;
        } catch (Exception e) {
            Debug.log(java.util.logging.Level.SEVERE, "Error scheduling sync task: " + e.getMessage());
            task.completeExceptionally(e);
            return task;
        }
    }

    @Override
    public Task executeLater(Runnable runnable, long taskId, long delayTicks) {
        TaskImpl task = new TaskImpl(taskId, runnable, false);

        try {
            BukkitTask bukkitTask = scheduler.runTaskLater(plugin, () -> executeTaskSafely(task), delayTicks);
            task.setBukkitTaskId(bukkitTask.getTaskId());
            return task;
        } catch (Exception e) {
            Debug.log(java.util.logging.Level.SEVERE, "Error scheduling delayed sync task: " + e.getMessage());
            task.completeExceptionally(e);
            return task;
        }
    }

    @Override
    public Task executeRepeating(Runnable runnable, long taskId, long delayTicks, long periodTicks) {
        TaskImpl task = new TaskImpl(taskId, runnable, true);

        try {
            BukkitTask bukkitTask = scheduler.runTaskTimer(plugin, () -> executeTaskSafely(task),
                    delayTicks, periodTicks);
            task.setBukkitTaskId(bukkitTask.getTaskId());
            return task;
        } catch (Exception e) {
            Debug.log(java.util.logging.Level.SEVERE, "Error scheduling repeating sync task: " + e.getMessage());
            task.completeExceptionally(e);
            return task;
        }
    }

    @Override
    public boolean cancelTask(long taskId) {
        try {
            scheduler.cancelTask((int) taskId);
            return true;
        } catch (Exception e) {
            Debug.log(java.util.logging.Level.WARNING, "Error cancelling sync task: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isCurrentThread() {
        return ConcurrencyUtil.isMainThread();
    }

    @Override
    public String getName() {
        return "SyncExecutor";
    }

    /**
     * Executes a task with proper safety measures.
     *
     * @param task The task to execute
     */
    private void executeTaskSafely(TaskImpl task) {
        if (task.isCancelled()) {
            return;
        }

        task.markRunning(true);
        long startTime = System.nanoTime();

        try {
            task.run();
            long executionTime = System.nanoTime() - startTime;
            task.recordExecution(executionTime);

            if (Debug.isDebugEnabled() && executionTime > 5_000_000) { // Log tasks taking more than 5ms
                Debug.debug("Sync task " + task.getTaskId() + " executed in " +
                        (executionTime / 1_000_000.0) + "ms");
            }
        } catch (Throwable t) {
            Debug.log(java.util.logging.Level.SEVERE, "Error executing sync task " + task.getTaskId() +
                    ": " + t.getMessage());
            task.completeExceptionally(t);
        } finally {
            task.markRunning(false);

            if (!task.isRepeating() && !task.isCancelled()) {
                task.complete();
            }
        }
    }
}