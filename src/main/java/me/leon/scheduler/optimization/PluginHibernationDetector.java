package me.leon.scheduler.optimization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.plugin.Plugin;

import me.leon.scheduler.util.Debug;

/**
 * Detects and manages plugins that are temporarily inactive.
 * Helps optimize task handling for inactive plugins.
 */
public class PluginHibernationDetector {

    // Configuration parameters
    private static final long DEFAULT_HIBERNATE_THRESHOLD_MS = 300000; // 5 minutes
    private static final long DEFAULT_CHECK_INTERVAL_MS = 60000; // 1 minute

    // Maps plugin names to their activity information
    private final Map<String, PluginActivityInfo> pluginActivity;

    // Last check time
    private long lastCheckTime;

    // Configuration
    private long hibernateThresholdMs;
    private long checkIntervalMs;

    /**
     * Creates a new plugin hibernation detector.
     */
    public PluginHibernationDetector() {
        this.pluginActivity = new ConcurrentHashMap<>();
        this.lastCheckTime = System.currentTimeMillis();
        this.hibernateThresholdMs = DEFAULT_HIBERNATE_THRESHOLD_MS;
        this.checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS;
    }

    /**
     * Records activity for a plugin.
     *
     * @param pluginName The name of the plugin
     * @param activityType The type of activity (e.g., "task", "command", "event")
     */
    public void recordActivity(String pluginName, String activityType) {
        if (pluginName == null || pluginName.isEmpty()) {
            return;
        }

        PluginActivityInfo info = pluginActivity.computeIfAbsent(pluginName,
                k -> new PluginActivityInfo());

        info.recordActivity(activityType);
    }

    /**
     * Checks if a plugin is hibernating (temporarily inactive).
     *
     * @param pluginName The name of the plugin
     * @return true if the plugin is hibernating
     */
    public boolean isPluginHibernating(String pluginName) {
        if (pluginName == null || pluginName.isEmpty()) {
            return false;
        }

        PluginActivityInfo info = pluginActivity.get(pluginName);
        if (info == null) {
            return false; // No activity recorded yet
        }

        return info.isHibernating(hibernateThresholdMs);
    }

    /**
     * Gets the time since the last activity for a plugin.
     *
     * @param pluginName The name of the plugin
     * @return Time in milliseconds since last activity, or 0 if no activity recorded
     */
    public long getTimeSinceLastActivity(String pluginName) {
        if (pluginName == null || pluginName.isEmpty()) {
            return 0;
        }

        PluginActivityInfo info = pluginActivity.get(pluginName);
        if (info == null) {
            return 0;
        }

        return info.getTimeSinceLastActivity();
    }

    /**
     * Gets information about plugin activity.
     *
     * @param pluginName The name of the plugin
     * @return A map of activity statistics, or null if no data
     */
    public Map<String, Object> getPluginActivityInfo(String pluginName) {
        PluginActivityInfo info = pluginActivity.get(pluginName);
        if (info == null) {
            return null;
        }

        return info.getActivityStats();
    }

    /**
     * Gets a list of all hibernating plugins.
     *
     * @return A list of hibernating plugin names
     */
    public List<String> getHibernatingPlugins() {
        List<String> hibernating = new ArrayList<>();

        for (Map.Entry<String, PluginActivityInfo> entry : pluginActivity.entrySet()) {
            if (entry.getValue().isHibernating(hibernateThresholdMs)) {
                hibernating.add(entry.getKey());
            }
        }

        return hibernating;
    }

    /**
     * Updates hibernation status for all plugins.
     * This should be called periodically to update plugin states.
     */
    public void updateHibernationStatus() {
        long now = System.currentTimeMillis();

        // Only check periodically
        if (now - lastCheckTime < checkIntervalMs) {
            return;
        }

        lastCheckTime = now;

        // Update status and log hibernation changes
        for (Map.Entry<String, PluginActivityInfo> entry : pluginActivity.entrySet()) {
            String pluginName = entry.getKey();
            PluginActivityInfo info = entry.getValue();

            boolean wasHibernating = info.isCurrentlyMarkedHibernating();
            boolean isHibernating = info.isHibernating(hibernateThresholdMs);

            if (wasHibernating != isHibernating) {
                info.setCurrentlyMarkedHibernating(isHibernating);

                if (isHibernating) {
                    // Plugin just entered hibernation
                    Debug.log(java.util.logging.Level.INFO,
                            "Plugin " + pluginName + " entered hibernation state after " +
                                    (info.getTimeSinceLastActivity() / 1000) + " seconds of inactivity");
                } else {
                    // Plugin just woke up
                    Debug.log(java.util.logging.Level.INFO,
                            "Plugin " + pluginName + " woke up from hibernation");
                }
            }
        }
    }

    /**
     * Sets the hibernation threshold.
     *
     * @param thresholdMs The threshold in milliseconds
     */
    public void setHibernateThreshold(long thresholdMs) {
        this.hibernateThresholdMs = thresholdMs;
    }

    /**
     * Sets the check interval.
     *
     * @param intervalMs The interval in milliseconds
     */
    public void setCheckInterval(long intervalMs) {
        this.checkIntervalMs = intervalMs;
    }

    /**
     * Class for tracking plugin activity information.
     */
    private static class PluginActivityInfo {
        private final AtomicLong lastActivityTime;
        private final Map<String, AtomicLong> activityTypeCounts;
        private volatile boolean currentlyMarkedHibernating;

        /**
         * Creates a new plugin activity info.
         */
        public PluginActivityInfo() {
            this.lastActivityTime = new AtomicLong(System.currentTimeMillis());
            this.activityTypeCounts = new ConcurrentHashMap<>();
            this.currentlyMarkedHibernating = false;
        }

        /**
         * Records an activity for the plugin.
         *
         * @param activityType The type of activity
         */
        public void recordActivity(String activityType) {
            lastActivityTime.set(System.currentTimeMillis());

            AtomicLong count = activityTypeCounts.computeIfAbsent(activityType,
                    k -> new AtomicLong(0));
            count.incrementAndGet();
        }

        /**
         * Checks if the plugin is hibernating.
         *
         * @param thresholdMs The hibernation threshold
         * @return true if the plugin has been inactive for longer than the threshold
         */
        public boolean isHibernating(long thresholdMs) {
            return getTimeSinceLastActivity() > thresholdMs;
        }

        /**
         * Gets the time since the last activity.
         *
         * @return Time in milliseconds
         */
        public long getTimeSinceLastActivity() {
            return System.currentTimeMillis() - lastActivityTime.get();
        }

        /**
         * Gets activity statistics.
         *
         * @return A map of activity statistics
         */
        public Map<String, Object> getActivityStats() {
            Map<String, Object> stats = new HashMap<>();

            stats.put("lastActivityTime", lastActivityTime.get());
            stats.put("timeSinceLastActivity", getTimeSinceLastActivity());
            stats.put("isHibernating", currentlyMarkedHibernating);

            Map<String, Long> activityCounts = new HashMap<>();
            for (Map.Entry<String, AtomicLong> entry : activityTypeCounts.entrySet()) {
                activityCounts.put(entry.getKey(), entry.getValue().get());
            }
            stats.put("activityCounts", activityCounts);

            return stats;
        }

        /**
         * Checks if the plugin is currently marked as hibernating.
         *
         * @return true if hibernating
         */
        public boolean isCurrentlyMarkedHibernating() {
            return currentlyMarkedHibernating;
        }

        /**
         * Sets whether the plugin is currently marked as hibernating.
         *
         * @param hibernating The hibernation state
         */
        public void setCurrentlyMarkedHibernating(boolean hibernating) {
            this.currentlyMarkedHibernating = hibernating;
        }
    }
}