package me.leon.scheduler.chain;

import java.util.function.Supplier;

/**
 * Represents a single link in a task chain.
 * Contains the information needed to execute a step in the chain.
 */
class ChainLink {

    /**
     * The type of link in the chain.
     */
    enum LinkType {
        SYNC,        // Synchronous task execution
        ASYNC,       // Asynchronous task execution
        DELAY,       // Delay before next link
        CONDITIONAL  // Conditional task execution
    }

    private final LinkType type;
    private final Runnable action;
    private final Supplier<Boolean> condition;
    private final long delayTicks;

    /**
     * Creates a new synchronous or asynchronous link.
     *
     * @param type The type of link (SYNC or ASYNC)
     * @param action The action to execute
     */
    ChainLink(LinkType type, Runnable action) {
        if (type != LinkType.SYNC && type != LinkType.ASYNC) {
            throw new IllegalArgumentException("This constructor only supports SYNC or ASYNC link types");
        }

        this.type = type;
        this.action = action;
        this.condition = null;
        this.delayTicks = 0;
    }

    /**
     * Creates a new delay link.
     *
     * @param delayTicks The delay in server ticks
     */
    ChainLink(long delayTicks) {
        this.type = LinkType.DELAY;
        this.action = null;
        this.condition = null;
        this.delayTicks = delayTicks;
    }

    /**
     * Creates a new conditional link.
     *
     * @param condition The condition to evaluate
     * @param action The action to execute if the condition is true
     */
    ChainLink(Supplier<Boolean> condition, Runnable action) {
        this.type = LinkType.CONDITIONAL;
        this.action = action;
        this.condition = condition;
        this.delayTicks = 0;
    }

    /**
     * Gets the type of this link.
     *
     * @return The link type
     */
    LinkType getType() {
        return type;
    }

    /**
     * Gets the action for this link.
     *
     * @return The action to execute
     */
    Runnable getAction() {
        return action;
    }

    /**
     * Gets the condition for this link.
     *
     * @return The condition to evaluate
     */
    Supplier<Boolean> getCondition() {
        return condition;
    }

    /**
     * Gets the delay for this link.
     *
     * @return The delay in server ticks
     */
    long getDelayTicks() {
        return delayTicks;
    }
}