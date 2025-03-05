package me.leon.scheduler.condition;

import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import me.leon.scheduler.api.Condition;

/**
 * Provides condition implementations related to player state.
 * These conditions check player-specific attributes and states.
 */
public final class PlayerCondition {

    private PlayerCondition() {
        // Private constructor to prevent instantiation
    }

    /**
     * Creates a condition that is met when a specific player is online.
     *
     * @param playerName The name of the player
     * @return A condition that checks if the player is online
     */
    public static Condition playerOnline(String playerName) {
        return () -> Bukkit.getPlayerExact(playerName) != null;
    }

    /**
     * Creates a condition that is met when a specific player is online.
     *
     * @param playerId The UUID of the player
     * @return A condition that checks if the player is online
     */
    public static Condition playerOnline(UUID playerId) {
        return () -> Bukkit.getPlayer(playerId) != null;
    }

    /**
     * Creates a condition that is met when a specific player has a permission.
     *
     * @param playerName The name of the player
     * @param permission The permission to check
     * @return A condition that checks if the player has the permission
     */
    public static Condition playerHasPermission(String playerName, String permission) {
        return () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            return player != null && player.hasPermission(permission);
        };
    }

    /**
     * Creates a condition that is met when a specific player is in a world.
     *
     * @param playerName The name of the player
     * @param worldName The name of the world
     * @return A condition that checks if the player is in the specified world
     */
    public static Condition playerInWorld(String playerName, String worldName) {
        return () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            return player != null && player.getWorld().getName().equals(worldName);
        };
    }

    /**
     * Creates a condition that is met when a specific player is within a radius of a location.
     *
     * @param playerName The name of the player
     * @param location The center location
     * @param radius The radius in blocks
     * @return A condition that checks if the player is within the radius
     */
    public static Condition playerNearLocation(String playerName, Location location, double radius) {
        return () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null || location == null || !player.getWorld().equals(location.getWorld())) {
                return false;
            }

            return player.getLocation().distance(location) <= radius;
        };
    }

    /**
     * Creates a condition that is met when a specific player is above a specified health threshold.
     *
     * @param playerName The name of the player
     * @param minHealth The minimum health threshold
     * @return A condition that checks if the player's health is above the threshold
     */
    public static Condition playerHealthAbove(String playerName, double minHealth) {
        return () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            return player != null && player.getHealth() >= minHealth;
        };
    }

    /**
     * Creates a condition that is met when a specific player is below a specified health threshold.
     *
     * @param playerName The name of the player
     * @param maxHealth The maximum health threshold
     * @return A condition that checks if the player's health is below the threshold
     */
    public static Condition playerHealthBelow(String playerName, double maxHealth) {
        return () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            return player != null && player.getHealth() <= maxHealth;
        };
    }

    /**
     * Creates a condition that is met when a specific player is above a specified Y-coordinate.
     *
     * @param playerName The name of the player
     * @param yCoord The Y-coordinate threshold
     * @return A condition that checks if the player is above the Y-coordinate
     */
    public static Condition playerAboveY(String playerName, double yCoord) {
        return () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            return player != null && player.getLocation().getY() > yCoord;
        };
    }

    /**
     * Creates a condition that is met when a specific player is below a specified Y-coordinate.
     *
     * @param playerName The name of the player
     * @param yCoord The Y-coordinate threshold
     * @return A condition that checks if the player is below the Y-coordinate
     */
    public static Condition playerBelowY(String playerName, double yCoord) {
        return () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            return player != null && player.getLocation().getY() < yCoord;
        };
    }

    /**
     * Creates a condition that is met when a specific player has at least the specified level.
     *
     * @param playerName The name of the player
     * @param minLevel The minimum level threshold
     * @return A condition that checks if the player's level is at least the threshold
     */
    public static Condition playerLevelAtLeast(String playerName, int minLevel) {
        return () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            return player != null && player.getLevel() >= minLevel;
        };
    }

    /**
     * Creates a condition that is met when a specific player is flying.
     *
     * @param playerName The name of the player
     * @return A condition that checks if the player is flying
     */
    public static Condition playerIsFlying(String playerName) {
        return () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            return player != null && player.isFlying();
        };
    }

    /**
     * Creates a condition that is met when a specific player is in a vehicle.
     *
     * @param playerName The name of the player
     * @return A condition that checks if the player is in a vehicle
     */
    public static Condition playerInVehicle(String playerName) {
        return () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            return player != null && player.isInsideVehicle();
        };
    }

    /**
     * Creates a condition that is met when a specific player is sprinting.
     *
     * @param playerName The name of the player
     * @return A condition that checks if the player is sprinting
     */
    public static Condition playerIsSprinting(String playerName) {
        return () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            return player != null && player.isSprinting();
        };
    }

    /**
     * Creates a condition that is met when a specific player is in the specified gamemode.
     *
     * @param playerName The name of the player
     * @param gameModeName The name of the gamemode (survival, creative, adventure, spectator)
     * @return A condition that checks if the player is in the specified gamemode
     */
    public static Condition playerInGameMode(String playerName, String gameModeName) {
        return () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null) {
                return false;
            }

            try {
                org.bukkit.GameMode gameMode = org.bukkit.GameMode.valueOf(gameModeName.toUpperCase());
                return player.getGameMode() == gameMode;
            } catch (IllegalArgumentException e) {
                return false;
            }
        };
    }
}