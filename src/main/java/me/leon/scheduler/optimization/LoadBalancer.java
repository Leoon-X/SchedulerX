package me.leon.scheduler.optimization;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import me.leon.scheduler.core.TPSMonitor;
import me.leon.scheduler.core.ThreadPoolManager;
import me.leon.scheduler.core.ThreadPoolManager.PoolType;
import me.leon.scheduler.util.Debug;

/**
 * Balances task load across available threads and resources.
 * Helps prevent overloading specific threads or resources.
 */
public class LoadBalancer {

    private final ThreadPoolManager poolManager;
    private final TPSMonitor tpsMonitor;
    private final Map<PoolType, AtomicInteger> activeTaskCounts;
    private final Map<String, ResourceUsage> resourceUsage;

    // Dynamic load balancing parameters
    private double cpuLoadFactor = 1.0;
    private double ioLoadFactor = 1.0;
    private int maxConcurrentTasksPerResource = 8;

    /**
     * Creates a new load balancer.
     *
     * @param poolManager The thread pool manager
     * @param tpsMonitor The TPS monitor
     */
    public LoadBalancer(ThreadPoolManager poolManager, TPSMonitor tpsMonitor) {
        this.poolManager = poolManager;
        this.tpsMonitor = tpsMonitor;
        this.activeTaskCounts = new HashMap<>();
        this.resourceUsage = new ConcurrentHashMap<>();

        // Initialize counters for each pool type
        for (PoolType type : PoolType.values()) {
            activeTaskCounts.put(type, new AtomicInteger(0));
        }
    }

    /**
     * Gets the most appropriate executor for the given task characteristics.
     *
     * @param isCpuIntensive Whether the task is CPU-intensive
     * @param isIoIntensive Whether the task is I/O-intensive
     * @param requiresLowLatency Whether the task requires low latency
     * @return The most appropriate executor service
     */
    public ExecutorService getExecutorFor(boolean isCpuIntensive, boolean isIoIntensive, boolean requiresLowLatency) {
        if (requiresLowLatency) {
            // Tasks requiring immediate execution
            incrementTaskCount(PoolType.LOW_LATENCY);
            return poolManager.getPool(PoolType.LOW_LATENCY);
        } else if (isCpuIntensive) {
            // CPU-bound tasks
            if (tpsMonitor != null && tpsMonitor.isLagging(16.0)) {
                // If server is lagging, limit CPU-intensive tasks
                double loadFactor = adjustLoadFactor();
                if (getActiveTaskCount(PoolType.CPU_BOUND) > (Runtime.getRuntime().availableProcessors() * loadFactor)) {
                    if (Debug.isDebugEnabled()) {
                        Debug.debug("Server is lagging, directing CPU-intensive task to I/O pool");
                    }
                    incrementTaskCount(PoolType.IO_BOUND);
                    return poolManager.getPool(PoolType.IO_BOUND);
                }
            }

            incrementTaskCount(PoolType.CPU_BOUND);
            return poolManager.getPool(PoolType.CPU_BOUND);
        } else if (isIoIntensive) {
            // I/O-bound tasks
            incrementTaskCount(PoolType.IO_BOUND);
            return poolManager.getPool(PoolType.IO_BOUND);
        } else {
            // Default to I/O pool for general tasks
            incrementTaskCount(PoolType.IO_BOUND);
            return poolManager.getPool(PoolType.IO_BOUND);
        }
    }

    /**
     * Checks if it's safe to execute a task that uses a specific resource.
     * Helps prevent overloading resources like databases or file systems.
     *
     * @param resourceName The name of the resource
     * @return true if it's safe to execute the task, false if the resource is overloaded
     */
    public boolean canUseResource(String resourceName) {
        if (resourceName == null || resourceName.isEmpty()) {
            return true;
        }

        ResourceUsage usage = resourceUsage.computeIfAbsent(resourceName, name -> new ResourceUsage());

        int currentUsage = usage.activeCount.get();
        int maxAllowed = calculateMaxConcurrent(resourceName);

        return currentUsage < maxAllowed;
    }

    /**
     * Notifies the load balancer that a task is starting to use a resource.
     *
     * @param resourceName The name of the resource
     */
    public void startUsingResource(String resourceName) {
        if (resourceName == null || resourceName.isEmpty()) {
            return;
        }

        ResourceUsage usage = resourceUsage.computeIfAbsent(resourceName, name -> new ResourceUsage());
        usage.activeCount.incrementAndGet();
    }

    /**
     * Notifies the load balancer that a task has finished using a resource.
     *
     * @param resourceName The name of the resource
     * @param durationNanos The duration for which the resource was used, in nanoseconds
     */
    public void finishUsingResource(String resourceName, long durationNanos) {
        if (resourceName == null || resourceName.isEmpty()) {
            return;
        }

        ResourceUsage usage = resourceUsage.get(resourceName);
        if (usage != null) {
            usage.activeCount.decrementAndGet();
            usage.totalDurationNanos.addAndGet(durationNanos);
            usage.usageCount.incrementAndGet();
        }
    }

    /**
     * Gets the number of active tasks for a specific pool type.
     *
     * @param type The pool type
     * @return The number of active tasks
     */
    public int getActiveTaskCount(PoolType type) {
        AtomicInteger counter = activeTaskCounts.get(type);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Gets the average duration for using a specific resource.
     *
     * @param resourceName The name of the resource
     * @return The average duration in nanoseconds, or 0 if unknown
     */
    public long getAverageResourceDuration(String resourceName) {
        ResourceUsage usage = resourceUsage.get(resourceName);
        if (usage != null && usage.usageCount.get() > 0) {
            return usage.totalDurationNanos.get() / usage.usageCount.get();
        }
        return 0;
    }

    /**
     * Increments the active task count for a pool type.
     *
     * @param type The pool type
     */
    public void incrementTaskCount(PoolType type) {
        AtomicInteger counter = activeTaskCounts.get(type);
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    /**
     * Decrements the active task count for a pool type.
     *
     * @param type The pool type
     */
    public void decrementTaskCount(PoolType type) {
        AtomicInteger counter = activeTaskCounts.get(type);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    /**
     * Adjusts the load factor based on current server conditions.
     *
     * @return The adjusted load factor
     */
    private double adjustLoadFactor() {
        if (tpsMonitor == null) {
            return cpuLoadFactor;
        }

        double tps = tpsMonitor.getCurrentTps();

        // Adjust load factor based on TPS
        if (tps >= 19.0) {
            // Server running smoothly, allow more concurrent tasks
            cpuLoadFactor = Math.min(2.0, cpuLoadFactor + 0.1);
            ioLoadFactor = Math.min(4.0, ioLoadFactor + 0.2);
        } else if (tps >= 17.0) {
            // Slight lag, maintain current levels
            cpuLoadFactor = 1.0;
            ioLoadFactor = 2.0;
        } else if (tps >= 15.0) {
            // Moderate lag, reduce load
            cpuLoadFactor = 0.8;
            ioLoadFactor = 1.5;
        } else {
            // Severe lag, significantly reduce load
            cpuLoadFactor = 0.5;
            ioLoadFactor = 1.0;
        }

        return cpuLoadFactor;
    }

    /**
     * Calculates the maximum concurrent tasks allowed for a resource.
     *
     * @param resourceName The name of the resource
     * @return The maximum number of concurrent tasks
     */
    private int calculateMaxConcurrent(String resourceName) {
        if (resourceName.contains("database") || resourceName.contains("db")) {
            // Database connections are often limited
            return (int) (4 * ioLoadFactor);
        } else if (resourceName.contains("file") || resourceName.contains("disk")) {
            // File operations should be limited
            return (int) (6 * ioLoadFactor);
        } else if (resourceName.contains("network") || resourceName.contains("api")) {
            // Network operations can be more parallel
            return (int) (10 * ioLoadFactor);
        } else {
            // Default limit
            return maxConcurrentTasksPerResource;
        }
    }

    /**
     * Sets the maximum concurrent tasks per resource.
     *
     * @param maxTasks The maximum number of concurrent tasks
     */
    public void setMaxConcurrentTasksPerResource(int maxTasks) {
        this.maxConcurrentTasksPerResource = maxTasks;
    }

    /**
     * Cleans up resources that haven't been used recently.
     */
    public void cleanupResources() {
        // Remove resources that have no active users
        resourceUsage.entrySet().removeIf(entry -> entry.getValue().activeCount.get() == 0);
    }

    /**
     * Class for tracking resource usage.
     */
    private static class ResourceUsage {
        private final AtomicInteger activeCount = new AtomicInteger(0);
        private final AtomicInteger usageCount = new AtomicInteger(0);
        private final AtomicLong totalDurationNanos = new AtomicLong(0);
    }
}