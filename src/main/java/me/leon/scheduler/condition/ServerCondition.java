package me.leon.scheduler.condition;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.function.Supplier;

import org.bukkit.Bukkit;

import me.leon.scheduler.api.Condition;
import me.leon.scheduler.core.TPSMonitor;

/**
 * Provides condition implementations related to server state.
 * These conditions check server performance metrics.
 */
public final class ServerCondition {

    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    private ServerCondition() {
        // Private constructor to prevent instantiation
    }

    /**
     * Creates a condition that is met when the server TPS is above a threshold.
     *
     * @param minTps The minimum TPS threshold
     * @param tpsMonitor The TPS monitor to use
     * @return A condition that checks server TPS
     */
    public static Condition tpsAbove(double minTps, TPSMonitor tpsMonitor) {
        return () -> {
            if (tpsMonitor != null) {
                return tpsMonitor.getCurrentTps() >= minTps;
            }

            // Fallback implementation if no TPS monitor is provided
            // This is a crude approximation and less accurate
            try {
                long start = System.nanoTime();

                // Wait for a server tick
                Bukkit.getScheduler().scheduleSyncDelayedTask(Bukkit.getPluginManager().getPlugins()[0], () -> {});

                // Calculate rough TPS based on tick time
                double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
                double currentTps = 1000.0 / Math.max(50.0, elapsedMs);

                return currentTps >= minTps;
            } catch (Exception e) {
                // If anything goes wrong, assume the condition is not met
                return false;
            }
        };
    }

    /**
     * Creates a condition that is met when the server memory usage is below a threshold.
     *
     * @param maxMemoryPercent The maximum memory usage percentage (0-100)
     * @return A condition that checks memory usage
     */
    public static Condition memoryBelow(int maxMemoryPercent) {
        return () -> {
            try {
                MemoryUsage heapUsage = MEMORY_BEAN.getHeapMemoryUsage();
                long used = heapUsage.getUsed();
                long max = heapUsage.getMax();

                double usagePercent = (double) used / max * 100.0;
                return usagePercent < maxMemoryPercent;
            } catch (Exception e) {
                // If anything goes wrong, assume the condition is not met
                return false;
            }
        };
    }

    /**
     * Creates a condition that is met when server has fewer than a max number of players.
     *
     * @param maxPlayers The maximum player threshold
     * @return A condition that checks player count
     */
    public static Condition playerCountBelow(int maxPlayers) {
        return () -> Bukkit.getOnlinePlayers().size() < maxPlayers;
    }

    /**
     * Creates a condition that is met when the server has at least a minimum number of players.
     *
     * @param minPlayers The minimum player threshold
     * @return A condition that checks player count
     */
    public static Condition playerCountAbove(int minPlayers) {
        return () -> Bukkit.getOnlinePlayers().size() > minPlayers;
    }

    /**
     * Creates a condition that is met when the server has an exact number of players.
     *
     * @param exactCount The exact player count
     * @return A condition that checks player count
     */
    public static Condition playerCountExactly(int exactCount) {
        return () -> Bukkit.getOnlinePlayers().size() == exactCount;
    }

    /**
     * Creates a condition that is met when the server is empty (no players).
     *
     * @return A condition that checks if the server is empty
     */
    public static Condition serverEmpty() {
        return () -> Bukkit.getOnlinePlayers().isEmpty();
    }

    /**
     * Creates a condition that is met during server startup.
     * This condition is only true during the first few ticks of server operation.
     *
     * @return A condition that checks if the server is in startup phase
     */
    public static Supplier<Boolean> duringServerStartup() {
        final long[] serverStartTime = new long[1];

        return new Supplier<Boolean>() {
            @Override
            public Boolean get() {
                if (serverStartTime[0] == 0) {
                    serverStartTime[0] = System.currentTimeMillis();
                    return true;
                }

                // Consider startup phase to be the first 30 seconds
                return System.currentTimeMillis() - serverStartTime[0] < 30000;
            }
        };
    }

    /**
     * Creates a condition that combines multiple conditions with AND logic.
     *
     * @param conditions The conditions to combine
     * @return A condition that is met when all given conditions are met
     */
    public static Condition all(Condition... conditions) {
        return () -> {
            for (Condition condition : conditions) {
                if (!condition.get()) {
                    return false;
                }
            }
            return true;
        };
    }

    /**
     * Creates a condition that combines multiple conditions with OR logic.
     *
     * @param conditions The conditions to combine
     * @return A condition that is met when any of the given conditions is met
     */
    public static Condition any(Condition... conditions) {
        return () -> {
            for (Condition condition : conditions) {
                if (condition.get()) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * Creates a condition that negates another condition.
     *
     * @param condition The condition to negate
     * @return A condition that is met when the given condition is not met
     */
    public static Condition not(Condition condition) {
        return () -> !condition.get();
    }
}