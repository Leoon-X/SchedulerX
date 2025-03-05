package me.leon.scheduler.execution;

import me.leon.scheduler.api.Task;

/**
 * Interface for different task execution strategies.
 * Allows for pluggable execution mechanisms based on task requirements.
 */
public interface ExecutionStrategy {

    /**
     * Executes a task according to the strategy.
     *
     * @param runnable The task to execute
     * @param taskId   The unique ID of the task
     * @return A cancellable task handle
     */
    Task execute(Runnable runnable, long taskId);

    /**
     * Executes a delayed task according to the strategy.
     *
     * @param runnable   The task to execute
     * @param taskId     The unique ID of the task
     * @param delayTicks Delay in server ticks
     * @return A cancellable task handle
     */
    Task executeLater(Runnable runnable, long taskId, long delayTicks);

    /**
     * Executes a repeating task according to the strategy.
     *
     * @param runnable    The task to execute
     * @param taskId      The unique ID of the task
     * @param delayTicks  Initial delay in server ticks
     * @param periodTicks Period between executions in server ticks
     * @return A cancellable task handle
     */
    Task executeRepeating(Runnable runnable, long taskId, long delayTicks, long periodTicks);

    /**
     * Cancels a task managed by this strategy.
     *
     * @param taskId The ID of the task to cancel
     * @return true if the task was found and cancelled, false otherwise
     */
    boolean cancelTask(long taskId);

    /**
     * Checks if a task is running on the current thread.
     *
     * @return true if the current thread is managed by this execution strategy
     */
    boolean isCurrentThread();

    /**
     * Gets the name of this execution strategy.
     *
     * @return The strategy name
     */
    String getName();
}