package me.leon.scheduler.condition;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.World;

import me.leon.scheduler.api.Condition;

/**
 * Provides condition implementations related to time.
 * These conditions check real-world time and in-game time.
 */
public final class TimeCondition {

    private TimeCondition() {
        // Private constructor to prevent instantiation
    }

    /**
     * Creates a condition that is met during a specific time range in the Minecraft world.
     *
     * @param worldName The name of the world
     * @param startTick The starting tick of the day (0-24000)
     * @param endTick The ending tick of the day (0-24000)
     * @return A condition that checks world time
     */
    public static Condition worldTimeBetween(String worldName, int startTick, int endTick) {
        return () -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return false;
            }

            long time = world.getTime();

            if (startTick <= endTick) {
                return time >= startTick && time <= endTick;
            } else {
                // Handle wrap-around (e.g., 18000-6000 meaning evening to morning)
                return time >= startTick || time <= endTick;
            }
        };
    }

    /**
     * Creates a condition that is met during daytime in the Minecraft world.
     *
     * @param worldName The name of the world
     * @return A condition that checks if it's day in the world
     */
    public static Condition isDayInWorld(String worldName) {
        // Minecraft day is between tick 0 and 12000
        return worldTimeBetween(worldName, 0, 12000);
    }

    /**
     * Creates a condition that is met during nighttime in the Minecraft world.
     *
     * @param worldName The name of the world
     * @return A condition that checks if it's night in the world
     */
    public static Condition isNightInWorld(String worldName) {
        // Minecraft night is between tick 13000 and 24000
        return worldTimeBetween(worldName, 13000, 24000);
    }

    /**
     * Creates a condition that is met during sunrise in the Minecraft world.
     *
     * @param worldName The name of the world
     * @return A condition that checks if it's sunrise in the world
     */
    public static Condition isSunriseInWorld(String worldName) {
        // Sunrise is approximately between tick 23000 and 1000
        return worldTimeBetween(worldName, 23000, 1000);
    }

    /**
     * Creates a condition that is met during sunset in the Minecraft world.
     *
     * @param worldName The name of the world
     * @return A condition that checks if it's sunset in the world
     */
    public static Condition isSunsetInWorld(String worldName) {
        // Sunset is approximately between tick 11000 and 13000
        return worldTimeBetween(worldName, 11000, 13000);
    }

    /**
     * Creates a condition that is met when it's raining in the Minecraft world.
     *
     * @param worldName The name of the world
     * @return A condition that checks if it's raining in the world
     */
    public static Condition isRainingInWorld(String worldName) {
        return () -> {
            World world = Bukkit.getWorld(worldName);
            return world != null && world.hasStorm();
        };
    }

    /**
     * Creates a condition that is met when there's a thunderstorm in the Minecraft world.
     *
     * @param worldName The name of the world
     * @return A condition that checks if there's a thunderstorm in the world
     */
    public static Condition isThunderingInWorld(String worldName) {
        return () -> {
            World world = Bukkit.getWorld(worldName);
            return world != null && world.isThundering();
        };
    }

    /**
     * Creates a condition that is met during a specific real-world time range.
     *
     * @param startHour The starting hour (0-23)
     * @param startMinute The starting minute (0-59)
     * @param endHour The ending hour (0-23)
     * @param endMinute The ending minute (0-59)
     * @param zoneId The time zone ID (e.g., "UTC", "America/New_York")
     * @return A condition that checks real-world time
     */
    public static Condition realTimeBetween(int startHour, int startMinute,
                                            int endHour, int endMinute, String zoneId) {
        return () -> {
            try {
                LocalTime start = LocalTime.of(startHour, startMinute);
                LocalTime end = LocalTime.of(endHour, endMinute);
                LocalTime now = LocalTime.now(ZoneId.of(zoneId));

                if (start.isBefore(end)) {
                    return !now.isBefore(start) && !now.isAfter(end);
                } else {
                    // Handle wrap-around (e.g., 22:00-06:00 meaning evening to morning)
                    return !now.isBefore(start) || !now.isAfter(end);
                }
            } catch (Exception e) {
                // If there's an error (like invalid zone ID), the condition is not met
                return false;
            }
        };
    }

    /**
     * Creates a condition that is met on specific days of the week.
     *
     * @param days The days of the week
     * @return A condition that checks if the current day is one of the specified days
     */
    public static Condition onDaysOfWeek(DayOfWeek... days) {
        return () -> {
            DayOfWeek today = LocalDate.now().getDayOfWeek();

            for (DayOfWeek day : days) {
                if (today == day) {
                    return true;
                }
            }

            return false;
        };
    }

    /**
     * Creates a condition that is met on weekdays (Monday to Friday).
     *
     * @return A condition that checks if the current day is a weekday
     */
    public static Condition onWeekdays() {
        return onDaysOfWeek(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY
        );
    }

    /**
     * Creates a condition that is met on weekends (Saturday and Sunday).
     *
     * @return A condition that checks if the current day is a weekend
     */
    public static Condition onWeekends() {
        return onDaysOfWeek(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    /**
     * Creates a condition that is met after a specified delay.
     *
     * @param delay The delay
     * @param unit The time unit of the delay
     * @return A condition that becomes true after the specified delay
     */
    public static Condition afterDelay(long delay, TimeUnit unit) {
        final long[] targetTime = new long[1];

        return () -> {
            if (targetTime[0] == 0) {
                targetTime[0] = System.currentTimeMillis() + unit.toMillis(delay);
                return false;
            }

            return System.currentTimeMillis() >= targetTime[0];
        };
    }

    /**
     * Creates a condition that is met only once at the specified interval.
     *
     * @param interval The interval in minutes
     * @return A condition that becomes true at each interval
     */
    public static Condition everyInterval(int interval) {
        final long[] lastCheckTime = new long[1];
        final boolean[] result = new boolean[1];

        return () -> {
            long now = System.currentTimeMillis();
            long intervalMillis = interval * 60 * 1000L;

            if (lastCheckTime[0] == 0) {
                lastCheckTime[0] = now;
                return false;
            }

            if (now - lastCheckTime[0] >= intervalMillis) {
                if (!result[0]) {
                    result[0] = true;
                    return true;
                }
            } else {
                result[0] = false;
            }

            return false;
        };
    }

    /**
     * Creates a condition that is met on a specific date.
     *
     * @param year The year
     * @param month The month (1-12)
     * @param day The day of the month
     * @return A condition that checks if the current date matches the specified date
     */
    public static Condition onDate(int year, int month, int day) {
        return () -> {
            LocalDate target = LocalDate.of(year, month, day);
            LocalDate now = LocalDate.now();

            return now.equals(target);
        };
    }

    /**
     * Creates a condition that is met on a specific day of every month.
     *
     * @param day The day of the month (1-31)
     * @return A condition that checks if the current day of month is the specified day
     */
    public static Condition onDayOfMonth(int day) {
        return () -> {
            Calendar cal = Calendar.getInstance();
            return cal.get(Calendar.DAY_OF_MONTH) == day;
        };
    }
}