package me.leon.scheduler.optimization;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import me.leon.scheduler.util.Debug;

/**
 * Generic object pooling system to reduce garbage collection pressure.
 * Reuses objects instead of creating new ones.
 *
 * @param <T> The type of object to pool
 */
public class ObjectPool<T> {

    private final Queue<T> pool;
    private final Supplier<T> factory;
    private final Consumer<T> recycler;
    private final int maxPoolSize;
    private final AtomicInteger createdCount;
    private final AtomicInteger borrowedCount;
    private final AtomicInteger returnedCount;
    private final AtomicInteger discardedCount;

    /**
     * Creates a new object pool.
     *
     * @param factory The factory function to create new instances
     * @param recycler The recycler function to reset instances for reuse
     * @param initialSize The initial number of objects to create
     * @param maxPoolSize The maximum number of objects to keep in the pool
     */
    public ObjectPool(Supplier<T> factory, Consumer<T> recycler, int initialSize, int maxPoolSize) {
        if (factory == null) {
            throw new IllegalArgumentException("Factory cannot be null");
        }

        this.pool = new ConcurrentLinkedQueue<>();
        this.factory = factory;
        this.recycler = recycler;
        this.maxPoolSize = maxPoolSize;
        this.createdCount = new AtomicInteger(0);
        this.borrowedCount = new AtomicInteger(0);
        this.returnedCount = new AtomicInteger(0);
        this.discardedCount = new AtomicInteger(0);

        // Pre-populate the pool
        for (int i = 0; i < initialSize; i++) {
            T obj = factory.get();
            if (obj != null) {
                pool.offer(obj);
                createdCount.incrementAndGet();
            }
        }
    }

    /**
     * Borrows an object from the pool, creating a new one if needed.
     *
     * @return An object instance
     */
    public T borrow() {
        // Try to get an existing object from the pool
        T obj = pool.poll();

        // If pool is empty, create a new object
        if (obj == null) {
            obj = factory.get();
            createdCount.incrementAndGet();

            if (Debug.isDebugEnabled() && createdCount.get() % 100 == 0) {
                Debug.debug("Object pool created " + createdCount.get() + " instances");
            }
        }

        borrowedCount.incrementAndGet();
        return obj;
    }

    /**
     * Returns an object to the pool for reuse.
     *
     * @param obj The object to return
     */
    public void returnObject(T obj) {
        if (obj == null) {
            return;
        }

        returnedCount.incrementAndGet();

        // Only keep a limited number of objects in the pool
        if (pool.size() < maxPoolSize) {
            // Recycle the object if a recycler is provided
            if (recycler != null) {
                try {
                    recycler.accept(obj);
                } catch (Exception e) {
                    Debug.log(java.util.logging.Level.WARNING,
                            "Error recycling pooled object: " + e.getMessage());
                    discardedCount.incrementAndGet();
                    return;
                }
            }

            // Add the object back to the pool
            pool.offer(obj);
        } else {
            // Pool is full, discard the object
            discardedCount.incrementAndGet();
        }
    }

    /**
     * Gets the current size of the pool.
     *
     * @return The number of objects currently in the pool
     */
    public int getPoolSize() {
        return pool.size();
    }

    /**
     * Gets the total number of objects created by this pool.
     *
     * @return The number of created objects
     */
    public int getCreatedCount() {
        return createdCount.get();
    }

    /**
     * Gets the total number of objects borrowed from this pool.
     *
     * @return The number of borrowed objects
     */
    public int getBorrowedCount() {
        return borrowedCount.get();
    }

    /**
     * Gets the total number of objects returned to this pool.
     *
     * @return The number of returned objects
     */
    public int getReturnedCount() {
        return returnedCount.get();
    }

    /**
     * Gets the total number of objects discarded by this pool.
     *
     * @return The number of discarded objects
     */
    public int getDiscardedCount() {
        return discardedCount.get();
    }

    /**
     * Clears the pool, releasing all pooled objects.
     * Does not affect currently borrowed objects.
     */
    public void clear() {
        int size = pool.size();
        pool.clear();
        discardedCount.addAndGet(size);
    }

    /**
     * Executes a function with a borrowed object and automatically returns it to the pool.
     *
     * @param <R> The return type of the function
     * @param function The function to execute
     * @return The result of the function
     */
    public <R> R execute(Function<T, R> function) {
        T obj = borrow();
        try {
            return function.apply(obj);
        } finally {
            returnObject(obj);
        }
    }

    /**
     * Executes a consumer with a borrowed object and automatically returns it to the pool.
     *
     * @param consumer The consumer to execute
     */
    public void execute(Consumer<T> consumer) {
        T obj = borrow();
        try {
            consumer.accept(obj);
        } finally {
            returnObject(obj);
        }
    }

    /**
     * Functional interface for executing with a pooled object.
     *
     * @param <T> The pooled object type
     * @param <R> The return type
     */
    @FunctionalInterface
    public interface Function<T, R> {
        R apply(T obj);
    }
}