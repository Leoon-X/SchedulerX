package me.leon.scheduler.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Utility class for concurrency and thread-safety operations.
 * Provides optimized methods for common concurrency patterns.
 */
public final class ConcurrencyUtil {

    private static final int SPIN_TRIES = 100;

    private ConcurrencyUtil() {
        // Private constructor to prevent instantiation
    }

    /**
     * Executes a task asynchronously with reduced object allocation overhead.
     *
     * @param <T>      Return type of the task
     * @param task     The task to execute
     * @param executor The executor to use, or null for common ForkJoinPool
     * @return CompletableFuture with the task result
     */
    public static <T> CompletableFuture<T> runOptimizedAsync(Supplier<T> task, Executor executor) {
        if (executor == null) {
            executor = ForkJoinPool.commonPool();
        }

        // Using atomic reference to avoid unnecessary allocations
        AtomicReference<CompletableFuture<T>> futureRef = new AtomicReference<>(new CompletableFuture<>());

        executor.execute(() -> {
            try {
                T result = task.get();
                futureRef.get().complete(result);
            } catch (Throwable ex) {
                futureRef.get().completeExceptionally(ex);
            }
        });

        return futureRef.get();
    }

    /**
     * Thread-safe way to update a value with minimal locking.
     *
     * @param <T>           Type of the reference
     * @param reference     Atomic reference to update
     * @param updateFunc    Function to compute the new value
     * @return              The updated value
     */
    public static <T> T updateWithRetry(AtomicReference<T> reference, java.util.function.Function<T, T> updateFunc) {
        T oldValue, newValue;
        int spinCount = 0;

        do {
            oldValue = reference.get();
            newValue = updateFunc.apply(oldValue);

            // Use spin waiting for a small number of attempts before yielding
            if (spinCount++ > SPIN_TRIES) {
                Thread.yield();
                spinCount = 0;
            }
        } while (!reference.compareAndSet(oldValue, newValue));

        return newValue;
    }

    /**
     * Determines if the current thread is the Bukkit server's main thread.
     *
     * @return true if called from the main server thread
     */
    public static boolean isMainThread() {
        return Thread.currentThread().getName().equals("Server thread");
    }

    /**
     * Creates a thread-local object pool to reduce allocation overhead.
     *
     * @param <T>           Type of the pooled object
     * @param initializer   Function to create a new instance
     * @param resetter      Function to reset an instance for reuse
     * @return              A supplier that provides pooled objects
     */
    public static <T> Supplier<T> createThreadLocalPool(Supplier<T> initializer, java.util.function.Consumer<T> resetter) {
        ThreadLocal<T> threadLocal = ThreadLocal.withInitial(initializer);

        return () -> {
            T obj = threadLocal.get();
            if (resetter != null) {
                resetter.accept(obj);
            }
            return obj;
        };
    }
}