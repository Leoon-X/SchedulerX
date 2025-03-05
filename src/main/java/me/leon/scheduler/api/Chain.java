package me.leon.scheduler.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Represents a chain of tasks that execute in sequence.
 * Provides a fluent API for building task chains.
 */
public interface Chain {

    /**
     * Starts execution of the task chain.
     *
     * @return A task representing the entire chain
     */
    Task execute();

    /**
     * Cancels all remaining tasks in the chain.
     *
     * @return true if the chain was cancelled, false otherwise
     */
    boolean cancel();

    /**
     * Gets a future that completes when the entire chain completes.
     *
     * @return A CompletableFuture that completes when the chain finishes
     */
    CompletableFuture<Void> getCompletionFuture();

    /**
     * Builder for creating task chains with a fluent API.
     */
    interface Builder {

        /**
         * Adds a synchronous task to the chain.
         *
         * @param task The task to add
         * @return This builder instance for chaining
         */
        Builder sync(Runnable task);

        /**
         * Adds an asynchronous task to the chain.
         *
         * @param task The task to add
         * @return This builder instance for chaining
         */
        Builder async(Runnable task);

        /**
         * Adds a delay before the next task.
         *
         * @param ticks Delay in server ticks
         * @return This builder instance for chaining
         */
        Builder delay(long ticks);

        /**
         * Adds a delay before the next task.
         *
         * @param time Amount of time
         * @param unit Time unit
         * @return This builder instance for chaining
         */
        Builder delay(long time, TimeUnit unit);

        /**
         * Adds a task that executes only if a condition is met.
         *
         * @param condition The condition to check
         * @param task The task to execute if condition is true
         * @return This builder instance for chaining
         */
        Builder conditional(Supplier<Boolean> condition, Runnable task);

        /**
         * Adds a task that executes repeatedly until a condition is met.
         *
         * @param condition The condition to check after each execution
         * @param task The task to repeatedly execute
         * @param delayBetween Delay between executions in ticks
         * @return This builder instance for chaining
         */
        Builder repeatUntil(Supplier<Boolean> condition, Runnable task, long delayBetween);

        /**
         * Adds a task that supplies a value to be used by later tasks.
         *
         * @param <T> The type of value to supply
         * @param supplier The supplier of the value
         * @return A TypedBuilder for this value type
         */
        <T> TypedBuilder<T> supply(Supplier<T> supplier);

        /**
         * Builds and returns the final Chain instance.
         *
         * @return The configured Chain instance
         */
        Chain build();
    }

    /**
     * Specialized builder for chains that produce and consume values.
     *
     * @param <T> The type of value being passed through the chain
     */
    interface TypedBuilder<T> {

        /**
         * Adds a task that consumes the previous value.
         *
         * @param consumer The consumer of the value
         * @return The regular Builder to continue adding tasks
         */
        Builder consume(Consumer<T> consumer);

        /**
         * Adds a task that transforms the value.
         *
         * @param <R> The type of the new value
         * @param function The transformation function
         * @return A new TypedBuilder for the transformed value type
         */
        <R> TypedBuilder<R> transform(Function<T, R> function);

        /**
         * Adds a task that executes conditionally based on the value.
         *
         * @param predicate The predicate to test the value
         * @param task The task to execute if predicate returns true
         * @return The regular Builder to continue adding tasks
         */
        Builder filter(java.util.function.Predicate<T> predicate, Consumer<T> task);
    }
}