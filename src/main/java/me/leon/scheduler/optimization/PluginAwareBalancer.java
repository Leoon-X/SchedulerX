package me.leon.scheduler.optimization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import me.leon.scheduler.util.Debug;

/**
 * Balances task execution across different plugins to prevent one plugin
 * from monopolizing server resources.
 */
public class PluginAwareBalancer {

    // Maximum tasks allowed per plugin (adjusts dynamically)
    private static final int DEFAULT_MAX_TASKS_PER_PLUGIN = 50;
    private static final int ABSOLUTE_MIN_TASKS_PER_PLUGIN = 5;

    // Thresholds for identifying problematic plugins
    private static final double HIGH_LOAD_THRESHOLD = 0.7; // 70% of total tasks
    private static final double EXTREME_LOAD_THRESHOLD = 0.9; // 90% of total tasks

    // Maps plugin names to their task quotas and usage
    private final Map<String, PluginQuota> pluginQuotas;

    // Maps plugin names to their usage statistics
    private final Map<String, PluginStats> pluginStats;

    // Total active tasks
    private final AtomicInteger totalActiveTasks;

    // Server plugin manager reference
    private final PluginManager pluginManager;

    /**
     * Creates a new plugin-aware balancer.
     */
    public PluginAwareBalancer() {
        this.pluginQuotas = new ConcurrentHashMap<>();
        this.pluginStats = new ConcurrentHashMap<>();
        this.totalActiveTasks = new AtomicInteger(0);
        this.pluginManager = Bukkit.getPluginManager();

        // Initialize quotas for all plugins
        initializePluginQuotas();
    }

    /**
     * Initialize task quotas for all plugins.
     */
    private void initializePluginQuotas() {
        for (Plugin plugin : pluginManager.getPlugins()) {
            String pluginName = plugin.getName();
            pluginQuotas.put(pluginName, new PluginQuota(DEFAULT_MAX_TASKS_PER_PLUGIN));
            pluginStats.put(pluginName, new PluginStats());
        }
    }

    /**
     * Checks if a plugin is allowed to schedule a new task.
     *
     * @param pluginName The name of the plugin
     * @return true if the plugin can schedule another task, false otherwise
     */
    public boolean canScheduleTask(String pluginName) {
        if (pluginName == null || pluginName.isEmpty()) {
            return true; // Unknown plugin, allow by default
        }

        PluginQuota quota = pluginQuotas.get(pluginName);
        if (quota == null) {
            // New plugin, create quota
            quota = new PluginQuota(DEFAULT_MAX_TASKS_PER_PLUGIN);
            pluginQuotas.put(pluginName, quota);

            pluginStats.put(pluginName, new PluginStats());
        }

        return quota.activeTasks.get() < quota.maxAllowedTasks;
    }

    /**
     * Registers a task start for a plugin.
     *
     * @param pluginName The name of the plugin
     * @return true if the task was registered, false if quota exceeded
     */
    public boolean registerTaskStart(String pluginName) {
        if (pluginName == null || pluginName.isEmpty()) {
            totalActiveTasks.incrementAndGet();
            return true; // Unknown plugin, allow by default
        }

        PluginQuota quota = pluginQuotas.computeIfAbsent(pluginName,
                k -> new PluginQuota(DEFAULT_MAX_TASKS_PER_PLUGIN));

        PluginStats stats = pluginStats.computeIfAbsent(pluginName,
                k -> new PluginStats());

        // Check if the plugin has exceeded its quota
        if (quota.activeTasks.get() >= quota.maxAllowedTasks) {
            stats.rejectedTasks.incrementAndGet();
            return false;
        }

        // Register task
        quota.activeTasks.incrementAndGet();
        totalActiveTasks.incrementAndGet();
        stats.scheduledTasks.incrementAndGet();

        return true;
    }

    /**
     * Registers a task completion for a plugin.
     *
     * @param pluginName The name of the plugin
     * @param executionTimeNanos The execution time in nanoseconds
     */
    public void registerTaskComplete(String pluginName, long executionTimeNanos) {
        if (pluginName == null || pluginName.isEmpty()) {
            totalActiveTasks.decrementAndGet();
            return;
        }

        PluginQuota quota = pluginQuotas.get(pluginName);
        if (quota != null) {
            quota.activeTasks.decrementAndGet();
        }

        PluginStats stats = pluginStats.get(pluginName);
        if (stats != null) {
            stats.completedTasks.incrementAndGet();
            stats.totalExecutionTimeNanos.addAndGet(executionTimeNanos);
        }

        totalActiveTasks.decrementAndGet();
    }

    /**
     * Registers a task failure for a plugin.
     *
     * @param pluginName The name of the plugin
     */
    public void registerTaskFailure(String pluginName) {
        if (pluginName == null || pluginName.isEmpty()) {
            totalActiveTasks.decrementAndGet();
            return;
        }

        PluginQuota quota = pluginQuotas.get(pluginName);
        if (quota != null) {
            quota.activeTasks.decrementAndGet();
        }

        PluginStats stats = pluginStats.get(pluginName);
        if (stats != null) {
            stats.failedTasks.incrementAndGet();
        }

        totalActiveTasks.decrementAndGet();
    }

    /**
     * Adjusts quotas based on current plugin usage patterns.
     * This should be called periodically to adapt to changing conditions.
     */
    public void balanceQuotas() {
        int total = totalActiveTasks.get();
        if (total == 0) {
            return; // Nothing to balance
        }

        List<PluginUsage> usages = new ArrayList<>();
        int totalMaxAllowed = 0;

        // Collect current usage statistics
        for (Map.Entry<String, PluginQuota> entry : pluginQuotas.entrySet()) {
            String pluginName = entry.getKey();
            PluginQuota quota = entry.getValue();
            PluginStats stats = pluginStats.get(pluginName);

            if (stats == null) {
                continue;
            }

            int active = quota.activeTasks.get();
            double usagePercent = total > 0 ? (double) active / total : 0;

            usages.add(new PluginUsage(pluginName, active, quota.maxAllowedTasks, usagePercent));
            totalMaxAllowed += quota.maxAllowedTasks;
        }

        // Look for plugins that are using excessive resources
        for (PluginUsage usage : usages) {
            if (usage.usagePercent > HIGH_LOAD_THRESHOLD) {
                // This plugin is using a high percentage of resources
                reducePluginQuota(usage.pluginName);

                Debug.log(java.util.logging.Level.WARNING,
                        "Plugin " + usage.pluginName + " is using " +
                                String.format("%.1f%%", usage.usagePercent * 100) +
                                " of server task capacity, reducing quota");

                // If load is extremely high, reduce more aggressively
                if (usage.usagePercent > EXTREME_LOAD_THRESHOLD) {
                    reducePluginQuota(usage.pluginName);
                    Debug.log(java.util.logging.Level.SEVERE,
                            "Plugin " + usage.pluginName + " is monopolizing server resources!");
                }
            } else if (usage.active < usage.maxAllowed * 0.5 &&
                    usage.maxAllowed < DEFAULT_MAX_TASKS_PER_PLUGIN) {
                // This plugin is using less than half its quota and has a reduced quota
                // Gradually restore its quota
                increasePluginQuota(usage.pluginName);
            }
        }
    }

    /**
     * Reduces the task quota for a plugin.
     *
     * @param pluginName The name of the plugin
     */
    private void reducePluginQuota(String pluginName) {
        PluginQuota quota = pluginQuotas.get(pluginName);
        if (quota != null) {
            // Don't let it go below the absolute minimum
            int newMax = Math.max(ABSOLUTE_MIN_TASKS_PER_PLUGIN, quota.maxAllowedTasks / 2);
            quota.maxAllowedTasks = newMax;

            Debug.debug("Reduced quota for plugin " + pluginName + " to " + newMax);
        }
    }

    /**
     * Increases the task quota for a plugin.
     *
     * @param pluginName The name of the plugin
     */
    private void increasePluginQuota(String pluginName) {
        PluginQuota quota = pluginQuotas.get(pluginName);
        if (quota != null) {
            // Don't let it exceed the default maximum
            int newMax = Math.min(DEFAULT_MAX_TASKS_PER_PLUGIN, quota.maxAllowedTasks * 2);
            quota.maxAllowedTasks = newMax;

            Debug.debug("Increased quota for plugin " + pluginName + " to " + newMax);
        }
    }

    /**
     * Gets the current quota for a plugin.
     *
     * @param pluginName The name of the plugin
     * @return The maximum allowed tasks, or -1 if not found
     */
    public int getPluginQuota(String pluginName) {
        PluginQuota quota = pluginQuotas.get(pluginName);
        return quota != null ? quota.maxAllowedTasks : -1;
    }

    /**
     * Gets the current active task count for a plugin.
     *
     * @param pluginName The name of the plugin
     * @return The number of active tasks, or 0 if not found
     */
    public int getPluginActiveTaskCount(String pluginName) {
        PluginQuota quota = pluginQuotas.get(pluginName);
        return quota != null ? quota.activeTasks.get() : 0;
    }

    /**
     * Gets statistics for all plugins.
     *
     * @return A map of plugin names to their statistics
     */
    public Map<String, Map<String, Object>> getPluginStatistics() {
        Map<String, Map<String, Object>> result = new HashMap<>();

        for (Map.Entry<String, PluginQuota> entry : pluginQuotas.entrySet()) {
            String pluginName = entry.getKey();
            PluginQuota quota = entry.getValue();
            PluginStats stats = pluginStats.get(pluginName);

            if (stats == null) {
                continue;
            }

            Map<String, Object> pluginData = new HashMap<>();
            pluginData.put("activeTasks", quota.activeTasks.get());
            pluginData.put("maxAllowedTasks", quota.maxAllowedTasks);
            pluginData.put("scheduledTasks", stats.scheduledTasks.get());
            pluginData.put("completedTasks", stats.completedTasks.get());
            pluginData.put("failedTasks", stats.failedTasks.get());
            pluginData.put("rejectedTasks", stats.rejectedTasks.get());

            long execTime = stats.totalExecutionTimeNanos.get();
            long completedCount = stats.completedTasks.get();
            double avgExecTimeMs = completedCount > 0 ?
                    (execTime / completedCount) / 1_000_000.0 : 0;

            pluginData.put("avgExecutionTimeMs", avgExecTimeMs);

            result.put(pluginName, pluginData);
        }

        return result;
    }

    /**
     * Detects if a plugin is currently experiencing issues.
     *
     * @param pluginName The name of the plugin
     * @return true if the plugin appears to be problematic
     */
    public boolean isPluginProblematic(String pluginName) {
        PluginQuota quota = pluginQuotas.get(pluginName);
        PluginStats stats = pluginStats.get(pluginName);

        if (quota == null || stats == null) {
            return false;
        }

        // Check for high failure rate
        long completed = stats.completedTasks.get();
        long failed = stats.failedTasks.get();
        long total = completed + failed;

        if (total >= 10 && (double)failed / total > 0.5) {
            // Over 50% failure rate
            return true;
        }

        // Check for resource monopolization
        int totalTasks = totalActiveTasks.get();
        if (totalTasks > 0 && (double)quota.activeTasks.get() / totalTasks > HIGH_LOAD_THRESHOLD) {
            return true;
        }

        return false;
    }

    /**
     * Resets statistics for all plugins.
     */
    public void resetStatistics() {
        for (PluginStats stats : pluginStats.values()) {
            stats.reset();
        }
    }

    /**
     * Class representing a plugin's task quota.
     */
    private static class PluginQuota {
        private final AtomicInteger activeTasks;
        private volatile int maxAllowedTasks;

        /**
         * Creates a new plugin quota.
         *
         * @param maxAllowedTasks Maximum allowed concurrent tasks
         */
        public PluginQuota(int maxAllowedTasks) {
            this.activeTasks = new AtomicInteger(0);
            this.maxAllowedTasks = maxAllowedTasks;
        }
    }

    /**
     * Class for tracking plugin task statistics.
     */
    private static class PluginStats {
        private final AtomicLong scheduledTasks;
        private final AtomicLong completedTasks;
        private final AtomicLong failedTasks;
        private final AtomicLong rejectedTasks;
        private final AtomicLong totalExecutionTimeNanos;

        /**
         * Creates a new plugin stats tracker.
         */
        public PluginStats() {
            this.scheduledTasks = new AtomicLong(0);
            this.completedTasks = new AtomicLong(0);
            this.failedTasks = new AtomicLong(0);
            this.rejectedTasks = new AtomicLong(0);
            this.totalExecutionTimeNanos = new AtomicLong(0);
        }

        /**
         * Resets all statistics.
         */
        public void reset() {
            scheduledTasks.set(0);
            completedTasks.set(0);
            failedTasks.set(0);
            rejectedTasks.set(0);
            totalExecutionTimeNanos.set(0);
        }
    }

    /**
     * Class for representing plugin resource usage during balancing.
     */
    private static class PluginUsage {
        private final String pluginName;
        private final int active;
        private final int maxAllowed;
        private final double usagePercent;

        /**
         * Creates a new plugin usage record.
         *
         * @param pluginName The name of the plugin
         * @param active The number of active tasks
         * @param maxAllowed The maximum allowed tasks
         * @param usagePercent The percentage of total server tasks
         */
        public PluginUsage(String pluginName, int active, int maxAllowed, double usagePercent) {
            this.pluginName = pluginName;
            this.active = active;
            this.maxAllowed = maxAllowed;
            this.usagePercent = usagePercent;
        }
    }
}