package me.leon.scheduler.core;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;

import me.leon.scheduler.api.Task;
import me.leon.scheduler.core.impl.TaskImpl;
import me.leon.scheduler.util.ConcurrencyUtil;
import me.leon.scheduler.util.Debug;
import me.leon.scheduler.util.TickUtil;

/**
 * Core class responsible for managing and optimizing task execution.
 * Handles task scheduling, execution tracking, and lifecycle management.
 */
public final class TaskManager {

    private static final AtomicLong TASK_ID_GENERATOR = new AtomicLong(0);
    private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    private final Plugin plugin;
    private final Map<Long, TaskImpl> activeTasks;
    private final Set<Long> pendingCancellations;
    private final ScheduledExecutorService asyncExecutor;
    private final BukkitScheduler bukkitScheduler;

    /**
     * Creates a new TaskManager for the specified plugin.
     *
     * @param plugin The owning plugin
     */
    public TaskManager(Plugin plugin) {
        this.plugin = plugin;
        this.activeTasks = new ConcurrentHashMap<>();
        this.pendingCancellations = ConcurrentHashMap.newKeySet();
        this.bukkitScheduler = Bukkit.getScheduler();

        // Create an optimized thread pool for async tasks
        ThreadFactory threadFactory = r -> {
            Thread thread = new Thread(r, "Scheduler-Worker-" + plugin.getName());
            thread.setDaemon(true);
            return thread;
        };

        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(CORE_POOL_SIZE, threadFactory);
        executor.setRemoveOnCancelPolicy(true); // Reduce memory pressure from cancelled tasks
        executor.setMaximumPoolSize(CORE_POOL_SIZE * 2); // Allow some growth during heavy load
        this.asyncExecutor = executor;

        // Start housekeeping task to clean up completed tasks
        startHousekeeping();
    }

    /**
     * Generates a unique task ID.
     *
     * @return A new unique task ID
     */
    public long generateTaskId() {
        return TASK_ID_GENERATOR.incrementAndGet();
    }

    /**
     * Runs a task synchronously on the main server thread.
     *
     * @param runnable The task to run
     * @return The created task instance
     */
    public Task runSync(Runnable runnable) {
        long taskId = generateTaskId();
        TaskImpl task = new TaskImpl(taskId, runnable, false);
        activeTasks.put(taskId, task);

        int bukkitTaskId = bukkitScheduler.runTask(plugin, () -> executeTask(task)).getTaskId();
        task.setBukkitTaskId(bukkitTaskId);

        return task;
    }

    /**
     * Runs a task asynchronously off the main server thread.
     *
     * @param runnable The task to run
     * @return The created task instance
     */
    public Task runAsync(Runnable runnable) {
        long taskId = generateTaskId();
        TaskImpl task = new TaskImpl(taskId, runnable, false);
        activeTasks.put(taskId, task);

        int bukkitTaskId = bukkitScheduler.runTaskAsynchronously(plugin, () -> executeTask(task)).getTaskId();
        task.setBukkitTaskId(bukkitTaskId);

        return task;
    }

    /**
     * Runs a task on the main server thread after a delay.
     *
     * @param runnable The task to run
     * @param delayTicks The delay in server ticks
     * @return The created task instance
     */
    public Task runLater(Runnable runnable, long delayTicks) {
        long taskId = generateTaskId();
        TaskImpl task = new TaskImpl(taskId, runnable, false);
        activeTasks.put(taskId, task);

        int bukkitTaskId = bukkitScheduler.runTaskLater(plugin, () -> executeTask(task), delayTicks).getTaskId();
        task.setBukkitTaskId(bukkitTaskId);

        return task;
    }

    /**
     * Runs a task asynchronously after a delay.
     *
     * @param runnable The task to run
     * @param delayTicks The delay in server ticks
     * @return The created task instance
     */
    public Task runLaterAsync(Runnable runnable, long delayTicks) {
        long taskId = generateTaskId();
        TaskImpl task = new TaskImpl(taskId, runnable, false);
        activeTasks.put(taskId, task);

        int bukkitTaskId = bukkitScheduler.runTaskLaterAsynchronously(plugin, () -> executeTask(task), delayTicks).getTaskId();
        task.setBukkitTaskId(bukkitTaskId);

        return task;
    }

    /**
     * Runs a task on the main server thread repeatedly.
     *
     * @param runnable The task to run
     * @param delayTicks Initial delay in ticks
     * @param periodTicks Period between executions in ticks
     * @return The created task instance
     */
    public Task runTimer(Runnable runnable, long delayTicks, long periodTicks) {
        long taskId = generateTaskId();
        TaskImpl task = new TaskImpl(taskId, runnable, true);
        activeTasks.put(taskId, task);

        int bukkitTaskId = bukkitScheduler.runTaskTimer(plugin, () -> executeTask(task), delayTicks, periodTicks).getTaskId();
        task.setBukkitTaskId(bukkitTaskId);

        return task;
    }

    /**
     * Runs a task asynchronously repeatedly.
     *
     * @param runnable The task to run
     * @param delayTicks Initial delay in ticks
     * @param periodTicks Period between executions in ticks
     * @return The created task instance
     */
    public Task runTimerAsync(Runnable runnable, long delayTicks, long periodTicks) {
        long taskId = generateTaskId();
        TaskImpl task = new TaskImpl(taskId, runnable, true);
        activeTasks.put(taskId, task);

        int bukkitTaskId = bukkitScheduler.runTaskTimerAsynchronously(plugin, () -> executeTask(task),
                delayTicks, periodTicks).getTaskId();
        task.setBukkitTaskId(bukkitTaskId);

        return task;
    }

    /**
     * Executes a task and handles timing, errors, and completion.
     *
     * @param task The task to execute
     */
    private void executeTask(TaskImpl task) {
        if (task.isCancelled() || pendingCancellations.contains(task.getTaskId())) {
            cleanupTask(task);
            return;
        }

        task.markRunning(true);
        long startTime = System.nanoTime();

        try {
            task.run();
            long executionTime = System.nanoTime() - startTime;
            task.recordExecution(executionTime);

            if (Debug.isDebugEnabled()) {
                Debug.debug("Task " + task.getTaskId() + " executed in " +
                        (executionTime / 1_000_000.0) + "ms");
            }
        } catch (Throwable t) {
            Debug.log(java.util.logging.Level.SEVERE, "Error executing task " + task.getTaskId() + ": " + t.getMessage());
            task.completeExceptionally(t);
        } finally {
            task.markRunning(false);

            if (!task.isRepeating() && !task.isCancelled()) {
                task.complete();
                cleanupTask(task);
            }
        }
    }

    /**
     * Cleans up a task, removing it from tracking collections.
     *
     * @param task The task to clean up
     */
    private void cleanupTask(TaskImpl task) {
        activeTasks.remove(task.getTaskId());
        pendingCancellations.remove(task.getTaskId());

        if (task.getBukkitTaskId() >= 0) {
            try {
                bukkitScheduler.cancelTask(task.getBukkitTaskId());
            } catch (Exception e) {
                // Task might already be cancelled, ignore
            }
        }
    }

    /**
     * Cancels a specific task.
     *
     * @param taskId The ID of the task to cancel
     * @return true if the task was found and cancelled, false otherwise
     */
    public boolean cancelTask(long taskId) {
        TaskImpl task = activeTasks.get(taskId);
        if (task == null) {
            return false;
        }

        pendingCancellations.add(taskId);
        task.markCancelled();

        if (task.getBukkitTaskId() >= 0) {
            bukkitScheduler.cancelTask(task.getBukkitTaskId());
        }

        return true;
    }

    /**
     * Cancels all tasks managed by this TaskManager.
     */
    public void cancelAllTasks() {
        for (TaskImpl task : activeTasks.values()) {
            cancelTask(task.getTaskId());
        }
    }

    /**
     * Starts the housekeeping task to clean up completed and cancelled tasks.
     */
    private void startHousekeeping() {
        asyncExecutor.scheduleAtFixedRate(() -> {
            try {
                // Clean up tasks that are done but still in the active map
                activeTasks.entrySet().removeIf(entry -> {
                    TaskImpl task = entry.getValue();
                    return task.isDone() || task.isCancelled();
                });

                // Clear any stale pending cancellations
                pendingCancellations.removeIf(taskId -> !activeTasks.containsKey(taskId));

            } catch (Exception e) {
                Debug.log(java.util.logging.Level.WARNING, "Error in housekeeping: " + e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Shuts down the TaskManager and releases resources.
     */
    public void shutdown() {
        cancelAllTasks();
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Gets the current number of active tasks.
     *
     * @return The number of active tasks
     */
    public int getActiveTaskCount() {
        return activeTasks.size();
    }

    /**
     * Gets the plugin associated with this TaskManager.
     *
     * @return The plugin instance
     */
    public Plugin getPlugin() {
        return plugin;
    }
}