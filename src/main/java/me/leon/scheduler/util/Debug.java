package me.leon.scheduler.util;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility class for debugging, logging, and performance monitoring.
 * Provides optimized logging with minimal overhead in production.
 */
public final class Debug {

    private static final Logger LOGGER = Logger.getLogger("Scheduler");
    private static volatile boolean DEBUG_MODE = false;
    private static final int MAX_DEBUG_HISTORY = 100;

    // Performance metrics tracking
    private static final Deque<String> PERFORMANCE_HISTORY = new ConcurrentLinkedDeque<>();
    private static final AtomicLong LAST_WARNING_TIME = new AtomicLong(0);
    private static final long WARNING_THROTTLE_MS = 60000; // Limit warnings to once per minute

    private Debug() {
        // Private constructor to prevent instantiation
    }

    /**
     * Enables or disables debug mode.
     *
     * @param enabled true to enable debug output, false to disable
     */
    public static void setDebugMode(boolean enabled) {
        DEBUG_MODE = enabled;
        log(Level.INFO, "Debug mode " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Checks if debug mode is enabled.
     *
     * @return true if debug mode is enabled
     */
    public static boolean isDebugEnabled() {
        return DEBUG_MODE;
    }

    /**
     * Logs a message if debug mode is enabled.
     * This method is optimized to have minimal impact when debug is disabled.
     *
     * @param message The message to log
     */
    public static void debug(String message) {
        if (DEBUG_MODE) {
            log(Level.INFO, "[DEBUG] " + message);
        }
    }

    /**
     * Logs a message at the specified level.
     *
     * @param level   The logging level
     * @param message The message to log
     */
    public static void log(Level level, String message) {
        LOGGER.log(level, message);
    }

    /**
     * Records task execution time for performance tracking.
     * Only stores data if debug mode is enabled.
     *
     * @param taskName     Name of the task
     * @param executionTime Time taken in nanoseconds
     */
    public static void recordExecutionTime(String taskName, long executionTime) {
        if (!DEBUG_MODE) return;

        // Convert to milliseconds for readability
        double timeMs = executionTime / 1_000_000.0;

        // Only log slow tasks
        if (timeMs > 10.0) { // 10ms threshold
            String perfEntry = String.format("%s took %.2fms", taskName, timeMs);

            // Add to history with bounds checking
            PERFORMANCE_HISTORY.addFirst(perfEntry);
            if (PERFORMANCE_HISTORY.size() > MAX_DEBUG_HISTORY) {
                PERFORMANCE_HISTORY.removeLast();
            }

            // Log warning for very slow tasks, with throttling
            if (timeMs > 50.0) { // 50ms threshold for warnings
                long now = System.currentTimeMillis();
                long lastWarning = LAST_WARNING_TIME.get();

                if (now - lastWarning > WARNING_THROTTLE_MS &&
                        LAST_WARNING_TIME.compareAndSet(lastWarning, now)) {
                    log(Level.WARNING, "Performance issue detected: " + perfEntry);
                }
            }
        }
    }

    /**
     * Convenient method to time code execution with minimal overhead.
     *
     * @param taskName Name of the task to record
     * @return AutoCloseable timer that records duration when closed
     */
    public static AutoCloseable timeExecution(String taskName) {
        if (!DEBUG_MODE) {
            // Return no-op closeable when debug is disabled
            return () -> {};
        }

        long startTime = System.nanoTime();
        return () -> recordExecutionTime(taskName, System.nanoTime() - startTime);
    }

    /**
     * Gets the recent performance history for analysis.
     *
     * @return Array of recent performance entries
     */
    public static String[] getPerformanceHistory() {
        return PERFORMANCE_HISTORY.toArray(new String[0]);
    }

    /**
     * Clears the performance history.
     */
    public static void clearPerformanceHistory() {
        PERFORMANCE_HISTORY.clear();
    }
}