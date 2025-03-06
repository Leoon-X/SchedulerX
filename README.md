# SchedulerX

A high-performance, feature-rich task scheduling library for Minecraft plugins that goes far beyond the capabilities of the standard Bukkit scheduler.

## ✨ Features

- **Advanced Task Scheduling**: Synchronous, asynchronous, delayed, repeating, and conditional task execution
- **Intelligent Optimization**:
  - Adaptive timing based on server TPS and memory usage
  - Dynamic task prioritization to handle high-load scenarios
  - Thread pool optimization with specialized pools for different workloads
  - Object pooling to reduce garbage collection pressure
  - Batch processing for similar tasks to minimize overhead
- **Task Chaining**: Fluent API for creating complex sequences of tasks with data passing
- **Resource Management**: Prevent resource overloading with automatic throttling
- **Comprehensive Metrics**: Detailed performance tracking with visualization

## 🚀 Why SchedulerX?

### Performance Focused
Regular Bukkit scheduler can cause performance issues during high-load scenarios. SchedulerX dynamically adjusts timing based on server conditions, ensuring your scheduled tasks don't tank your TPS.

### Resource Efficiency
SchedulerX intelligently distributes workloads across specialized thread pools and monitors resource usage to prevent overloading databases, file systems, or network connections.

### Developer Friendly
The fluent API makes even complex scheduling patterns readable and maintainable. No more callback hell or deeply nested tasks!

### Debugging Superpowers
Stop guessing which tasks are causing lag! The built-in metrics system tracks execution times, identifies slow tasks, and generates detailed reports to help you optimize your plugin.

## 💡 What Makes It Different?

| Feature | Bukkit Scheduler | SchedulerX |
|---------|-----------------|------------|
| Sync/Async Tasks | ✅ | ✅ |
| Delayed/Repeating Tasks | ✅ | ✅ |
| Task Chaining | ❌ | ✅ |
| Adaptive Timing | ❌ | ✅ |
| Task Prioritization | ❌ | ✅ |
| Resource Management | ❌ | ✅ |
| Batch Processing | ❌ | ✅ |
| Performance Metrics | ❌ | ✅ |
| Object Pooling | ❌ | ✅ |
| HTML/CSV Metrics Export | ❌ | ✅ |
| Dynamic Thread Pool Management | ❌ | ✅ |
| Hot Path Optimization | ❌ | ✅ |
| Task Locality Awareness | ❌ | ✅ |
| Task Checkpointing & Recovery | ❌ | ✅ |
| Plugin-Aware Resource Balancing | ❌ | ✅ |
| Predictive Scheduling | ❌ | ✅ |
| Task Coalescing | ❌ | ✅ |
| Plugin Hibernation Detection | ❌ | ✅ |
| Memory-Conscious Scheduling | ❌ | ✅ |
| Smart Task Cancellation | ❌ | ✅ |

## 📋 Usage

### Basic Scheduling

```java
// Get the scheduler instance
Scheduler scheduler = Scheduler.get(yourPlugin);

// Run a task on the main server thread
scheduler.runSync(() -> {
    player.sendMessage("Hello from the main thread!");
});

// Run a task asynchronously
scheduler.runAsync(() -> {
    // Database operations, HTTP requests, etc.
    loadDataFromDatabase();
});

// Run a task after delay (in ticks)
scheduler.runLater(() -> {
    player.sendMessage("5 seconds have passed!");
}, 100);

// Run a repeating task
scheduler.runTimer(() -> {
    updateScoreboard();
}, 20, 20); // 1 second delay, update every second

// Chain multiple tasks together
scheduler.chain()
    .async(() -> loadPlayerData(player))
    .sync(data -> updatePlayerInventory(player, data))
    .delay(20) // Wait 1 second
    .async(() -> savePlayerData(player))
    .execute();

// Run task only when condition is met
scheduler.runWhen(
    () -> player.isOnline() && !player.isDead(),
    () -> givePlayerReward(player)
);

// Run task only when server TPS is healthy
scheduler.runWhenHealthy(() -> {
    generateNewStructures();
}, 18.0); // Only run when TPS is above 18

// Run with resource lock to prevent overloading
scheduler.runWithResource(
    () -> executeDatabaseQuery(),
    "database"
);

// Run task with specific priority
scheduler.runPrioritized(
    () -> processPlayerMovement(),
    Priority.HIGH
);

// Run resource-intensive task in most efficient way
scheduler.runEfficient(() -> {
    // This will run when the server isn't lagging
    generateChunks();
});
```

### Advanced Features

```java
// Task Chaining
scheduler.chain()
    .async(() -> loadPlayerData(player))
    .sync(data -> updatePlayerInventory(player, data))
    .delay(20) // Wait 1 second
    .async(() -> savePlayerData(player))
    .execute();

// Conditional Execution
scheduler.runWhen(
    () -> player.isOnline() && !player.isDead(),
    () -> givePlayerReward(player)
);

// Resource-Aware Execution
scheduler.runWithResource(
    () -> executeDatabaseQuery(),
    "database"
);

// Efficient Task Scheduling
scheduler.runEfficient(() -> {
    // This will run when the server isn't lagging
    generateChunks();
});

// Prioritized Tasks
scheduler.runPrioritized(
    () -> processPlayerMovement(),
    Priority.HIGH
);

// Group tasks that operate on the same data
scheduler.runWithLocality(
    () -> processChunkData(chunk), 
    "chunk:" + chunk.getX() + "," + chunk.getZ()
);

// Combine similar small tasks to reduce overhead
scheduler.runCoalesced(
    () -> updatePlayerScore(player),
    "player-score-updates"
);

// Create resumable task that can be interrupted
Runnable checkpointableTask = scheduler.createCheckpointableTask(
    taskId,
    () -> initialState(),
    state -> processStateAndReturnNewState(state)
);
scheduler.runAsync(checkpointableTask);

// Complex tasks with multiple processing stages
Runnable multiStageTask = scheduler.taskCheckpointing.createMultiStageTask(
    taskId,
    TaskCheckpointing.createStageProcessor(() -> initialData(), (data, checkpoint) -> {
        // Stage 1: Process data
        DataResult result = processData(data);
        checkpoint.accept(result);
        return result;
    }),
    TaskCheckpointing.createStageProcessor(null, (result, checkpoint) -> {
        // Stage 2: Use the result from stage 1
        return finalizeResult(result);
    })
);
scheduler.runAsync(multiStageTask);
```

## 🔧 Installation

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>me.leon</groupId>
    <artifactId>scheduler</artifactId>
    <version>1.0.0</version>
    <scope>compile</scope>
</dependency>
```

Or include the JAR file in your plugin.

## 📊 Metrics

Access performance metrics:

```java
// In-game command
// /scheduler metrics

// Export HTML report
scheduler.getMetricsExporter().exportToHtml(new File("metrics.html"));

// Get programmatic access
SchedulerMetrics metrics = scheduler.getMetrics();
double avgExecutionTime = metrics.getAverageExecutionTimeNanos("database") / 1_000_000.0;
```

## 🔍 Why It's Special

SchedulerX was built from the ground up to address the limitations of traditional Minecraft schedulers. It's specifically designed to help server owners and plugin developers:

1. **Maintain server performance** even with many concurrent tasks
2. **Simplify complex operations** with the powerful chaining API
3. **Identify performance bottlenecks** with comprehensive metrics
4. **Optimize resource usage** with intelligent load balancing

Whether you're developing a small utility plugin or a complex gameplay system, SchedulerX provides the tools you need to build efficient, reliable scheduling logic.

---

⭐ Star this project if you find it useful! Contributions welcome.
