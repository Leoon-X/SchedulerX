package me.leon.scheduler.optimization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.plugin.Plugin;

import me.leon.scheduler.core.TPSMonitor;
import me.leon.scheduler.util.Debug;

/**
 * Uses machine learning techniques to predict server load patterns and optimize task scheduling.
 * Helps in scheduling tasks during predicted low-load periods.
 */
public class PredictiveScheduler {

    private static final int DEFAULT_SAMPLE_SIZE = 100;
    private static final int DEFAULT_PREDICTION_WINDOW = 60; // In minutes
    private static final int CYCLES_PER_DAY = 1440; // Minutes in a day

    private final Plugin plugin;
    private final TPSMonitor tpsMonitor;

    // Historical data for prediction
    private final Queue<LoadSample> loadHistory;
    private final Map<Integer, List<Double>> cyclicalPatterns;
    private final Map<String, TaskPattern> taskPatterns;

    // Prediction parameters
    private int sampleSize;
    private int predictionWindowMinutes;

    // Last prediction time
    private long lastPredictionTime;
    private final Map<Integer, Double> loadPredictions;

    // Current scheduling cycle
    private final AtomicInteger currentMinute;

    /**
     * Creates a new predictive scheduler.
     *
     * @param plugin The owning plugin
     * @param tpsMonitor The TPS monitor for tracking server load
     */
    public PredictiveScheduler(Plugin plugin, TPSMonitor tpsMonitor) {
        this.plugin = plugin;
        this.tpsMonitor = tpsMonitor;
        this.loadHistory = new LinkedList<>();
        this.cyclicalPatterns = new HashMap<>();
        this.taskPatterns = new ConcurrentHashMap<>();
        this.loadPredictions = new ConcurrentHashMap<>();
        this.currentMinute = new AtomicInteger(getCurrentMinuteOfDay());

        this.sampleSize = DEFAULT_SAMPLE_SIZE;
        this.predictionWindowMinutes = DEFAULT_PREDICTION_WINDOW;
        this.lastPredictionTime = 0;

        // Initialize patterns
        initializeCyclicalPatterns();
    }

    /**
     * Initializes cyclical pattern detection.
     */
    private void initializeCyclicalPatterns() {
        // Initialize daily pattern (24 hours)
        cyclicalPatterns.put(1440, new ArrayList<>());

        // Initialize hourly pattern (60 minutes)
        cyclicalPatterns.put(60, new ArrayList<>());
    }

    /**
     * Records current server load for prediction.
     */
    public void recordCurrentLoad() {
        if (tpsMonitor == null) {
            return;
        }

        double currentTps = tpsMonitor.getCurrentTps();
        double loadFactor = 1.0 - (currentTps / 20.0); // 0.0 = no load, 1.0 = full load
        int minute = getCurrentMinuteOfDay();

        // Update current minute
        currentMinute.set(minute);

        // Create new sample
        LoadSample sample = new LoadSample(System.currentTimeMillis(), minute, loadFactor);

        // Add to history
        loadHistory.add(sample);

        // Keep history size limited
        while (loadHistory.size() > sampleSize) {
            loadHistory.poll();
        }

        // Update cyclical patterns
        for (Map.Entry<Integer, List<Double>> entry : cyclicalPatterns.entrySet()) {
            int cycle = entry.getKey();
            List<Double> pattern = entry.getValue();

            int position = minute % cycle;

            // Ensure list has enough elements
            while (pattern.size() <= position) {
                pattern.add(0.0);
            }

            // Update exponential moving average
            double current = pattern.get(position);
            double alpha = 0.1; // Weight for the current observation
            double updated = (current * (1 - alpha)) + (loadFactor * alpha);
            pattern.set(position, updated);
        }

        // Check if we need to update predictions
        long now = System.currentTimeMillis();
        if (now - lastPredictionTime > TimeUnit.MINUTES.toMillis(5)) {
            updateLoadPredictions();
            lastPredictionTime = now;
        }
    }

    /**
     * Updates load predictions for the next prediction window.
     */
    private void updateLoadPredictions() {
        // Clear previous predictions
        loadPredictions.clear();

        int currentMin = currentMinute.get();

        // Generate predictions for the next window
        for (int i = 0; i < predictionWindowMinutes; i++) {
            int futureMinute = (currentMin + i) % CYCLES_PER_DAY;

            // Combine predictions from different patterns
            double prediction = 0.0;
            int patterns = 0;

            for (Map.Entry<Integer, List<Double>> entry : cyclicalPatterns.entrySet()) {
                int cycle = entry.getKey();
                List<Double> pattern = entry.getValue();

                int position = futureMinute % cycle;
                if (pattern.size() > position) {
                    prediction += pattern.get(position);
                    patterns++;
                }
            }

            // Average the predictions
            if (patterns > 0) {
                prediction /= patterns;
                loadPredictions.put(futureMinute, prediction);
            }
        }

        Debug.debug("Updated load predictions for the next " + predictionWindowMinutes + " minutes");
    }

    /**
     * Records a task execution to learn patterns.
     *
     * @param taskType The type of task
     * @param executionTimeNanos The execution time in nanoseconds
     */
    public void recordTaskExecution(String taskType, long executionTimeNanos) {
        TaskPattern pattern = taskPatterns.computeIfAbsent(taskType, k -> new TaskPattern());
        pattern.recordExecution(executionTimeNanos, currentMinute.get());
    }

    /**
     * Predicts the best time to schedule a task within the prediction window.
     *
     * @param estimatedDurationMs The estimated duration of the task in milliseconds
     * @param urgency How urgent the task is (0.0-1.0, where 0 = not urgent)
     * @return The recommended delay in minutes, or 0 for immediate execution
     */
    public int recommendSchedulingTime(long estimatedDurationMs, double urgency) {
        if (urgency >= 0.8) {
            // Highly urgent tasks should run immediately
            return 0;
        }

        if (loadPredictions.isEmpty()) {
            // No predictions available
            updateLoadPredictions();
            if (loadPredictions.isEmpty()) {
                return 0; // Default to immediate execution
            }
        }

        int currentMin = currentMinute.get();

        // Find the window with the lowest predicted load
        double lowestLoad = 1.0;
        int bestDelay = 0;

        // Maximum delay depends on urgency
        int maxDelay = (int) ((1.0 - urgency) * predictionWindowMinutes);

        for (int delay = 0; delay <= maxDelay; delay++) {
            int futureMinute = (currentMin + delay) % CYCLES_PER_DAY;

            Double prediction = loadPredictions.get(futureMinute);
            if (prediction != null && prediction < lowestLoad) {
                lowestLoad = prediction;
                bestDelay = delay;
            }
        }

        return bestDelay;
    }

    /**
     * Recommends the optimal scheduling for a specific task type.
     *
     * @param taskType The type of task
     * @param urgency How urgent the task is (0.0-1.0)
     * @return The recommended delay in minutes
     */
    public int recommendForTaskType(String taskType, double urgency) {
        TaskPattern pattern = taskPatterns.get(taskType);

        if (pattern == null) {
            // No pattern data for this task type
            return recommendSchedulingTime(0, urgency);
        }

        // Use the task's typical duration for better prediction
        long avgDurationMs = (long) (pattern.getAverageExecutionTimeNanos() / 1_000_000.0);
        return recommendSchedulingTime(avgDurationMs, urgency);
    }

    /**
     * Gets the current minute of the day (0-1439).
     *
     * @return The current minute of the day
     */
    private int getCurrentMinuteOfDay() {
        long now = System.currentTimeMillis();
        long today = now - (now % TimeUnit.DAYS.toMillis(1));
        long minutesSinceMidnight = (now - today) / TimeUnit.MINUTES.toMillis(1);
        return (int) minutesSinceMidnight;
    }

    /**
     * Converts a delay in minutes to server ticks.
     *
     * @param delayMinutes The delay in minutes
     * @return The delay in server ticks
     */
    public long minutesToTicks(int delayMinutes) {
        return delayMinutes * 60 * 20; // 20 tps * 60 seconds
    }

    /**
     * Gets the current predictive model accuracy based on recent history.
     *
     * @return The accuracy as a percentage (0-100)
     */
    public double getModelAccuracy() {
        if (loadHistory.size() < 10) {
            return 0; // Not enough data
        }

        // Compare recent predictions to actual values
        double totalError = 0;
        int comparisons = 0;

        List<LoadSample> recentSamples = new ArrayList<>(loadHistory);
        for (int i = 5; i < recentSamples.size(); i++) {
            LoadSample sample = recentSamples.get(i);
            LoadSample previous = recentSamples.get(i - 5); // Sample from 5 minutes ago

            int predictedMinute = (previous.minuteOfDay + 5) % CYCLES_PER_DAY;
            Double prediction = null;

            // Find if we had a prediction for this minute
            for (Map.Entry<Integer, List<Double>> entry : cyclicalPatterns.entrySet()) {
                List<Double> pattern = entry.getValue();
                int position = predictedMinute % entry.getKey();

                if (pattern.size() > position) {
                    prediction = pattern.get(position);
                    break;
                }
            }

            if (prediction != null) {
                double error = Math.abs(prediction - sample.loadFactor);
                totalError += error;
                comparisons++;
            }
        }

        if (comparisons == 0) {
            return 0;
        }

        double avgError = totalError / comparisons;
        return (1.0 - avgError) * 100; // Convert to percentage accuracy
    }

    /**
     * Sets the sample size for load history.
     *
     * @param sampleSize The number of samples to keep
     */
    public void setSampleSize(int sampleSize) {
        this.sampleSize = sampleSize;
    }

    /**
     * Sets the prediction window.
     *
     * @param minutes The prediction window in minutes
     */
    public void setPredictionWindow(int minutes) {
        this.predictionWindowMinutes = minutes;
    }

    /**
     * Gets a map of predicted load factors for each minute in the prediction window.
     *
     * @return Map of minute to predicted load factor (0.0-1.0)
     */
    public Map<Integer, Double> getPredictions() {
        if (loadPredictions.isEmpty()) {
            updateLoadPredictions();
        }
        return new HashMap<>(loadPredictions);
    }

    /**
     * Class representing a server load sample.
     */
    private static class LoadSample {
        private final long timestamp;
        private final int minuteOfDay;
        private final double loadFactor;

        /**
         * Creates a new load sample.
         *
         * @param timestamp The timestamp when the sample was taken
         * @param minuteOfDay The minute of day (0-1439)
         * @param loadFactor The load factor (0.0-1.0)
         */
        public LoadSample(long timestamp, int minuteOfDay, double loadFactor) {
            this.timestamp = timestamp;
            this.minuteOfDay = minuteOfDay;
            this.loadFactor = loadFactor;
        }
    }

    /**
     * Class representing a task execution pattern.
     */
    private static class TaskPattern {
        private final Map<Integer, Double> minuteFrequency;
        private double totalExecutionTimeNanos;
        private int executionCount;

        /**
         * Creates a new task pattern.
         */
        public TaskPattern() {
            this.minuteFrequency = new HashMap<>();
            this.totalExecutionTimeNanos = 0;
            this.executionCount = 0;
        }

        /**
         * Records an execution of the task.
         *
         * @param executionTimeNanos The execution time in nanoseconds
         * @param minuteOfDay The minute of day when executed (0-1439)
         */
        public void recordExecution(long executionTimeNanos, int minuteOfDay) {
            // Record execution time
            totalExecutionTimeNanos += executionTimeNanos;
            executionCount++;

            // Update frequency for this minute
            Double current = minuteFrequency.getOrDefault(minuteOfDay, 0.0);
            minuteFrequency.put(minuteOfDay, current + 1.0);

            // Normalize frequencies so they sum to 1.0
            normalizeFrequencies();
        }

        /**
         * Normalizes the minute frequencies to sum to 1.0.
         */
        private void normalizeFrequencies() {
            double sum = 0.0;
            for (Double value : minuteFrequency.values()) {
                sum += value;
            }

            if (sum > 0) {
                for (Map.Entry<Integer, Double> entry : minuteFrequency.entrySet()) {
                    entry.setValue(entry.getValue() / sum);
                }
            }
        }

        /**
         * Gets the average execution time in nanoseconds.
         *
         * @return The average execution time
         */
        public double getAverageExecutionTimeNanos() {
            return executionCount > 0 ? totalExecutionTimeNanos / executionCount : 0;
        }

        /**
         * Gets the most frequent minute for this task.
         *
         * @return The minute with highest frequency, or -1 if no data
         */
        public int getMostFrequentMinute() {
            if (minuteFrequency.isEmpty()) {
                return -1;
            }

            int bestMinute = -1;
            double highestFrequency = 0;

            for (Map.Entry<Integer, Double> entry : minuteFrequency.entrySet()) {
                if (entry.getValue() > highestFrequency) {
                    highestFrequency = entry.getValue();
                    bestMinute = entry.getKey();
                }
            }

            return bestMinute;
        }
    }
}