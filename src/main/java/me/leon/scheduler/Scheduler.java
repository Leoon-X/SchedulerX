package me.leon.scheduler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.plugin.Plugin;

import me.leon.scheduler.api.Chain;
import me.leon.scheduler.api.Condition;
import me.leon.scheduler.api.Task;
import me.leon.scheduler.chain.ChainBuilder;
import me.leon.scheduler.chain.ChainExecutor;
import me.leon.scheduler.condition.ServerCondition;
import me.leon.scheduler.condition.TimeCondition;
import me.leon.scheduler.core.MemoryMonitor;
import me.leon.scheduler.core.TPSMonitor;
import me.leon.scheduler.core.TaskManager;
import me.leon.scheduler.core.ThreadPoolManager;
import me.leon.scheduler.core.ThreadPoolOrchestrator;
import me.leon.scheduler.execution.AsyncExecutor;
import me.leon.scheduler.execution.BatchExecutor;
import me.leon.scheduler.execution.ExecutionStrategy;
import me.leon.scheduler.execution.SyncExecutor;
import me.leon.scheduler.execution.TaskCheckpointing;
import me.leon.scheduler.metrics.MetricsExporter;
import me.leon.scheduler.metrics.SchedulerMetrics;
import me.leon.scheduler.metrics.TaskTracker;
import me.leon.scheduler.optimization.AdaptiveTiming;
import me.leon.scheduler.optimization.HotPathOptimizer;
import me.leon.scheduler.optimization.LoadBalancer;
import me.leon.scheduler.optimization.ObjectPool;
import me.leon.scheduler.optimization.PluginAwareBalancer;
import me.leon.scheduler.optimization.PredictiveScheduler;
import me.leon.scheduler.optimization.TaskCoalescer;
import me.leon.scheduler.optimization.TaskLocalityOptimizer;
import me.leon.scheduler.optimization.TaskPrioritizer;
import me.leon.scheduler.optimization.TaskPrioritizer.Priority;
import me.leon.scheduler.util.Debug;

/**
 * Main entry point for the high-performance task scheduling system.
 * Provides optimized methods for scheduling Minecraft tasks.
 */
public final class Scheduler {

    private static Scheduler instance;

    private final Plugin plugin;

    // Core components
    private final TaskManager taskManager;
    private final ThreadPoolManager threadPoolManager;
    private final ThreadPoolOrchestrator threadPoolOrchestrator;
    private final TPSMonitor tpsMonitor;
    private final MemoryMonitor memoryMonitor;

    // Execution strategies
    private final ExecutionStrategy syncExecutor;
    private final ExecutionStrategy asyncExecutor;
    private final BatchExecutor batchExecutor;

    // Chain execution
    private final ChainExecutor chainExecutor;

    // Optimization components
    private final TaskPrioritizer taskPrioritizer;
    private final LoadBalancer loadBalancer;
    private final AdaptiveTiming adaptiveTiming;
    private final HotPathOptimizer hotPathOptimizer;
    private final TaskLocalityOptimizer localityOptimizer;
    private final PluginAwareBalancer pluginBalancer;
    private final TaskCheckpointing taskCheckpointing;
    private final PredictiveScheduler predictiveScheduler;
    private final TaskCoalescer taskCoalescer;

    // Metrics components
    private final SchedulerMetrics schedulerMetrics;
    private final TaskTracker taskTracker;
    private final MetricsExporter metricsExporter;

    // Object pools
    private final ObjectPool<Runnable> runnablePool;

    /**
     * Creates a new scheduler instance.
     *
     * @param plugin The owning plugin
     */
    private Scheduler(Plugin plugin) {
        this.plugin = plugin;

        // Initialize core components
        this.threadPoolManager = new ThreadPoolManager();
        this.tpsMonitor = new TPSMonitor(plugin);
        this.memoryMonitor = new MemoryMonitor(threadPoolManager.getScheduledPool());
        this.threadPoolOrchestrator = new ThreadPoolOrchestrator(plugin, threadPoolManager, tpsMonitor, memoryMonitor);

        // Initialize execution strategies
        this.syncExecutor = new SyncExecutor(plugin);
        this.asyncExecutor = new AsyncExecutor(plugin, threadPoolManager);
        this.batchExecutor = new BatchExecutor(plugin, threadPoolManager);

        // Initialize task management
        this.taskManager = new TaskManager(plugin);

        // Initialize chain execution
        this.chainExecutor = new ChainExecutor(plugin, taskManager, tpsMonitor);

        // Initialize optimization components
        this.taskPrioritizer = new TaskPrioritizer(tpsMonitor);
        this.loadBalancer = new LoadBalancer(threadPoolManager, tpsMonitor);
        this.adaptiveTiming = new AdaptiveTiming(tpsMonitor, memoryMonitor);
        this.hotPathOptimizer = new HotPathOptimizer();
        this.localityOptimizer = new TaskLocalityOptimizer();
        this.pluginBalancer = new PluginAwareBalancer();
        this.taskCheckpointing = new TaskCheckpointing();
        this.predictiveScheduler = new PredictiveScheduler(plugin, tpsMonitor);
        this.taskCoalescer = new TaskCoalescer(this::executeBatchTasks);

        // Initialize metrics components
        this.schedulerMetrics = new SchedulerMetrics(plugin);
        this.taskTracker = new TaskTracker();
        this.metricsExporter = new MetricsExporter(plugin, schedulerMetrics, taskTracker);

        // Create object pools
        this.runnablePool = new ObjectPool<>(
                () -> () -> {}, // Empty runnable factory
                r -> {}, // No recycling needed
                10, 100);

        // Start all components
        initialize();
    }

    /**
     * Gets or creates the scheduler instance.
     *
     * @param plugin The plugin instance
     * @return The scheduler instance
     */
    public static synchronized Scheduler get(Plugin plugin) {
        if (instance == null) {
            instance = new Scheduler(plugin);
        }
        return instance;
    }

    /**
     * Initializes the scheduler subsystems.
     */
    private void initialize() {
        Debug.log(Level.INFO, "Initializing Scheduler for " + plugin.getName());

        // Start monitoring systems
        tpsMonitor.start();
        memoryMonitor.start();
        batchExecutor.start();

        // Enable debug mode if plugin config specifies
        if (plugin.getConfig().getBoolean("scheduler.debug", false)) {
            Debug.setDebugMode(true);
            Debug.log(Level.INFO, "Debug mode enabled from config");
        }

        // Schedule periodic maintenance tasks
        scheduleMaintenanceTasks();
    }

    /**
     * Schedules internal maintenance tasks.
     */
    private void scheduleMaintenanceTasks() {
        // Update adaptive timing every 30 seconds
        runTimer(() -> adaptiveTiming.updateScaleFactor(), 30 * 20, 30 * 20);

        // Clean up task tracker records every 5 minutes
        runTimer(() -> taskTracker.cleanupOldRecords(), 5 * 60 * 20, 5 * 60 * 20);

        // Clean up load balancer resources every 10 minutes
        runTimer(() -> loadBalancer.cleanupResources(), 10 * 60 * 20, 10 * 60 * 20);

        // Adjust thread pool sizes dynamically every minute
        runTimer(() -> threadPoolOrchestrator.adjustPoolSizes(), 60 * 20, 60 * 20);

        // Balance plugin quotas every 30 seconds
        runTimer(() -> pluginBalancer.balanceQuotas(), 30 * 20, 30 * 20);

        // Clean up stale locality groups every 2 minutes
        runTimer(() -> localityOptimizer.cleanupStaleGroups(), 2 * 60 * 20, 2 * 60 * 20);

        // Record server load for predictive scheduling every minute
        runTimer(() -> predictiveScheduler.recordCurrentLoad(), 20, 60 * 20);

        // Schedule metrics export if enabled
        if (plugin.getConfig().getBoolean("scheduler.metrics.export", false)) {
            String exportPath = plugin.getConfig().getString("scheduler.metrics.path", "metrics");
            int intervalMinutes = plugin.getConfig().getInt("scheduler.metrics.interval", 60);
            metricsExporter.schedulePeriodicExport(intervalMinutes, new java.io.File(plugin.getDataFolder(), exportPath));
        }
    }

    /**
     * Runs a task synchronously on the main server thread.
     *
     * @param task The task to run
     * @return A cancellable task handle
     */
    public Task runSync(Runnable task) {
        long startTime = System.nanoTime();
        try {
            // Check plugin quota
            String pluginName = plugin.getName();
            if (!pluginBalancer.canScheduleTask(pluginName)) {
                Debug.log(Level.WARNING, "Plugin " + pluginName + " exceeded task quota, delaying task");
                return runLater(task, 20); // Delay by 1 second
            }

            pluginBalancer.registerTaskStart(pluginName);

            // Track metrics
            Consumer<Long> completion = schedulerMetrics.taskStarted("sync");

            // Execute the task
            Task scheduledTask = taskManager.runSync(() -> {
                long execStartTime = System.nanoTime();
                try {
                    task.run();
                    long execTime = System.nanoTime() - execStartTime;
                    if (completion != null) {
                        completion.accept(execTime);
                    }

                    // Record execution for hot path optimization
                    hotPathOptimizer.recordExecution("sync", execTime);

                    // Register task completion with plugin balancer
                    pluginBalancer.registerTaskComplete(pluginName, execTime);
                } catch (Throwable t) {
                    schedulerMetrics.recordTaskFailed("sync", t);
                    pluginBalancer.registerTaskFailure(pluginName);
                    throw t;
                }
            });

            // Track the task for detailed metrics
            taskTracker.trackTask(scheduledTask, "sync", "Synchronous task");

            return scheduledTask;
        } catch (Exception e) {
            Debug.log(Level.SEVERE, "Error in runSync: " + e.getMessage());
            schedulerMetrics.recordTaskFailed("sync", e);
            return null;
        } finally {
            long overhead = System.nanoTime() - startTime;
            if (Debug.isDebugEnabled() && overhead > 1_000_000) { // Log if overhead > 1ms
                Debug.debug("Scheduling overhead for runSync: " + (overhead / 1_000_000.0) + "ms");
            }
        }
    }

    /**
     * Runs a task asynchronously off the main server thread.
     *
     * @param task The task to run
     * @return A cancellable task handle
     */
    public Task runAsync(Runnable task) {
        return runAsync(task, false, false);
    }

    /**
     * Runs a task asynchronously with specific characteristics.
     *
     * @param task The task to run
     * @param isCpuIntensive Whether the task is CPU-intensive
     * @param isIoIntensive Whether the task is I/O-intensive
     * @return A cancellable task handle
     */
    public Task runAsync(Runnable task, boolean isCpuIntensive, boolean isIoIntensive) {
        long startTime = System.nanoTime();
        try {
            // Check plugin quota
            String pluginName = plugin.getName();
            if (!pluginBalancer.canScheduleTask(pluginName)) {
                Debug.log(Level.WARNING, "Plugin " + pluginName + " exceeded task quota, delaying task");
                return runLaterAsync(task, 20); // Delay by 1 second
            }

            pluginBalancer.registerTaskStart(pluginName);

            // Track metrics
            Consumer<Long> completion = schedulerMetrics.taskStarted("async");

            // Get appropriate executor based on task characteristics
            ExecutorService executor = threadPoolOrchestrator.getOptimalExecutor(
                    isCpuIntensive, isIoIntensive, false);

            // Check for hot path optimization
            String taskSignature = HotPathOptimizer.createTaskSignature("async",
                    (isCpuIntensive ? "cpu" : "") + (isIoIntensive ? "io" : ""));

            if (hotPathOptimizer.isHotPath(taskSignature)) {
                // This is a hot path, use optimized execution
                HotPathOptimizer.ExecutionMode mode = hotPathOptimizer.getRecommendedMode(taskSignature);

                if (mode == HotPathOptimizer.ExecutionMode.BATCH) {
                    // Execute as part of a batch
                    return runBatch(task, "hot-" + taskSignature);
                }
            }

            // Execute the task
            Task scheduledTask = taskManager.runAsync(() -> {
                long execStartTime = System.nanoTime();
                try {
                    task.run();
                    long execTime = System.nanoTime() - execStartTime;
                    if (completion != null) {
                        completion.accept(execTime);
                    }

                    // Record execution for hot path optimization
                    hotPathOptimizer.recordExecution(taskSignature, execTime);

                    // Register task completion with plugin balancer
                    pluginBalancer.registerTaskComplete(pluginName, execTime);
                } catch (Throwable t) {
                    schedulerMetrics.recordTaskFailed("async", t);
                    pluginBalancer.registerTaskFailure(pluginName);
                    throw t;
                }
            });

            // Track the task for detailed metrics
            String category = isCpuIntensive ? "async-cpu" : (isIoIntensive ? "async-io" : "async");
            taskTracker.trackTask(scheduledTask, category, "Asynchronous task");

            return scheduledTask;
        } catch (Exception e) {
            Debug.log(Level.SEVERE, "Error in runAsync: " + e.getMessage());
            schedulerMetrics.recordTaskFailed("async", e);
            return null;
        } finally {
            long overhead = System.nanoTime() - startTime;
            if (Debug.isDebugEnabled() && overhead > 1_000_000) { // Log if overhead > 1ms
                Debug.debug("Scheduling overhead for runAsync: " + (overhead / 1_000_000.0) + "ms");
            }
        }
    }

    /**
     * Schedules a task to run after a delay.
     *
     * @param task The task to run
     * @param delayTicks Delay in server ticks
     * @return A cancellable task handle
     */
    public Task runLater(Runnable task, long delayTicks) {
        try {
            // Check if we should adjust delay based on server load
            long adjustedDelay = adaptiveTiming.getAdjustedDelay(delayTicks, "later");

            if (adjustedDelay != delayTicks && Debug.isDebugEnabled()) {
                Debug.debug("Adjusted delay from " + delayTicks + " to " + adjustedDelay + " ticks");
            }

            // Check for predictive scheduling opportunities
            String taskType = "runLater";
            int recommendedDelay = predictiveScheduler.recommendForTaskType(taskType, 0.5);
            if (recommendedDelay > 0 && recommendedDelay * 20 > adjustedDelay) {
                // Use the predicted optimal time if it's later than the requested time
                adjustedDelay = recommendedDelay * 20;
                Debug.debug("Using predictive scheduling: adjusted delay to " + adjustedDelay + " ticks");
            }

            // Register with plugin balancer (deferred until execution)
            String pluginName = plugin.getName();

            // Track metrics
            Consumer<Long> completion = schedulerMetrics.taskStarted("later");

            // Execute the task
            Task scheduledTask = taskManager.runLater(() -> {
                // Check plugin quota right before execution
                if (!pluginBalancer.canScheduleTask(pluginName)) {
                    // Try again later
                    runLater(task, 20);
                    return;
                }

                pluginBalancer.registerTaskStart(pluginName);

                long startTime = System.nanoTime();
                try {
                    task.run();
                    long execTime = System.nanoTime() - startTime;
                    if (completion != null) {
                        completion.accept(execTime);
                    }

                    // Record execution for future prediction
                    predictiveScheduler.recordTaskExecution(taskType, execTime);

                    // Register task completion with plugin balancer
                    pluginBalancer.registerTaskComplete(pluginName, execTime);
                } catch (Throwable t) {
                    schedulerMetrics.recordTaskFailed("later", t);
                    pluginBalancer.registerTaskFailure(pluginName);
                    throw t;
                }
            }, adjustedDelay);

            // Track the task
            taskTracker.trackTask(scheduledTask, "later", "Delayed task");

            return scheduledTask;
        } catch (Exception e) {
            Debug.log(Level.SEVERE, "Error in runLater: " + e.getMessage());
            schedulerMetrics.recordTaskFailed("later", e);
            return null;
        }
    }

    /**
     * Schedules a task to run asynchronously after a delay.
     *
     * @param task The task to run
     * @param delayTicks Delay in server ticks
     * @return A cancellable task handle
     */
    public Task runLaterAsync(Runnable task, long delayTicks) {
        try {
            // Check if we should adjust delay based on server load
            long adjustedDelay = adaptiveTiming.getAdjustedDelay(delayTicks, "later-async");

            // Register with plugin balancer (deferred until execution)
            String pluginName = plugin.getName();

            // Track metrics
            Consumer<Long> completion = schedulerMetrics.taskStarted("later-async");

            // Execute the task
            Task scheduledTask = taskManager.runLaterAsync(() -> {
                // Check plugin quota right before execution
                if (!pluginBalancer.canScheduleTask(pluginName)) {
                    // Try again later
                    runLaterAsync(task, 20);
                    return;
                }

                pluginBalancer.registerTaskStart(pluginName);

                long startTime = System.nanoTime();
                try {
                    task.run();
                    long execTime = System.nanoTime() - startTime;
                    if (completion != null) {
                        completion.accept(execTime);
                    }

                    // Register task completion with plugin balancer
                    pluginBalancer.registerTaskComplete(pluginName, execTime);
                } catch (Throwable t) {
                    schedulerMetrics.recordTaskFailed("later-async", t);
                    pluginBalancer.registerTaskFailure(pluginName);
                    throw t;
                }
            }, adjustedDelay);

            // Track the task
            taskTracker.trackTask(scheduledTask, "later-async", "Delayed async task");

            return scheduledTask;
        } catch (Exception e) {
            Debug.log(Level.SEVERE, "Error in runLaterAsync: " + e.getMessage());
            schedulerMetrics.recordTaskFailed("later-async", e);
            return null;
        }
    }

    /**
     * Schedules a task to run repeatedly.
     *
     * @param task The task to run
     * @param delayTicks Initial delay in server ticks
     * @param periodTicks Period between executions in server ticks
     * @return A cancellable task handle
     */
    public Task runTimer(Runnable task, long delayTicks, long periodTicks) {
        try {
            // Check if we should adjust timing based on server load
            long adjustedDelay = adaptiveTiming.getAdjustedDelay(delayTicks, "timer");
            long adjustedPeriod = adaptiveTiming.getAdjustedPeriod(periodTicks, "timer");

            // Register with plugin balancer
            String pluginName = plugin.getName();

            // Track metrics
            String category = "timer";

            // Execute the task
            Task scheduledTask = taskManager.runTimer(() -> {
                // Check plugin quota
                if (!pluginBalancer.canScheduleTask(pluginName)) {
                    // Skip this execution but don't cancel
                    Debug.debug("Skipping timer execution due to plugin quota");
                    return;
                }

                pluginBalancer.registerTaskStart(pluginName);

                Consumer<Long> completion = schedulerMetrics.taskStarted(category);
                long startTime = System.nanoTime();
                try {
                    task.run();
                    long execTime = System.nanoTime() - startTime;
                    if (completion != null) {
                        completion.accept(execTime);
                    }

                    // Record timing for future adjustments
                    adaptiveTiming.recordExecutionTime(category, execTime);

                    // Register task completion with plugin balancer
                    pluginBalancer.registerTaskComplete(pluginName, execTime);
                } catch (Throwable t) {
                    schedulerMetrics.recordTaskFailed(category, t);
                    pluginBalancer.registerTaskFailure(pluginName);
                    throw t;
                }
            }, adjustedDelay, adjustedPeriod);

            // Track the task
            taskTracker.trackTask(scheduledTask, category, "Timer task");

            return scheduledTask;
        } catch (Exception e) {
            Debug.log(Level.SEVERE, "Error in runTimer: " + e.getMessage());
            schedulerMetrics.recordTaskFailed("timer", e);
            return null;
        }
    }

    /**
     * Schedules a task to run asynchronously and repeatedly.
     *
     * @param task The task to run
     * @param delayTicks Initial delay in server ticks
     * @param periodTicks Period between executions in server ticks
     * @return A cancellable task handle
     */
    public Task runTimerAsync(Runnable task, long delayTicks, long periodTicks) {
        try {
            // Check if we should adjust timing based on server load
            long adjustedDelay = adaptiveTiming.getAdjustedDelay(delayTicks, "timer-async");
            long adjustedPeriod = adaptiveTiming.getAdjustedPeriod(periodTicks, "timer-async");

            // Register with plugin balancer
            String pluginName = plugin.getName();

            // Track metrics
            String category = "timer-async";

            // Execute the task
            Task scheduledTask = taskManager.runTimerAsync(() -> {
                // Check plugin quota
                if (!pluginBalancer.canScheduleTask(pluginName)) {
                    // Skip this execution but don't cancel
                    Debug.debug("Skipping async timer execution due to plugin quota");
                    return;
                }

                pluginBalancer.registerTaskStart(pluginName);

                Consumer<Long> completion = schedulerMetrics.taskStarted(category);
                long startTime = System.nanoTime();
                try {
                    task.run();
                    long execTime = System.nanoTime() - startTime;
                    if (completion != null) {
                        completion.accept(execTime);
                    }

                    // Record timing for future adjustments
                    adaptiveTiming.recordExecutionTime(category, execTime);

                    // Register task completion with plugin balancer
                    pluginBalancer.registerTaskComplete(pluginName, execTime);
                } catch (Throwable t) {
                    schedulerMetrics.recordTaskFailed(category, t);
                    pluginBalancer.registerTaskFailure(pluginName);
                    throw t;
                }
            }, adjustedDelay, adjustedPeriod);

            // Track the task
            taskTracker.trackTask(scheduledTask, category, "Async timer task");

            return scheduledTask;
        } catch (Exception e) {
            Debug.log(Level.SEVERE, "Error in runTimerAsync: " + e.getMessage());
            schedulerMetrics.recordTaskFailed("timer-async", e);
            return null;
        }
    }

    /**
     * Runs a task in a batch with other similar tasks for efficiency.
     *
     * @param task The task to run
     * @param batchName The name of the batch
     * @return A cancellable task handle
     */
    public Task runBatch(Runnable task, String batchName) {
        try {
            // Register with plugin balancer
            String pluginName = plugin.getName();

            // Check plugin quota
            if (!pluginBalancer.canScheduleTask(pluginName)) {
                Debug.log(Level.WARNING, "Plugin " + pluginName + " exceeded task quota, delaying batch task");
                return runLaterAsync(() -> runBatch(task, batchName), 20); // Delay by 1 second
            }

            pluginBalancer.registerTaskStart(pluginName);

            // Track metrics
            Consumer<Long> completion = schedulerMetrics.taskStarted("batch");

            // Create a wrapped task with metrics
            Runnable wrappedTask = () -> {
                long startTime = System.nanoTime();
                try {
                    task.run();
                    long execTime = System.nanoTime() - startTime;
                    if (completion != null) {
                        completion.accept(execTime);
                    }

                    // Register task completion with plugin balancer
                    pluginBalancer.registerTaskComplete(pluginName, execTime);
                } catch (Throwable t) {
                    schedulerMetrics.recordTaskFailed("batch", t);
                    pluginBalancer.registerTaskFailure(pluginName);
                    throw t;
                }
            };

            // Execute in batch
            Task scheduledTask = batchExecutor.executeInBatch(wrappedTask,
                    taskManager.generateTaskId(), batchName);

            // Track the task
            taskTracker.trackTask(scheduledTask, "batch", "Batch task: " + batchName);

            return scheduledTask;
        } catch (Exception e) {
            Debug.log(Level.SEVERE, "Error in runBatch: " + e.getMessage());
            schedulerMetrics.recordTaskFailed("batch", e);
            return null;
        }
    }

    /**
     * Executes a list of tasks as a batch.
     * This is used by the task coalescer.
     *
     * @param tasks The list of tasks to execute
     */
    private void executeBatchTasks(List<Runnable> tasks) {
        if (tasks.isEmpty()) {
            return;
        }

        runAsync(() -> {
            for (Runnable task : tasks) {
                try {
                    task.run();
                } catch (Exception e) {
                    Debug.log(Level.SEVERE, "Error executing coalesced task: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Runs a task with a specific priority.
     *
     * @param task The task to run
     * @param priority The task priority
     * @return A cancellable task handle
     */
    public Task runPrioritized(Runnable task, Priority priority) {
        try {
            // Register with plugin balancer
            String pluginName = plugin.getName();

            // Check plugin quota - only apply to lower priorities
            if (priority != Priority.CRITICAL && !pluginBalancer.canScheduleTask(pluginName)) {
                Debug.log(Level.WARNING, "Plugin " + pluginName + " exceeded task quota, reducing priority");
                // Run with reduced priority instead of delaying
                priority = getPriorityBelow(priority);
            }

            pluginBalancer.registerTaskStart(pluginName);

            // Track metrics
            Consumer<Long> completion = schedulerMetrics.taskStarted("prioritized");

            // Run on appropriate thread based on priority
            Task scheduledTask;
            if (priority == Priority.CRITICAL) {
                // Critical tasks run synchronously for immediate execution
                scheduledTask = runSync(() -> {
                    long startTime = System.nanoTime();
                    try {
                        task.run();
                        long execTime = System.nanoTime() - startTime;
                        if (completion != null) {
                            completion.accept(execTime);
                        }

                        // Register task completion with plugin balancer
                        pluginBalancer.registerTaskComplete(pluginName, execTime);
                    } catch (Throwable t) {
                        schedulerMetrics.recordTaskFailed("prioritized", t);
                        pluginBalancer.registerTaskFailure(pluginName);
                        throw t;
                    }
                });
            } else {
                // Other priorities go through the prioritizer
                scheduledTask = taskManager.runAsync(() -> {
                    long startTime = System.nanoTime();
                    try {
                        task.run();
                        long execTime = System.nanoTime() - startTime;
                        if (completion != null) {
                            completion.accept(execTime);
                        }

                        // Register task completion with plugin balancer
                        pluginBalancer.registerTaskComplete(pluginName, execTime);
                    } catch (Throwable t) {
                        schedulerMetrics.recordTaskFailed("prioritized", t);
                        pluginBalancer.registerTaskFailure(pluginName);
                        throw t;
                    }
                });

                // Add to prioritizer
                taskPrioritizer.addTask(scheduledTask, priority);
            }

            // Track the task
            taskTracker.trackTask(scheduledTask, "prioritized", "Priority: " + priority);

            return scheduledTask;
        } catch (Exception e) {
            Debug.log(Level.SEVERE, "Error in runPrioritized: " + e.getMessage());
            schedulerMetrics.recordTaskFailed("prioritized", e);
            return null;
        }
    }

    /**
     * Gets the next lower priority level.
     *
     * @param priority The current priority
     * @return The next lower priority, or LOWEST if already at the bottom
     */
    private Priority getPriorityBelow(Priority priority) {
        switch (priority) {
            case CRITICAL:
                return Priority.HIGH;
            case HIGH:
                return Priority.NORMAL;
            case NORMAL:
                return Priority.LOW;
            case LOW:
            case LOWEST:
            default:
                return Priority.LOWEST;
        }
    }

    /**
     * Coalesces small similar tasks to reduce overhead.
     *
     * @param task The task to run
     * @param taskType The type of task for coalescing
     * @return A task representing the coalesced operation
     */
    public Task runCoalesced(Runnable task, String taskType) {
        return taskCoalescer.scheduleTask(task, taskType);
    }

    /**
     * Creates a new task chain for sequential execution.
     *
     * @return A new task chain builder
     */
    public Chain.Builder chain() {
        return new ChainBuilder(plugin, taskManager);
    }

    /**
     * Executes a chain and returns a task representing the chain.
     *
     * @param chain The chain to execute
     * @return A task representing the chain execution
     */
    public Task executeChain(Chain chain) {
        return chainExecutor.execute(chain);
    }

    /**
     * Creates a conditional task that only executes when the condition is met.
     *
     * @param condition The condition to check
     * @param task The task to run when condition is true
     * @return A cancellable task handle
     */
    public Task runWhen(Supplier<Boolean> condition, Runnable task) {
        return runWhen(condition, task, 20); // Check every second by default
    }

    /**
     * Creates a conditional task that only executes when the condition is met.
     *
     * @param condition The condition to check
     * @param task The task to run when condition is true
     * @param checkIntervalTicks How often to check the condition, in ticks
     * @return A cancellable task handle
     */
    public Task runWhen(Supplier<Boolean> condition, Runnable task, long checkIntervalTicks) {
        try {
            // Adjust check interval based on server load
            long adjustedInterval = adaptiveTiming.getAdjustedPeriod(checkIntervalTicks, "conditional");

            // Register with plugin balancer
            String pluginName = plugin.getName();

            // Track metrics
            String category = "conditional";

            // Create a repeating task that checks the condition
            Task scheduledTask = runTimer(() -> {
                try {
                    if (condition.get()) {
                        // Check plugin quota before actual execution
                        if (!pluginBalancer.canScheduleTask(pluginName)) {
                            // Skip this execution
                            return;
                        }

                        pluginBalancer.registerTaskStart(pluginName);
                        Consumer<Long> completion = schedulerMetrics.taskStarted(category);
                        long startTime = System.nanoTime();

                        try {
                            task.run();

                            long execTime = System.nanoTime() - startTime;
                            if (completion != null) {
                                completion.accept(execTime);
                            }

                            // Register task completion with plugin balancer
                            pluginBalancer.registerTaskComplete(pluginName, execTime);
                        } catch (Throwable t) {
                            schedulerMetrics.recordTaskFailed(category, t);
                            pluginBalancer.registerTaskFailure(pluginName);
                            throw t;
                        }
                    }
                } catch (Exception e) {
                    Debug.log(Level.WARNING, "Error checking condition: " + e.getMessage());
                }
            }, adjustedInterval, adjustedInterval);

            // Track the task
            taskTracker.trackTask(scheduledTask, category, "Conditional task");

            return scheduledTask;
        } catch (Exception e) {
            Debug.log(Level.SEVERE, "Error in runWhen: " + e.getMessage());
            schedulerMetrics.recordTaskFailed("conditional", e);
            return null;
        }
    }

    /**
     * Schedules a task for resource-efficient execution.
     * This method will intelligently schedule the task based on server conditions.
     *
     * @param task The task to run
     * @return A cancellable task handle
     */
    public Task runEfficient(Runnable task) {
        try {
            // Check server conditions
            if (tpsMonitor != null && tpsMonitor.isLagging(16.0)) {
                // Server is experiencing lag, delay execution
                Debug.debug("Server is lagging, scheduling efficient task with delay");
                return runLaterAsync(task, 20); // Wait 1 second
            } else if (memoryMonitor != null && memoryMonitor.isMemoryHigh()) {
                // Memory usage is high, run during low-memory periods
                Debug.debug("Memory usage is high, scheduling efficient task conditionally");
                return runWhen(() -> !memoryMonitor.isMemoryHigh(), task, 40);
            } else {
                // Server is healthy, run immediately but async
                return runAsync(task);
            }
        } catch (Exception e) {
            Debug.log(Level.SEVERE, "Error in runEfficient: " + e.getMessage());
            return null;
        }
    }

    /**
     * Runs a task with a resource lock to prevent resource overloading.
     *
     * @param task The task to run
     * @param resourceName The name of the resource to lock
     * @return A cancellable task handle
     */
    public Task runWithResource(Runnable task, String resourceName) {
        try {
            // Register with plugin balancer
            String pluginName = plugin.getName();

            // Track metrics
            Consumer<Long> completion = schedulerMetrics.taskStarted("resource");

            // Create a condition that checks resource availability
            Supplier<Boolean> resourceAvailable = () -> loadBalancer.canUseResource(resourceName);

            // Create the wrapped task
            Runnable resourceTask = () -> {
                // Check plugin quota
                if (!pluginBalancer.canScheduleTask(pluginName)) {
                    // Skip and try again later
                    return;
                }

                pluginBalancer.registerTaskStart(pluginName);

                // Acquire resource
                loadBalancer.startUsingResource(resourceName);

                long startTime = System.nanoTime();
                try {
                    task.run();

                    long execTime = System.nanoTime() - startTime;
                    if (completion != null) {
                        completion.accept(execTime);
                    }

                    // Track resource usage time
                    loadBalancer.finishUsingResource(resourceName, execTime);

                    // Register task completion with plugin balancer
                    pluginBalancer.registerTaskComplete(pluginName, execTime);
                } catch (Throwable t) {
                    schedulerMetrics.recordTaskFailed("resource", t);
                    loadBalancer.finishUsingResource(resourceName, System.nanoTime() - startTime);
                    pluginBalancer.registerTaskFailure(pluginName);
                    throw t;
                }
            };

            // Run when resource is available
            Task scheduledTask = runWhen(resourceAvailable, resourceTask, 5);

            // Track the task
            taskTracker.trackTask(scheduledTask, "resource", "Resource: " + resourceName);

            return scheduledTask;
        } catch (Exception e) {
            Debug.log(Level.SEVERE, "Error in runWithResource: " + e.getMessage());
            schedulerMetrics.recordTaskFailed("resource", e);
            return null;
        }
    }

    /**
     * Runs a task with data locality awareness for better cache efficiency.
     *
     * @param task The task to run
     * @param localityKey A key identifying the data region this task operates on
     * @return A cancellable task handle
     */
    public Task runWithLocality(Runnable task, String localityKey) {
        // Use the task locality optimizer to improve cache efficiency
        return localityOptimizer.scheduleLocalityAware(task, localityKey);
    }

    /**
     * Creates a checkpointable task that can be resumed if interrupted.
     *
     * @param <T> The state type
     * @param taskId A unique ID for the task
     * @param initialState The initial state, or null to use last checkpoint
     * @param processor Function to process the state and return a new state
     * @return A runnable that will run the checkpointable task
     */
    public <T> Runnable createCheckpointableTask(
            long taskId,
            Supplier<T> initialState,
            Function<T, T> processor) {

        return taskCheckpointing.createCheckpointableTask(taskId, initialState, processor);
    }

    /**
     * Runs a task after a real-time delay (not tick-based).
     *
     * @param task The task to run
     * @param delay The delay amount
     * @param unit The time unit
     * @return A cancellable task handle
     */
    public Task runAfter(Runnable task, long delay, TimeUnit unit) {
        // Convert real time to ticks
        long ticks = adaptiveTiming.toAdjustedTicks(delay, unit);
        return runLater(task, ticks);
    }

    /**
     * Runs a task at a specific time of day in the Minecraft world.
     *
     * @param task The task to run
     * @param worldName The name of the world
     * @param timeTick The time tick (0-24000)
     * @return A cancellable task handle
     */
    public Task runAtTime(Runnable task, String worldName, int timeTick) {
        Condition timeCondition = TimeCondition.worldTimeBetween(worldName, timeTick, timeTick + 10);
        return runWhen(timeCondition, task, 10);
    }

    /**
     * Runs a task only when server TPS is above a threshold.
     *
     * @param task The task to run
     * @param minTps The minimum TPS required
     * @return A cancellable task handle
     */
    public Task runWhenHealthy(Runnable task, double minTps) {
        Condition tpsCondition = ServerCondition.tpsAbove(minTps, tpsMonitor);
        return runWhen(tpsCondition, task, 20);
    }

    /**
     * Cancels all tasks owned by this scheduler.
     */
    public void cancelAllTasks() {
        taskManager.cancelAllTasks();
        chainExecutor.cancelAllChains();
        taskCoalescer.flushAllTasks(); // Execute any pending coalesced tasks
    }

    /**
     * Gets the metrics collector for this scheduler.
     *
     * @return The scheduler metrics
     */
    public SchedulerMetrics getMetrics() {
        return schedulerMetrics;
    }

    /**
     * Gets the task tracker for this scheduler.
     *
     * @return The task tracker
     */
    public TaskTracker getTaskTracker() {
        return taskTracker;
    }

    /**
     * Gets the metrics exporter for this scheduler.
     *
     * @return The metrics exporter
     */
    public MetricsExporter getMetricsExporter() {
        return metricsExporter;
    }

    /**
     * Gets plugin resource utilization statistics.
     *
     * @return A map of plugin statistics
     */
    public Map<String, Map<String, Object>> getPluginStats() {
        return pluginBalancer.getPluginStatistics();
    }

    /**
     * Displays metrics in-game to a command sender.
     *
     * @param sender The command sender to display metrics to
     */
    public void showMetrics(org.bukkit.command.CommandSender sender) {
        metricsExporter.displaySummary(sender);
    }

    /**
     * Shuts down the scheduler and releases resources.
     * Should be called when the plugin is disabled.
     */
    public void shutdown() {
        Debug.log(Level.INFO, "Shutting down Scheduler");

        // Execute all pending tasks with locality
        localityOptimizer.executeAllPending();

        // Execute any pending coalesced tasks
        taskCoalescer.flushAllTasks();

        // Cancel all tasks
        cancelAllTasks();

        // Shut down components
        taskManager.shutdown();
        tpsMonitor.stop();

        // Shut down thread pools
        threadPoolManager.shutdown();

        // Reset instance
        instance = null;
    }
}