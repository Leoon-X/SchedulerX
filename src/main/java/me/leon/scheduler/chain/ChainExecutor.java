package me.leon.scheduler.chain;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.plugin.Plugin;

import me.leon.scheduler.api.Chain;
import me.leon.scheduler.api.Task;
import me.leon.scheduler.core.TaskManager;
import me.leon.scheduler.core.TPSMonitor;
import me.leon.scheduler.util.Debug;

/**
 * Manages and optimizes the execution of task chains.
 * Provides methods to create, track, and control chains.
 */
public class ChainExecutor {

    private final Plugin plugin;
    private final TaskManager taskManager;
    private final TPSMonitor tpsMonitor;
    private final AtomicLong chainIdCounter;
    private final Map<Long, TaskChain> activeChains;
    private final Set<Long> cancelledChains;

    /**
     * Creates a new chain executor.
     *
     * @param plugin The owning plugin
     * @param taskManager The task manager
     * @param tpsMonitor The TPS monitor
     */
    public ChainExecutor(Plugin plugin, TaskManager taskManager, TPSMonitor tpsMonitor) {
        this.plugin = plugin;
        this.taskManager = taskManager;
        this.tpsMonitor = tpsMonitor;
        this.chainIdCounter = new AtomicLong(0);
        this.activeChains = new ConcurrentHashMap<>();
        this.cancelledChains = ConcurrentHashMap.newKeySet();
    }

    /**
     * Creates a new chain builder.
     *
     * @return A new chain builder
     */
    public Chain.Builder newChain() {
        return new ChainBuilder(plugin, taskManager);
    }

    /**
     * Executes a chain and returns a task representing the chain.
     *
     * @param chain The chain to execute
     * @return A task representing the chain execution
     */
    public Task execute(Chain chain) {
        if (chain == null) {
            throw new IllegalArgumentException("Chain cannot be null");
        }

        long chainId = chainIdCounter.incrementAndGet();

        // Check if we need to adjust timing based on server load
        if (tpsMonitor != null && tpsMonitor.isLagging(16.0)) {
            Debug.debug("Server is lagging (TPS: " + String.format("%.2f", tpsMonitor.getCurrentTps()) +
                    "), delaying chain execution");

            // Delay chain execution during lag
            return taskManager.runLater(() -> startChain(chain, chainId), 10);
        } else {
            // Execute immediately
            return startChain(chain, chainId);
        }
    }

    /**
     * Starts execution of a chain.
     *
     * @param chain The chain to execute
     * @param chainId The unique ID of the chain
     * @return A task representing the chain execution
     */
    private Task startChain(Chain chain, long chainId) {
        if (chain instanceof TaskChain) {
            TaskChain taskChain = (TaskChain) chain;

            // Store the chain for tracking
            activeChains.put(chainId, taskChain);

            // When the chain completes, remove it from tracking
            taskChain.getCompletionFuture().whenComplete((result, error) -> {
                activeChains.remove(chainId);
                cancelledChains.remove(chainId);

                if (error != null && !(error instanceof InterruptedException)) {
                    Debug.log(java.util.logging.Level.WARNING,
                            "Chain " + chainId + " failed: " + error.getMessage());
                }
            });

            // Execute the chain
            return taskChain.execute();
        } else {
            // For non-TaskChain implementations, just execute without tracking
            return chain.execute();
        }
    }

    /**
     * Cancels a specific chain.
     *
     * @param chainId The ID of the chain to cancel
     * @return true if the chain was found and cancelled, false otherwise
     */
    public boolean cancelChain(long chainId) {
        TaskChain chain = activeChains.get(chainId);
        if (chain != null && !cancelledChains.contains(chainId)) {
            cancelledChains.add(chainId);
            return chain.cancel();
        }
        return false;
    }

    /**
     * Cancels all active chains.
     */
    public void cancelAllChains() {
        for (TaskChain chain : activeChains.values()) {
            chain.cancel();
        }

        activeChains.clear();
        cancelledChains.clear();
    }

    /**
     * Gets the number of active chains.
     *
     * @return The number of active chains
     */
    public int getActiveChainCount() {
        return activeChains.size();
    }

    /**
     * Shuts down the executor and cancels all chains.
     */
    public void shutdown() {
        cancelAllChains();
    }
}