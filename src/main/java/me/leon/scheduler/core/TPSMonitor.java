package me.leon.scheduler.core;

import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import me.leon.scheduler.util.Debug;

/**
 * Monitors server TPS (Ticks Per Second) to optimize task scheduling.
 * Provides methods to check server performance and adjust scheduling.
 */
public final class TPSMonitor {

    private static final int TPS_SAMPLE_COUNT = 20; // 20 second moving average
    private static final long TICK_TIME_MILLIS = 50; // 20 TPS = 50ms per tick

    private final Plugin plugin;
    private final AtomicReference<Double> currentTps;
    private final LinkedList<Long> tickTimes;
    private long lastTickTime;
    private int taskId;

    /**
     * Creates a new TPS monitor for the specified plugin.
     *
     * @param plugin The plugin instance
     */
    public TPSMonitor(Plugin plugin) {
        this.plugin = plugin;
        this.currentTps = new AtomicReference<>(20.0); // Start with assumption of perfect TPS
        this.tickTimes = new LinkedList<>();
        this.lastTickTime = System.currentTimeMillis();
        this.taskId = -1;
    }

    /**
     * Starts monitoring server TPS.
     */
    public void start() {
        if (taskId != -1) {
            return; // Already started
        }

        Debug.log(java.util.logging.Level.INFO, "Starting TPS monitoring");

        // Use Bukkit scheduler to measure tick times
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::measureTick, 1L, 1L).getTaskId();
    }

    /**
     * Stops monitoring server TPS.
     */
    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    /**
     * Measures a server tick and updates the TPS calculation.
     */
    private void measureTick() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastTickTime;
        lastTickTime = now;

        // Skip first tick which may have abnormal timing
        if (elapsed > 0 && elapsed < 1000) { // Ignore unreasonable values
            synchronized (tickTimes) {
                tickTimes.add(elapsed);
                while (tickTimes.size() > TPS_SAMPLE_COUNT) {
                    tickTimes.removeFirst();
                }

                // Calculate TPS based on moving average
                double averageTickTime = calculateAverageTickTime();
                double calculatedTps = Math.min(20.0, 1000.0 / averageTickTime);

                // Update the atomic reference
                currentTps.set(calculatedTps);

                if (Debug.isDebugEnabled() && tickTimes.size() >= TPS_SAMPLE_COUNT) {
                    // Only log when we have enough samples and periodically
                    if (Math.random() < 0.01) { // ~1% of ticks
                        Debug.debug(String.format("Current TPS: %.2f (avg tick: %.2fms)",
                                calculatedTps, averageTickTime));
                    }
                }
            }
        }
    }

    /**
     * Calculates the average tick time from the samples.
     *
     * @return Average tick time in milliseconds
     */
    private double calculateAverageTickTime() {
        synchronized (tickTimes) {
            if (tickTimes.isEmpty()) {
                return TICK_TIME_MILLIS;
            }

            long sum = 0;
            for (long time : tickTimes) {
                sum += time;
            }

            return (double) sum / tickTimes.size();
        }
    }

    /**
     * Gets the current server TPS.
     *
     * @return Current TPS value (0-20)
     */
    public double getCurrentTps() {
        return currentTps.get();
    }

    /**
     * Checks if the server is experiencing lag.
     *
     * @param threshold The TPS threshold below which the server is considered lagging
     * @return true if the server TPS is below the threshold
     */
    public boolean isLagging(double threshold) {
        return currentTps.get() < threshold;
    }

    /**
     * Calculates a load factor between 0.0 and 1.0.
     * 0.0 means no load (20 TPS), 1.0 means maximum load (0 TPS).
     *
     * @return Load factor between 0.0 and 1.0
     */
    public double getLoadFactor() {
        return Math.max(0.0, Math.min(1.0, 1.0 - (currentTps.get() / 20.0)));
    }

    /**
     * Gets the recommended task delay based on current server load.
     * Increases delay during periods of high load.
     *
     * @param baseDelay The base delay in ticks
     * @return Adjusted delay in ticks
     */
    public long getAdjustedDelay(long baseDelay) {
        double loadFactor = getLoadFactor();

        // Exponential backoff based on load
        if (loadFactor < 0.3) {
            // Low load, use base delay
            return baseDelay;
        } else if (loadFactor < 0.6) {
            // Medium load, increase delay slightly
            return baseDelay + Math.round(baseDelay * 0.5);
        } else {
            // High load, increase delay significantly
            return baseDelay + Math.round(baseDelay * loadFactor * 2);
        }
    }
}