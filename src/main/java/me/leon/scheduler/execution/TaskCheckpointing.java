package me.leon.scheduler.execution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import me.leon.scheduler.api.Task;
import me.leon.scheduler.util.Debug;

/**
 * Provides checkpointing capabilities for long-running tasks.
 * Allows tasks to save progress and resume after interruption.
 */
public class TaskCheckpointing {

    // Maps task IDs to their saved checkpoints
    private final Map<String, Checkpoint<?>> checkpoints;

    // Maps task IDs to their execution stages
    private final Map<Long, Integer> taskStages;

    // Configuration
    private long checkpointIntervalMs = 5000; // Check for checkpoints every 5 seconds

    /**
     * Creates a new task checkpointing manager.
     */
    public TaskCheckpointing() {
        this.checkpoints = new ConcurrentHashMap<>();
        this.taskStages = new ConcurrentHashMap<>();
    }

    /**
     * Sets the checkpoint interval.
     *
     * @param intervalMs The interval in milliseconds
     */
    public void setCheckpointInterval(long intervalMs) {
        this.checkpointIntervalMs = intervalMs;
    }

    /**
     * Creates a checkpointable task that can be resumed after interruption.
     *
     * @param <T> The checkpoint state type
     * @param taskId The unique ID of the task
     * @param initialState Initial state to start with, or null to use last checkpoint
     * @param processor Function that processes the state and returns a new state
     * @return A new task that processes the state
     */
    public <T> Runnable createCheckpointableTask(
            long taskId,
            Supplier<T> initialState,
            Function<T, T> processor) {

        return () -> {
            T state;

            // Check if we have a saved checkpoint
            @SuppressWarnings("unchecked")
            Checkpoint<T> checkpoint = (Checkpoint<T>) checkpoints.get(taskId);

            if (checkpoint != null) {
                Debug.debug("Resuming task " + taskId + " from checkpoint");
                state = checkpoint.getState();
            } else if (initialState != null) {
                state = initialState.get();
            } else {
                // No saved state and no initial state provided
                Debug.log(java.util.logging.Level.WARNING,
                        "No state found for checkpointable task " + taskId);
                return;
            }

            long startTime = System.currentTimeMillis();
            long lastCheckpointTime = startTime;

            try {
                while (true) {
                    // Process the state
                    T newState = processor.apply(state);

                    // Check if we should create a checkpoint
                    long now = System.currentTimeMillis();
                    if (now - lastCheckpointTime >= checkpointIntervalMs) {
                        saveCheckpoint(String.valueOf(taskId), state);
                        lastCheckpointTime = now;
                    }

                    // If state doesn't change, we're done
                    if (newState == null || newState.equals(state)) {
                        // Clean up the checkpoint as task is complete
                        checkpoints.remove(taskId);
                        break;
                    }

                    state = newState;
                }
            } catch (Throwable t) {
                // Save the checkpoint on error so we can resume later
                saveCheckpoint(String.valueOf(taskId), state);
                throw t;
            }
        };
    }

    /**
     * Creates a checkpointable task with multiple stages.
     * Each stage can have its own processor function and state type.
     *
     * @param taskId The unique ID of the task
     * @param stageProcessors Array of stage processors
     * @return A new task that processes through multiple stages
     */
    public Runnable createMultiStageTask(
            final long taskId,
            final StageProcessor<?>... stageProcessors) {

        return () -> {
            // Get the current stage or start from 0
            int currentStage = taskStages.getOrDefault(taskId, 0);

            if (currentStage >= stageProcessors.length) {
                Debug.debug("All stages completed for task " + taskId);
                taskStages.remove(taskId);
                checkpoints.remove(taskId);
                return;
            }

            try {
                // Process each stage in sequence
                for (int stage = currentStage; stage < stageProcessors.length; stage++) {
                    // Update the current stage
                    taskStages.put(taskId, stage);
                    final int currentStageIndex = stage; // Create a final copy for the lambda

                    // Get the stage processor
                    @SuppressWarnings("unchecked")
                    StageProcessor<Object> processor =
                            (StageProcessor<Object>) stageProcessors[currentStageIndex];

                    // Get the checkpoint for this stage
                    String checkpointKey = taskId + "_stage_" + currentStageIndex;
                    @SuppressWarnings("unchecked")
                    Checkpoint<Object> checkpoint =
                            (Checkpoint<Object>) checkpoints.get(checkpointKey);

                    // Get the state to process
                    Object state;
                    if (checkpoint != null) {
                        state = checkpoint.getState();
                    } else if (processor.initialStateSupplier != null) {
                        state = processor.initialStateSupplier.get();
                    } else {
                        // Use null state if neither is available
                        state = null;
                    }

                    // Process this stage
                    Object result = processor.process(state, (s) -> {
                        // Checkpoint callback
                        saveCheckpoint(taskId + "_stage_" + currentStageIndex, s);
                    });

                    // Pass result to next stage if applicable
                    if (currentStageIndex < stageProcessors.length - 1 && result != null) {
                        StageProcessor<?> nextProcessor = stageProcessors[currentStageIndex + 1];
                        if (nextProcessor.initialStateSupplier == null) {
                            // Set the result as initial state for next stage
                            nextProcessor.setInitialState(result);
                        }
                    }
                }

                // All stages completed successfully
                taskStages.remove(taskId);

                // Clean up all checkpoints for this task
                for (int i = 0; i < stageProcessors.length; i++) {
                    checkpoints.remove(taskId + "_stage_" + i);
                }

            } catch (Throwable t) {
                final Integer failedStage = taskStages.get(taskId); // Create a final copy for error reporting
                Debug.log(java.util.logging.Level.WARNING,
                        "Error in multi-stage task " + taskId +
                                " at stage " + failedStage + ": " + t.getMessage());
                throw t;
            }
        };
    }

    /**
     * Saves a checkpoint for a task.
     *
     * @param <T> The checkpoint state type
     * @param taskId The task ID (as a String)
     * @param state The state to save
     */
    private <T> void saveCheckpoint(String taskId, T state) {
        checkpoints.put(taskId, new Checkpoint<>(state));
        Debug.debug("Saved checkpoint for task " + taskId);
    }

    /**
     * Checks if a checkpoint exists for a task.
     *
     * @param taskId The task ID
     * @return true if a checkpoint exists
     */
    public boolean hasCheckpoint(long taskId) {
        return checkpoints.containsKey(taskId);
    }

    /**
     * Gets the current stage of a multi-stage task.
     *
     * @param taskId The task ID
     * @return The current stage, or -1 if not found
     */
    public int getCurrentStage(long taskId) {
        return taskStages.getOrDefault(taskId, -1);
    }

    /**
     * Removes all checkpoints for a task.
     *
     * @param taskId The task ID
     */
    public void clearCheckpoints(long taskId) {
        checkpoints.remove(taskId);
        taskStages.remove(taskId);

        // Clean up any stage checkpoints
        for (int i = 0; i < 100; i++) { // Reasonable limit
            checkpoints.remove(taskId + "_stage_" + i);
        }
    }

    /**
     * Creates a processor for a stage in a multi-stage task.
     *
     * @param <T> The state type
     * @param initialStateSupplier Supplier for the initial state
     * @param processor Function to process the state
     * @return A new stage processor
     */
    public static <T> StageProcessor<T> createStageProcessor(
            Supplier<T> initialStateSupplier,
            BiFunction<T, Consumer<T>, T> processor) {

        return new StageProcessor<>(initialStateSupplier, processor);
    }

    /**
     * Class that stores checkpoint state.
     */
    private static class Checkpoint<T> {
        private final T state;
        private final long timestamp;

        /**
         * Creates a new checkpoint.
         *
         * @param state The state to checkpoint
         */
        public Checkpoint(T state) {
            this.state = state;
            this.timestamp = System.currentTimeMillis();
        }

        /**
         * Gets the checkpointed state.
         *
         * @return The state
         */
        public T getState() {
            return state;
        }

        /**
         * Gets the time when this checkpoint was created.
         *
         * @return The timestamp in milliseconds
         */
        public long getTimestamp() {
            return timestamp;
        }
    }

    /**
     * Class representing a processor for a stage in a multi-stage task.
     */
    public static class StageProcessor<T> {
        private Supplier<T> initialStateSupplier;
        private final BiFunction<T, Consumer<T>, T> processorFunction;

        /**
         * Creates a new stage processor.
         *
         * @param initialStateSupplier Supplier for the initial state
         * @param processorFunction Function to process the state
         */
        public StageProcessor(
                Supplier<T> initialStateSupplier,
                BiFunction<T, Consumer<T>, T> processorFunction) {

            this.initialStateSupplier = initialStateSupplier;
            this.processorFunction = processorFunction;
        }

        /**
         * Sets the initial state.
         *
         * @param state The initial state
         */
        @SuppressWarnings("unchecked")
        public void setInitialState(Object state) {
            final T typedState = (T) state;
            this.initialStateSupplier = () -> typedState;
        }

        /**
         * Processes the state.
         *
         * @param state The state to process
         * @param checkpointCallback Callback to save checkpoints
         * @return The processed state
         */
        public T process(T state, Consumer<T> checkpointCallback) {
            return processorFunction.apply(state, checkpointCallback);
        }
    }
}