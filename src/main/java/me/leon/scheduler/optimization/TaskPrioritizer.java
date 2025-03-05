package me.leon.scheduler.optimization;

import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

import me.leon.scheduler.api.Task;
import me.leon.scheduler.core.TPSMonitor;
import me.leon.scheduler.util.Debug;

/**
 * Prioritizes tasks based on their importance and impact on server performance.
 * Helps optimize task execution by running the most critical tasks first.
 */
public class TaskPrioritizer {

    /**
     * Task priority levels.
     */
    public enum Priority {
        CRITICAL(10),  // Must execute immediately (e.g., player teleportation)
        HIGH(7),       // Important but can wait briefly (e.g., chunk generation)
        NORMAL(5),     // Standard priority (e.g., mob AI)
        LOW(3),        // Background tasks (e.g., statistics)
        LOWEST(1);     // Can wait indefinitely (e.g., aesthetic updates)

        private final int value;

        Priority(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    private final TPSMonitor tpsMonitor;
    private final Map<Long, PrioritizedTask> taskMap;
    private final PriorityQueue<PrioritizedTask> taskQueue;
    private final AtomicInteger lastTaskOrder;

    /**
     * Creates a new task prioritizer.
     *
     * @param tpsMonitor The TPS monitor to use for dynamic prioritization
     */
    public TaskPrioritizer(TPSMonitor tpsMonitor) {
        this.tpsMonitor = tpsMonitor;
        this.taskMap = new ConcurrentHashMap<>();

        // Create a queue that prioritizes by score first, then insertion order
        this.taskQueue = new PriorityQueue<>(
                Comparator.<PrioritizedTask>comparingDouble(task -> -task.getScore())
                        .thenComparingInt(PrioritizedTask::getOrder)
        );

        this.lastTaskOrder = new AtomicInteger(0);
    }

    /**
     * Adds a task to the prioritizer with the specified priority.
     *
     * @param task The task to prioritize
     * @param priority The priority level
     */
    public void addTask(Task task, Priority priority) {
        if (task == null) {
            return;
        }

        PrioritizedTask prioritizedTask = new PrioritizedTask(
                task,
                priority,
                lastTaskOrder.incrementAndGet()
        );

        taskMap.put(task.getTaskId(), prioritizedTask);
        taskQueue.offer(prioritizedTask);

        if (Debug.isDebugEnabled()) {
            Debug.debug("Added task " + task.getTaskId() + " with priority " + priority);
        }
    }

    /**
     * Adds a task to the prioritizer with the specified priority and custom impact function.
     *
     * @param task The task to prioritize
     * @param priority The priority level
     * @param impactFunction Function to calculate the task's impact score
     */
    public void addTask(Task task, Priority priority, ToDoubleFunction<Task> impactFunction) {
        if (task == null) {
            return;
        }

        PrioritizedTask prioritizedTask = new PrioritizedTask(
                task,
                priority,
                lastTaskOrder.incrementAndGet(),
                impactFunction
        );

        taskMap.put(task.getTaskId(), prioritizedTask);
        taskQueue.offer(prioritizedTask);
    }

    /**
     * Removes a task from the prioritizer.
     *
     * @param taskId The ID of the task to remove
     * @return true if the task was found and removed, false otherwise
     */
    public boolean removeTask(long taskId) {
        PrioritizedTask task = taskMap.remove(taskId);
        if (task != null) {
            // Mark as removed so it's ignored in the queue
            task.setRemoved(true);
            return true;
        }
        return false;
    }

    /**
     * Gets the next highest priority task.
     *
     * @return The highest priority task, or null if no tasks are pending
     */
    public Task getNextTask() {
        PrioritizedTask task;

        // Find the highest priority task that isn't removed or completed
        while ((task = taskQueue.poll()) != null) {
            if (task.isRemoved() || task.getTask().isDone()) {
                // Skip removed or completed tasks
                taskMap.remove(task.getTask().getTaskId());
                continue;
            }

            // Re-offer the task with an updated score
            updateTaskScore(task);
            taskQueue.offer(task);

            return task.getTask();
        }

        return null;
    }

    /**
     * Updates the scores of all tasks based on current conditions.
     * Call this periodically to adapt to changing server conditions.
     */
    public void updateAllScores() {
        // Create a new queue with updated scores
        PriorityQueue<PrioritizedTask> newQueue = new PriorityQueue<>(
                Comparator.<PrioritizedTask>comparingDouble(task -> -task.getScore())
                        .thenComparingInt(PrioritizedTask::getOrder)
        );

        PrioritizedTask task;
        while ((task = taskQueue.poll()) != null) {
            if (task.isRemoved() || task.getTask().isDone()) {
                // Remove completed or cancelled tasks
                taskMap.remove(task.getTask().getTaskId());
                continue;
            }

            // Update score and add to new queue
            updateTaskScore(task);
            newQueue.offer(task);
        }

        // Replace the old queue with the new one
        taskQueue.clear();
        taskQueue.addAll(newQueue);
    }

    /**
     * Updates the score of a task based on current conditions.
     *
     * @param task The task to update
     */
    private void updateTaskScore(PrioritizedTask task) {
        double baseScore = task.getPriority().getValue();

        // Apply impact function if available
        if (task.getImpactFunction() != null) {
            double impact = task.getImpactFunction().applyAsDouble(task.getTask());
            baseScore *= (1.0 + impact);
        }

        // Adjust based on execution statistics if available
        if (task.getTask().getExecutionCount() > 0) {
            double avgExecutionTime = task.getTask().getAverageExecutionTimeNanos() / 1_000_000.0; // Convert to ms

            // Penalize tasks that take a long time during low TPS
            if (tpsMonitor != null && tpsMonitor.isLagging(17.0) && avgExecutionTime > 10.0) {
                baseScore *= (1.0 - Math.min(0.5, avgExecutionTime / 100.0));
            }
        }

        task.setScore(baseScore);
    }

    /**
     * Gets the number of tasks currently in the prioritizer.
     *
     * @return The number of tasks
     */
    public int getTaskCount() {
        return taskMap.size();
    }

    /**
     * Clears all tasks from the prioritizer.
     */
    public void clear() {
        taskMap.clear();
        taskQueue.clear();
    }

    /**
     * Class representing a prioritized task.
     */
    private static class PrioritizedTask {
        private final Task task;
        private final Priority priority;
        private final int order;
        private final ToDoubleFunction<Task> impactFunction;
        private volatile boolean removed;
        private volatile double score;

        public PrioritizedTask(Task task, Priority priority, int order) {
            this(task, priority, order, null);
        }

        public PrioritizedTask(Task task, Priority priority, int order, ToDoubleFunction<Task> impactFunction) {
            this.task = task;
            this.priority = priority;
            this.order = order;
            this.impactFunction = impactFunction;
            this.removed = false;
            this.score = priority.getValue();
        }

        public Task getTask() {
            return task;
        }

        public Priority getPriority() {
            return priority;
        }

        public int getOrder() {
            return order;
        }

        public ToDoubleFunction<Task> getImpactFunction() {
            return impactFunction;
        }

        public boolean isRemoved() {
            return removed;
        }

        public void setRemoved(boolean removed) {
            this.removed = removed;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }
    }
}