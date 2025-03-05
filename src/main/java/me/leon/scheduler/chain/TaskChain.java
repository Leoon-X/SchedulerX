package me.leon.scheduler.chain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.bukkit.plugin.Plugin;

import me.leon.scheduler.api.Chain;
import me.leon.scheduler.api.Task;
import me.leon.scheduler.core.TaskManager;
import me.leon.scheduler.util.Debug;
import me.leon.scheduler.util.TickUtil;

/**
 * Implementation of a task chain.
 * Executes a sequence of tasks in order with controlled transitions.
 */
public class TaskChain implements Chain {

    private final Plugin plugin;
    private final TaskManager taskManager;
    private final List<ChainLink> links;
    private final CompletableFuture<Void> completionFuture;
    private final AtomicBoolean started;
    private final AtomicBoolean cancelled;
    private final AtomicInteger currentLinkIndex;
    private Task currentTask;

    /**
     * Creates a new task chain.
     *
     * @param plugin The owning plugin
     * @param taskManager The task manager
     * @param links The links in the chain
     */
    TaskChain(Plugin plugin, TaskManager taskManager, List<ChainLink> links) {
        this.plugin = plugin;
        this.taskManager = taskManager;
        this.links = new ArrayList<>(links);
        this.completionFuture = new CompletableFuture<>();
        this.started = new AtomicBoolean(false);
        this.cancelled = new AtomicBoolean(false);
        this.currentLinkIndex = new AtomicInteger(-1);
        this.currentTask = null;
    }

    @Override
    public Task execute() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Chain already started");
        }

        // Begin chain execution
        executeNextLink();

        // Return a task that represents the entire chain
        return new ChainTask();
    }

    @Override
    public boolean cancel() {
        if (cancelled.compareAndSet(false, true)) {
            // Cancel the current task if there is one
            if (currentTask != null) {
                currentTask.cancel();
            }

            // Complete the future exceptionally
            completionFuture.completeExceptionally(new InterruptedException("Chain cancelled"));
            return true;
        }
        return false;
    }

    @Override
    public CompletableFuture<Void> getCompletionFuture() {
        return completionFuture;
    }

    /**
     * Executes the next link in the chain.
     */
    private void executeNextLink() {
        if (cancelled.get()) {
            return;
        }

        // Move to the next link
        int nextIndex = currentLinkIndex.incrementAndGet();

        // Check if we've reached the end of the chain
        if (nextIndex >= links.size()) {
            completionFuture.complete(null);
            return;
        }

        // Get the next link
        ChainLink link = links.get(nextIndex);

        try {
            // Execute the link based on its type
            switch (link.getType()) {
                case SYNC:
                    executeSyncLink(link);
                    break;
                case ASYNC:
                    executeAsyncLink(link);
                    break;
                case DELAY:
                    executeDelayLink(link);
                    break;
                case CONDITIONAL:
                    executeConditionalLink(link);
                    break;
                default:
                    // Shouldn't happen, but continue chain if it does
                    Debug.log(java.util.logging.Level.WARNING, "Unknown link type: " + link.getType());
                    executeNextLink();
            }
        } catch (Exception e) {
            Debug.log(java.util.logging.Level.SEVERE, "Error executing chain link: " + e.getMessage());
            completionFuture.completeExceptionally(e);
        }
    }

    /**
     * Executes a synchronous link.
     *
     * @param link The link to execute
     */
    private void executeSyncLink(ChainLink link) {
        currentTask = taskManager.runSync(() -> {
            try {
                link.getAction().run();
            } catch (Exception e) {
                Debug.log(java.util.logging.Level.SEVERE, "Error in sync chain link: " + e.getMessage());
                completionFuture.completeExceptionally(e);
                return;
            }

            // Continue to the next link
            executeNextLink();
        });
    }

    /**
     * Executes an asynchronous link.
     *
     * @param link The link to execute
     */
    private void executeAsyncLink(ChainLink link) {
        currentTask = taskManager.runAsync(() -> {
            try {
                link.getAction().run();
            } catch (Exception e) {
                Debug.log(java.util.logging.Level.SEVERE, "Error in async chain link: " + e.getMessage());
                completionFuture.completeExceptionally(e);
                return;
            }

            // Continue to the next link
            executeNextLink();
        });
    }

    /**
     * Executes a delay link.
     *
     * @param link The link to execute
     */
    private void executeDelayLink(ChainLink link) {
        long delayTicks = link.getDelayTicks();

        currentTask = taskManager.runLater(() -> {
            // Continue to the next link after the delay
            executeNextLink();
        }, delayTicks);
    }

    /**
     * Executes a conditional link.
     *
     * @param link The link to execute
     */
    private void executeConditionalLink(ChainLink link) {
        // Use sync execution for condition evaluation to avoid threading issues
        currentTask = taskManager.runSync(() -> {
            boolean conditionResult = false;

            try {
                conditionResult = link.getCondition().get();
            } catch (Exception e) {
                Debug.log(java.util.logging.Level.SEVERE, "Error evaluating chain condition: " + e.getMessage());
                completionFuture.completeExceptionally(e);
                return;
            }

            if (conditionResult) {
                try {
                    link.getAction().run();
                } catch (Exception e) {
                    Debug.log(java.util.logging.Level.SEVERE, "Error in conditional chain link: " + e.getMessage());
                    completionFuture.completeExceptionally(e);
                    return;
                }
            }

            // Continue to the next link regardless of condition result
            executeNextLink();
        });
    }

    /**
     * Task implementation that represents the entire chain.
     */
    private class ChainTask implements Task {

        @Override
        public boolean cancel() {
            return TaskChain.this.cancel();
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isRunning() {
            return started.get() && !isDone();
        }

        @Override
        public boolean isDone() {
            return completionFuture.isDone();
        }

        @Override
        public long getTaskId() {
            return System.identityHashCode(this);
        }

        @Override
        public CompletableFuture<Void> getCompletionFuture() {
            return completionFuture;
        }

        @Override
        public Task thenRun(Runnable action) {
            completionFuture.thenRun(action);
            return this;
        }

        @Override
        public Task exceptionally(Consumer<Throwable> action) {
            completionFuture.exceptionally(throwable -> {
                action.accept(throwable);
                return null;
            });
            return this;
        }

        @Override
        public long getLastExecutionTimeNanos() {
            // Not applicable for a chain
            return 0;
        }

        @Override
        public long getAverageExecutionTimeNanos() {
            // Not applicable for a chain
            return 0;
        }

        @Override
        public int getExecutionCount() {
            return currentLinkIndex.get() + 1;
        }
    }
}