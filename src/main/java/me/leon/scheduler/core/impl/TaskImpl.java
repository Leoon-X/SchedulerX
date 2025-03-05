package me.leon.scheduler.core.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import me.leon.scheduler.api.Task;
import me.leon.scheduler.util.ConcurrencyUtil;

/**
 * Implementation of the Task interface.
 * Handles task execution, monitoring, and lifecycle management.
 */
public class TaskImpl implements Task, Runnable {

    private final long taskId;
    private final Runnable action;
    private final boolean repeating;
    private final CompletableFuture<Void> completionFuture;
    private final List<Runnable> completionCallbacks;
    private final List<Consumer<Throwable>> exceptionHandlers;

    private final AtomicBoolean cancelled;
    private final AtomicBoolean running;
    private final AtomicBoolean done;
    private final AtomicLong lastExecutionTime;
    private final AtomicLong totalExecutionTime;
    private final AtomicInteger executionCount;

    private int bukkitTaskId;

    /**
     * Creates a new task with the specified ID and action.
     *
     * @param taskId The unique ID for this task
     * @param action The action to execute
     * @param repeating Whether this task repeats
     */
    public TaskImpl(long taskId, Runnable action, boolean repeating) {
        this.taskId = taskId;
        this.action = action;
        this.repeating = repeating;
        this.completionFuture = new CompletableFuture<>();
        this.completionCallbacks = new ArrayList<>(2); // Most tasks will have 0-2 callbacks
        this.exceptionHandlers = new ArrayList<>(1);

        this.cancelled = new AtomicBoolean(false);
        this.running = new AtomicBoolean(false);
        this.done = new AtomicBoolean(false);
        this.lastExecutionTime = new AtomicLong(0);
        this.totalExecutionTime = new AtomicLong(0);
        this.executionCount = new AtomicInteger(0);

        this.bukkitTaskId = -1;
    }

    /**
     * Executes the task action.
     */
    @Override
    public void run() {
        if (action != null) {
            action.run();
        }
    }

    /**
     * Records execution time and increments execution count.
     *
     * @param executionTimeNanos The execution time in nanoseconds
     */
    public void recordExecution(long executionTimeNanos) {
        lastExecutionTime.set(executionTimeNanos);
        totalExecutionTime.addAndGet(executionTimeNanos);
        executionCount.incrementAndGet();
    }

    /**
     * Marks the task as running or not running.
     *
     * @param isRunning Whether the task is currently running
     */
    public void markRunning(boolean isRunning) {
        running.set(isRunning);
    }

    /**
     * Marks the task as cancelled.
     */
    public void markCancelled() {
        cancelled.set(true);
    }

    /**
     * Completes the task normally.
     */
    public void complete() {
        if (done.compareAndSet(false, true)) {
            completionFuture.complete(null);
            for (Runnable callback : completionCallbacks) {
                try {
                    callback.run();
                } catch (Exception e) {
                    // Log but don't propagate callback exceptions
                }
            }
        }
    }

    /**
     * Completes the task exceptionally.
     *
     * @param throwable The exception that caused the failure
     */
    public void completeExceptionally(Throwable throwable) {
        if (done.compareAndSet(false, true)) {
            completionFuture.completeExceptionally(throwable);
            for (Consumer<Throwable> handler : exceptionHandlers) {
                try {
                    handler.accept(throwable);
                } catch (Exception e) {
                    // Log but don't propagate handler exceptions
                }
            }
        }
    }

    /**
     * Sets the Bukkit task ID associated with this task.
     *
     * @param bukkitTaskId The Bukkit task ID
     */
    public void setBukkitTaskId(int bukkitTaskId) {
        this.bukkitTaskId = bukkitTaskId;
    }

    /**
     * Gets the Bukkit task ID associated with this task.
     *
     * @return The Bukkit task ID
     */
    public int getBukkitTaskId() {
        return bukkitTaskId;
    }

    /**
     * Checks if this task is a repeating task.
     *
     * @return true if this task repeats, false otherwise
     */
    public boolean isRepeating() {
        return repeating;
    }

    // Task interface implementations

    @Override
    public boolean cancel() {
        if (cancelled.compareAndSet(false, true)) {
            if (!done.get()) {
                complete();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isDone() {
        return done.get();
    }

    @Override
    public long getTaskId() {
        return taskId;
    }

    @Override
    public CompletableFuture<Void> getCompletionFuture() {
        return completionFuture;
    }

    @Override
    public Task thenRun(Runnable action) {
        if (action != null) {
            if (done.get()) {
                // If already done, run immediately
                action.run();
            } else {
                completionCallbacks.add(action);
            }
        }
        return this;
    }

    @Override
    public Task exceptionally(Consumer<Throwable> action) {
        if (action != null) {
            exceptionHandlers.add(action);
        }
        return this;
    }

    @Override
    public long getLastExecutionTimeNanos() {
        return lastExecutionTime.get();
    }

    @Override
    public long getAverageExecutionTimeNanos() {
        int count = executionCount.get();
        return count > 0 ? totalExecutionTime.get() / count : 0;
    }

    @Override
    public int getExecutionCount() {
        return executionCount.get();
    }
}