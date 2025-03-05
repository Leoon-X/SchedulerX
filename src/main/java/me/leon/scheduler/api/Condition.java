package me.leon.scheduler.api;

import java.util.function.Supplier;
import org.bukkit.entity.Player;

/**
 * Represents conditions that can be used for conditional task execution.
 * Provides factory methods for common condition types.
 */
public interface Condition extends Supplier<Boolean> {

    /**
     * Evaluates this condition.
     *
     * @return true if the condition is met, false otherwise
     */
    @Override
    Boolean get();

    /**
     * Creates a condition that is met when the server TPS is above a threshold.
     *
     * @param minTps The minimum TPS threshold
     * @return A condition that checks server TPS
     */
    static Condition tpsAbove(double minTps) {
        return () -> {
            // Implementation will be added
            return true; // Placeholder
        };
    }

    /**
     * Creates a condition that is met when the server has fewer than a max number of players.
     *
     * @param maxPlayers The maximum player threshold
     * @return A condition that checks player count
     */
    static Condition playerCountBelow(int maxPlayers) {
        return () -> {
            // Implementation will be added
            return true; // Placeholder
        };
    }

    /**
     * Creates a condition that is met when a specific player is online.
     *
     * @param playerName The name of the player
     * @return A condition that checks if the player is online
     */
    static Condition playerOnline(String playerName) {
        return () -> {
            // Implementation will be added
            return true; // Placeholder
        };
    }

    /**
     * Creates a condition that is met when the server memory usage is below a threshold.
     *
     * @param maxMemoryPercent The maximum memory usage percentage (0-100)
     * @return A condition that checks memory usage
     */
    static Condition memoryBelow(int maxMemoryPercent) {
        return () -> {
            // Implementation will be added
            return true; // Placeholder
        };
    }

    /**
     * Creates a condition that is met during a specific time range in the Minecraft world.
     *
     * @param worldName The name of the world
     * @param startTick The starting tick of the day (0-24000)
     * @param endTick The ending tick of the day (0-24000)
     * @return A condition that checks world time
     */
    static Condition worldTimeBetween(String worldName, int startTick, int endTick) {
        return () -> {
            // Implementation will be added
            return true; // Placeholder
        };
    }

    /**
     * Creates a condition that combines multiple conditions with AND logic.
     *
     * @param conditions The conditions to combine
     * @return A condition that is met when all given conditions are met
     */
    static Condition all(Condition... conditions) {
        return () -> {
            for (Condition condition : conditions) {
                if (!condition.get()) {
                    return false;
                }
            }
            return true;
        };
    }

    /**
     * Creates a condition that combines multiple conditions with OR logic.
     *
     * @param conditions The conditions to combine
     * @return A condition that is met when any of the given conditions is met
     */
    static Condition any(Condition... conditions) {
        return () -> {
            for (Condition condition : conditions) {
                if (condition.get()) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * Creates a condition that negates another condition.
     *
     * @param condition The condition to negate
     * @return A condition that is met when the given condition is not met
     */
    static Condition not(Condition condition) {
        return () -> !condition.get();
    }
}