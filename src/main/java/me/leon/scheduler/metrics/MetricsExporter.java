package me.leon.scheduler.metrics;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import me.leon.scheduler.metrics.TaskTracker.TaskRecord;
import me.leon.scheduler.metrics.TaskTracker.TaskExecutionRecord;
import me.leon.scheduler.util.Debug;

/**
 * Exports scheduler metrics in various formats.
 * Provides methods for generating reports and visualizations.
 */
public class MetricsExporter {

    private final Plugin plugin;
    private final SchedulerMetrics schedulerMetrics;
    private final TaskTracker taskTracker;

    /**
     * Creates a new metrics exporter.
     *
     * @param plugin The owning plugin
     * @param schedulerMetrics The scheduler metrics
     * @param taskTracker The task tracker
     */
    public MetricsExporter(Plugin plugin, SchedulerMetrics schedulerMetrics, TaskTracker taskTracker) {
        this.plugin = plugin;
        this.schedulerMetrics = schedulerMetrics;
        this.taskTracker = taskTracker;
    }

    /**
     * Exports metrics to a file in CSV format.
     *
     * @param file The file to export to
     * @return true if the export was successful
     */
    public boolean exportToCsv(File file) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            // Write header
            writer.write("Category,ActiveTasks,CompletedTasks,FailedTasks,AvgExecutionTimeMs,MinExecutionTimeMs,MaxExecutionTimeMs\n");

            // Write category metrics
            for (Map.Entry<String, SchedulerMetrics.CategoryMetrics> entry : schedulerMetrics.getCategorizedMetrics().entrySet()) {
                String category = entry.getKey();
                SchedulerMetrics.CategoryMetrics metrics = entry.getValue();

                writer.write(String.format("%s,%d,%d,%d,%.2f,%.2f,%.2f\n",
                        category,
                        metrics.getActiveCount(),
                        metrics.getCompletedCount(),
                        metrics.getFailedCount(),
                        metrics.getAverageExecutionTimeNanos() / 1_000_000.0,
                        metrics.getMinExecutionTimeNanos() / 1_000_000.0,
                        metrics.getMaxExecutionTimeNanos() / 1_000_000.0));
            }

            // Add overall metrics
            writer.write(String.format("\nOVERALL,%d,%d,0,%.2f,0,0\n",
                    schedulerMetrics.getActiveTaskCount(),
                    schedulerMetrics.getCompletedTaskCount(),
                    schedulerMetrics.getAverageExecutionTimeNanos() / 1_000_000.0));

            return true;
        } catch (IOException e) {
            Debug.log(Level.SEVERE, "Error exporting metrics to CSV: " + e.getMessage());
            return false;
        }
    }

    /**
     * Exports task tracker data to a file in CSV format.
     *
     * @param file The file to export to
     * @return true if the export was successful
     */
    public boolean exportTasksToCSV(File file) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            // Write header
            writer.write("TaskID,Category,Description,Executions,Successes,Failures,AvgExecutionTimeMs,MaxExecutionTimeMs,FailureRate\n");

            // Write task records
            for (TaskRecord record : taskTracker.getAllTaskRecords()) {
                writer.write(String.format("%d,%s,\"%s\",%d,%d,%d,%.2f,%.2f,%.2f%%\n",
                        record.getTaskId(),
                        record.getCategory(),
                        record.getDescription().replace("\"", "\"\""), // Escape quotes
                        record.getExecutionCount(),
                        record.getSuccessCount(),
                        record.getFailureCount(),
                        record.getAverageExecutionTimeNanos() / 1_000_000.0,
                        record.getMaxExecutionTimeNanos() / 1_000_000.0,
                        record.getFailureRate() * 100.0));
            }

            return true;
        } catch (IOException e) {
            Debug.log(Level.SEVERE, "Error exporting task data to CSV: " + e.getMessage());
            return false;
        }
    }

    /**
     * Generates an HTML report of metrics.
     *
     * @param file The file to export to
     * @return true if the export was successful
     */
    public boolean exportToHtml(File file) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            // Generate HTML
            writer.write("<!DOCTYPE html>\n");
            writer.write("<html lang=\"en\">\n");
            writer.write("<head>\n");
            writer.write("  <meta charset=\"UTF-8\">\n");
            writer.write("  <title>Scheduler Metrics Report</title>\n");
            writer.write("  <style>\n");
            writer.write("    body { font-family: Arial, sans-serif; margin: 20px; }\n");
            writer.write("    h1, h2 { color: #333; }\n");
            writer.write("    table { border-collapse: collapse; width: 100%; margin-bottom: 20px; }\n");
            writer.write("    th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }\n");
            writer.write("    th { background-color: #f2f2f2; }\n");
            writer.write("    tr:nth-child(even) { background-color: #f9f9f9; }\n");
            writer.write("    .slow { background-color: #ffe6e6; }\n");
            writer.write("    .warning { color: #e74c3c; }\n");
            writer.write("  </style>\n");
            writer.write("</head>\n");
            writer.write("<body>\n");

            // Header
            writer.write("  <h1>Scheduler Metrics Report</h1>\n");
            writer.write("  <p>Generated on: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "</p>\n");
            writer.write("  <p>Plugin: " + plugin.getName() + " v" + plugin.getDescription().getVersion() + "</p>\n");

            // Overall metrics
            writer.write("  <h2>Overall Metrics</h2>\n");
            writer.write("  <table>\n");
            writer.write("    <tr><th>Metric</th><th>Value</th></tr>\n");
            writer.write("    <tr><td>Active Tasks</td><td>" + schedulerMetrics.getActiveTaskCount() + "</td></tr>\n");
            writer.write("    <tr><td>Completed Tasks</td><td>" + schedulerMetrics.getCompletedTaskCount() + "</td></tr>\n");
            writer.write("    <tr><td>Average Execution Time</td><td>" +
                    String.format("%.2f ms", schedulerMetrics.getAverageExecutionTimeNanos() / 1_000_000.0) + "</td></tr>\n");
            writer.write("    <tr><td>Time Since Reset</td><td>" +
                    formatDuration(schedulerMetrics.getTimeSinceReset(TimeUnit.MILLISECONDS)) + "</td></tr>\n");
            writer.write("  </table>\n");

            // Category metrics
            writer.write("  <h2>Category Metrics</h2>\n");
            writer.write("  <table>\n");
            writer.write("    <tr>\n");
            writer.write("      <th>Category</th>\n");
            writer.write("      <th>Active</th>\n");
            writer.write("      <th>Completed</th>\n");
            writer.write("      <th>Failed</th>\n");
            writer.write("      <th>Avg Time</th>\n");
            writer.write("      <th>Min Time</th>\n");
            writer.write("      <th>Max Time</th>\n");
            writer.write("    </tr>\n");

            for (Map.Entry<String, SchedulerMetrics.CategoryMetrics> entry : schedulerMetrics.getCategorizedMetrics().entrySet()) {
                String category = entry.getKey();
                SchedulerMetrics.CategoryMetrics metrics = entry.getValue();

                boolean isSlow = metrics.getAverageExecutionTimeNanos() > TimeUnit.MILLISECONDS.toNanos(50);

                writer.write("    <tr" + (isSlow ? " class=\"slow\"" : "") + ">\n");
                writer.write("      <td>" + category + "</td>\n");
                writer.write("      <td>" + metrics.getActiveCount() + "</td>\n");
                writer.write("      <td>" + metrics.getCompletedCount() + "</td>\n");
                writer.write("      <td>" + metrics.getFailedCount() + "</td>\n");
                writer.write("      <td>" + String.format("%.2f ms", metrics.getAverageExecutionTimeNanos() / 1_000_000.0) + "</td>\n");
                writer.write("      <td>" + String.format("%.2f ms", metrics.getMinExecutionTimeNanos() / 1_000_000.0) + "</td>\n");
                writer.write("      <td>" + String.format("%.2f ms", metrics.getMaxExecutionTimeNanos() / 1_000_000.0) + "</td>\n");
                writer.write("    </tr>\n");
            }

            writer.write("  </table>\n");

            // Slow tasks
            writer.write("  <h2>Slowest Tasks</h2>\n");
            writer.write("  <table>\n");
            writer.write("    <tr>\n");
            writer.write("      <th>Task ID</th>\n");
            writer.write("      <th>Category</th>\n");
            writer.write("      <th>Execution Time</th>\n");
            writer.write("      <th>Status</th>\n");
            writer.write("      <th>Timestamp</th>\n");
            writer.write("    </tr>\n");

            List<TaskExecutionRecord> slowestExecutions = taskTracker.getSlowestExecutions(10);
            for (TaskExecutionRecord execution : slowestExecutions) {
                writer.write("    <tr>\n");
                writer.write("      <td>" + execution.getTaskId() + "</td>\n");
                writer.write("      <td>" + execution.getCategory() + "</td>\n");
                writer.write("      <td>" + String.format("%.2f ms", execution.getExecutionTimeNanos() / 1_000_000.0) + "</td>\n");
                writer.write("      <td>" + (execution.isSuccessful() ? "Success" : "<span class=\"warning\">Failed</span>") + "</td>\n");
                writer.write("      <td>" + new SimpleDateFormat("HH:mm:ss").format(new Date(execution.getTimestamp())) + "</td>\n");
                writer.write("    </tr>\n");
            }

            writer.write("  </table>\n");

            // High failure rate tasks
            writer.write("  <h2>High Failure Rate Tasks</h2>\n");
            writer.write("  <table>\n");
            writer.write("    <tr>\n");
            writer.write("      <th>Task ID</th>\n");
            writer.write("      <th>Category</th>\n");
            writer.write("      <th>Description</th>\n");
            writer.write("      <th>Executions</th>\n");
            writer.write("      <th>Failures</th>\n");
            writer.write("      <th>Failure Rate</th>\n");
            writer.write("    </tr>\n");

            List<TaskRecord> highFailureTasks = taskTracker.getHighestFailureRateTasks(10, 5);
            for (TaskRecord record : highFailureTasks) {
                if (record.getFailureRate() > 0) {
                    writer.write("    <tr>\n");
                    writer.write("      <td>" + record.getTaskId() + "</td>\n");
                    writer.write("      <td>" + record.getCategory() + "</td>\n");
                    writer.write("      <td>" + record.getDescription() + "</td>\n");
                    writer.write("      <td>" + record.getExecutionCount() + "</td>\n");
                    writer.write("      <td>" + record.getFailureCount() + "</td>\n");
                    writer.write("      <td>" + String.format("%.1f%%", record.getFailureRate() * 100.0) + "</td>\n");
                    writer.write("    </tr>\n");
                }
            }

            writer.write("  </table>\n");

            // Footer
            writer.write("  <p><small>Generated by " + plugin.getName() + " scheduler metrics</small></p>\n");
            writer.write("</body>\n");
            writer.write("</html>\n");

            return true;
        } catch (IOException e) {
            Debug.log(Level.SEVERE, "Error exporting metrics to HTML: " + e.getMessage());
            return false;
        }
    }

    /**
     * Displays a metrics summary to a command sender.
     *
     * @param sender The command sender to display to
     */
    public void displaySummary(CommandSender sender) {
        // Header
        sender.sendMessage(ChatColor.GOLD + "=== Scheduler Metrics Summary ===");

        // Overall stats
        sender.sendMessage(ChatColor.YELLOW + "Active Tasks: " + ChatColor.WHITE + schedulerMetrics.getActiveTaskCount());
        sender.sendMessage(ChatColor.YELLOW + "Completed Tasks: " + ChatColor.WHITE + schedulerMetrics.getCompletedTaskCount());
        sender.sendMessage(ChatColor.YELLOW + "Average Execution Time: " + ChatColor.WHITE +
                String.format("%.2f ms", schedulerMetrics.getAverageExecutionTimeNanos() / 1_000_000.0));

        // Category breakdown (limited)
        Map<String, SchedulerMetrics.CategoryMetrics> categories = schedulerMetrics.getCategorizedMetrics();
        if (!categories.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Top Categories:");

            // Sort categories by completed count
            List<Map.Entry<String, SchedulerMetrics.CategoryMetrics>> sortedCategories =
                    new ArrayList<>(categories.entrySet());
            sortedCategories.sort((a, b) ->
                    Long.compare(b.getValue().getCompletedCount(), a.getValue().getCompletedCount()));

            // Display top 5
            int displayCount = Math.min(5, sortedCategories.size());
            for (int i = 0; i < displayCount; i++) {
                Map.Entry<String, SchedulerMetrics.CategoryMetrics> entry = sortedCategories.get(i);
                SchedulerMetrics.CategoryMetrics metrics = entry.getValue();

                sender.sendMessage(ChatColor.WHITE + "  " + entry.getKey() + ": " +
                        metrics.getCompletedCount() + " tasks, avg " +
                        String.format("%.2f ms", metrics.getAverageExecutionTimeNanos() / 1_000_000.0));
            }
        }

        // Slow tasks
        List<TaskExecutionRecord> slowestExecutions = taskTracker.getSlowestExecutions(3);
        if (!slowestExecutions.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Slowest Recent Tasks:");

            for (TaskExecutionRecord execution : slowestExecutions) {
                sender.sendMessage(ChatColor.WHITE + "  " + execution.getCategory() + ": " +
                        String.format("%.2f ms", execution.getExecutionTimeNanos() / 1_000_000.0));
            }
        }

        // Export options
        sender.sendMessage(ChatColor.YELLOW + "Use " + ChatColor.WHITE + "/scheduler metrics export" +
                ChatColor.YELLOW + " for full report");
    }

    /**
     * Schedules periodic export of metrics.
     *
     * @param intervalMinutes The interval in minutes
     * @param exportDirectory The directory to export to
     */
    public void schedulePeriodicExport(int intervalMinutes, File exportDirectory) {
        if (!exportDirectory.exists()) {
            exportDirectory.mkdirs();
        }

        // Schedule the export task
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm");
            String timestamp = dateFormat.format(new Date());

            // Export metrics
            File csvFile = new File(exportDirectory, "metrics_" + timestamp + ".csv");
            File htmlFile = new File(exportDirectory, "metrics_" + timestamp + ".html");

            exportToCsv(csvFile);
            exportToHtml(htmlFile);

            // Keep only the last 10 files
            cleanupOldExports(exportDirectory, 10);

        }, 20 * 60, 20 * 60 * intervalMinutes); // Convert minutes to ticks
    }

    /**
     * Cleans up old export files, keeping only the most recent ones.
     *
     * @param directory The directory containing export files
     * @param keepCount The number of files to keep
     */
    private void cleanupOldExports(File directory, int keepCount) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }

        // Get all metric files
        File[] files = directory.listFiles((dir, name) ->
                name.startsWith("metrics_") && (name.endsWith(".csv") || name.endsWith(".html")));

        if (files == null || files.length <= keepCount) {
            return;
        }

        // Sort by last modified time (oldest first)
        List<File> fileList = new ArrayList<>(files.length);
        for (File file : files) {
            fileList.add(file);
        }
        fileList.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        // Delete the oldest files
        int deleteCount = fileList.size() - keepCount;
        for (int i = 0; i < deleteCount; i++) {
            fileList.get(i).delete();
        }
    }

    /**
     * Formats a duration in milliseconds to a human-readable string.
     *
     * @param durationMs The duration in milliseconds
     * @return A formatted string
     */
    private String formatDuration(long durationMs) {
        long seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60;
        long hours = TimeUnit.MILLISECONDS.toHours(durationMs) % 24;
        long days = TimeUnit.MILLISECONDS.toDays(durationMs);

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            sb.append(minutes).append("m ");
        }
        sb.append(seconds).append("s");

        return sb.toString();
    }

    /**
     * Creates a metrics snapshot for point-in-time comparison.
     *
     * @return A map containing the metrics snapshot
     */
    public Map<String, Object> createSnapshot() {
        Map<String, Object> snapshot = new HashMap<>();

        // Overall metrics
        snapshot.put("timestamp", System.currentTimeMillis());
        snapshot.put("activeTasks", schedulerMetrics.getActiveTaskCount());
        snapshot.put("completedTasks", schedulerMetrics.getCompletedTaskCount());
        snapshot.put("avgExecutionTimeNanos", schedulerMetrics.getAverageExecutionTimeNanos());

        // Category metrics
        Map<String, Map<String, Object>> categoryData = new HashMap<>();
        for (Map.Entry<String, SchedulerMetrics.CategoryMetrics> entry : schedulerMetrics.getCategorizedMetrics().entrySet()) {
            String category = entry.getKey();
            SchedulerMetrics.CategoryMetrics metrics = entry.getValue();

            Map<String, Object> categoryMetrics = new HashMap<>();
            categoryMetrics.put("active", metrics.getActiveCount());
            categoryMetrics.put("completed", metrics.getCompletedCount());
            categoryMetrics.put("failed", metrics.getFailedCount());
            categoryMetrics.put("avgTimeNanos", metrics.getAverageExecutionTimeNanos());
            categoryMetrics.put("maxTimeNanos", metrics.getMaxExecutionTimeNanos());

            categoryData.put(category, categoryMetrics);
        }
        snapshot.put("categories", categoryData);

        return snapshot;
    }
}