package me.leon.scheduler.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Represents a scheduled task that can be cancelled and monitored.
 * Provides methods to manage task execution and lifecycle.
 */
public interface Task {

    /**
     * Cancels this task, preventing it from executing if it hasn't started yet.
     * If the task is already running, the behavior depends on the implementation.
     *
     * @return true if the task was cancelled, false if it couldn't be cancelled
     */
    boolean cancel();

    /**
     * Checks if this task has been cancelled.
     *
     * @return true if the task has been cancelled
     */
    boolean isCancelled();

    /**
     * Checks if this task is currently running.
     *
     * @return true if the task is currently executing
     */
    boolean isRunning();

    /**
     * Checks if this task has completed execution.
     *
     * @return true if the task has finished executing
     */
    boolean isDone();

    /**
     * Gets the unique identifier for this task.
     *
     * @return The task's unique ID
     */
    long getTaskId();

    /**
     * Gets a future that completes when this task completes.
     *
     * @return A CompletableFuture that completes when the task finishes
     */
    CompletableFuture<Void> getCompletionFuture();

    /**
     * Executes an action when this task completes.
     *
     * @param action The action to execute when the task completes
     * @return This task instance for chaining
     */
    Task thenRun(Runnable action);

    /**
     * Executes an action if this task fails with an exception.
     *
     * @param action The action to execute with the causing exception
     * @return This task instance for chaining
     */
    Task exceptionally(Consumer<Throwable> action);

    /**
     * Gets the most recent execution time of this task in nanoseconds.
     * Returns 0 if the task hasn't executed yet.
     *
     * @return The execution time in nanoseconds
     */
    long getLastExecutionTimeNanos();

    /**
     * Gets the average execution time of this task in nanoseconds.
     * Returns 0 if the task hasn't executed yet.
     *
     * @return The average execution time in nanoseconds
     */
    long getAverageExecutionTimeNanos();

    /**
     * Gets the number of times this task has been executed.
     *
     * @return The execution count
     */
    int getExecutionCount();
}