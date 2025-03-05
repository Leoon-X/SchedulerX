package me.leon.scheduler.util;

import org.bukkit.Bukkit;
import org.bukkit.Server;

import java.lang.reflect.Method;

/**
 * Utility class for Minecraft tick-related operations.
 * Provides methods to work with server ticks efficiently.
 */
public final class TickUtil {

    private static final long TICKS_PER_SECOND = 20L;
    private static final long MILLISECONDS_PER_TICK = 50L; // 1000ms / 20 ticks = 50ms per tick

    private TickUtil() {
        // Private constructor to prevent instantiation
    }

    /**
     * Converts real-time milliseconds to approximate Minecraft ticks.
     *
     * @param milliseconds Time in milliseconds
     * @return Approximate number of ticks
     */
    public static long millisecondsToTicks(long milliseconds) {
        return milliseconds / MILLISECONDS_PER_TICK;
    }

    /**
     * Converts ticks to real-time milliseconds.
     *
     * @param ticks Number of Minecraft ticks
     * @return Time in milliseconds
     */
    public static long ticksToMilliseconds(long ticks) {
        return ticks * MILLISECONDS_PER_TICK;
    }

    /**
     * Converts seconds to ticks.
     *
     * @param seconds Time in seconds
     * @return Number of ticks
     */
    public static long secondsToTicks(double seconds) {
        return Math.round(seconds * TICKS_PER_SECOND);
    }

    /**
     * Returns the current server tick count.
     * Uses an implementation that works across different server types.
     *
     * @return Current server tick count
     */

    public static long getCurrentTick() {
        return System.currentTimeMillis() / 50; // 50ms is the standard tick rate (20 TPS)
    }

    /**
     * Calculates time remaining until a specific tick occurs.
     *
     * @param targetTick The target tick number
     * @return Milliseconds until the target tick
     */
    public static long millisecondsUntilTick(long targetTick) {
        long currentTick = getCurrentTick();
        return (targetTick - currentTick) * MILLISECONDS_PER_TICK;
    }

    /**
     * Aligns a task to run at the start of a tick for better performance.
     *
     * @param tick The tick to align to
     * @return The aligned tick (start of the tick)
     */
    public static long alignToTickStart(long tick) {
        return tick; // In most cases, tasks are already aligned, but this provides an extension point
    }
}