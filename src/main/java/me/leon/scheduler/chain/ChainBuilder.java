package me.leon.scheduler.chain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.plugin.Plugin;

import me.leon.scheduler.api.Chain;
import me.leon.scheduler.core.TaskManager;
import me.leon.scheduler.util.TickUtil;

/**
 * Builder for creating task chains with a fluent API.
 * Implements the Chain.Builder interface.
 */
public class ChainBuilder implements Chain.Builder {

    private final Plugin plugin;
    private final TaskManager taskManager;
    private final List<ChainLink> links;

    /**
     * Creates a new chain builder.
     *
     * @param plugin The owning plugin
     * @param taskManager The task manager
     */
    public ChainBuilder(Plugin plugin, TaskManager taskManager) {
        this.plugin = plugin;
        this.taskManager = taskManager;
        this.links = new ArrayList<>();
    }

    @Override
    public Chain.Builder sync(Runnable task) {
        if (task != null) {
            links.add(new ChainLink(ChainLink.LinkType.SYNC, task));
        }
        return this;
    }

    @Override
    public Chain.Builder async(Runnable task) {
        if (task != null) {
            links.add(new ChainLink(ChainLink.LinkType.ASYNC, task));
        }
        return this;
    }

    @Override
    public Chain.Builder delay(long ticks) {
        if (ticks > 0) {
            links.add(new ChainLink(ticks));
        }
        return this;
    }

    @Override
    public Chain.Builder delay(long time, TimeUnit unit) {
        if (time > 0) {
            long millis = unit.toMillis(time);
            long ticks = TickUtil.millisecondsToTicks(millis);
            return delay(ticks);
        }
        return this;
    }

    @Override
    public Chain.Builder conditional(Supplier<Boolean> condition, Runnable task) {
        if (condition != null && task != null) {
            links.add(new ChainLink(condition, task));
        }
        return this;
    }

    @Override
    public Chain.Builder repeatUntil(Supplier<Boolean> condition, Runnable task, long delayBetween) {
        if (condition != null && task != null) {
            // Create a repeating task that checks the condition after each execution
            Runnable repeatingAction = new Runnable() {
                @Override
                public void run() {
                    // Run the task
                    task.run();

                    // If the condition is met, the chain will continue
                    // Otherwise, add this task again with a delay
                    if (!condition.get()) {
                        ChainBuilder.this.delay(delayBetween).sync(this);
                    }
                }
            };

            // Add the repeating action
            links.add(new ChainLink(ChainLink.LinkType.SYNC, repeatingAction));
        }

        return this;
    }

    @Override
    public <T> Chain.TypedBuilder<T> supply(Supplier<T> supplier) {
        return new TypedChainBuilder<>(supplier);
    }

    @Override
    public Chain build() {
        return new TaskChain(plugin, taskManager, links);
    }

    /**
     * Implementation of Chain.TypedBuilder for creating chains with data transformation.
     *
     * @param <T> The type of data being passed through the chain
     */
    private class TypedChainBuilder<T> implements Chain.TypedBuilder<T> {

        private final Supplier<T> supplier;

        /**
         * Creates a new typed chain builder.
         *
         * @param supplier The supplier of the value
         */
        public TypedChainBuilder(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        @Override
        public Chain.Builder consume(Consumer<T> consumer) {
            if (supplier != null && consumer != null) {
                // Add a task that supplies and consumes the value
                Runnable action = () -> {
                    T value = supplier.get();
                    consumer.accept(value);
                };

                links.add(new ChainLink(ChainLink.LinkType.SYNC, action));
            }

            return ChainBuilder.this;
        }

        @Override
        public <R> Chain.TypedBuilder<R> transform(Function<T, R> function) {
            if (supplier != null && function != null) {
                // Create a new supplier that transforms the value
                Supplier<R> transformedSupplier = () -> function.apply(supplier.get());

                return new TypedChainBuilder<>(transformedSupplier);
            }

            // If either is null, return a no-op builder
            return new TypedChainBuilder<>(null);
        }

        @Override
        public Chain.Builder filter(java.util.function.Predicate<T> predicate, Consumer<T> task) {
            if (supplier != null && predicate != null && task != null) {
                // Add a task that checks the predicate and conditionally executes
                Runnable action = () -> {
                    T value = supplier.get();
                    if (predicate.test(value)) {
                        task.accept(value);
                    }
                };

                links.add(new ChainLink(ChainLink.LinkType.SYNC, action));
            }

            return ChainBuilder.this;
        }
    }
}