package com.badwolfmc.rolerewards.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ConfigManager {
    private static final Pattern REWARD_ID = Pattern.compile("[a-z0-9_-]+");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);

    private final JavaPlugin plugin;
    private volatile RoleRewardsConfig current;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public RoleRewardsConfig loadInitial() {
        plugin.saveDefaultConfig();
        RoleRewardsConfig loaded = parse(plugin.getConfig());
        this.current = loaded;
        return loaded;
    }

    public RoleRewardsConfig reload() {
        plugin.reloadConfig();
        RoleRewardsConfig loaded = parse(plugin.getConfig());
        this.current = loaded;
        return loaded;
    }

    public RoleRewardsConfig current() {
        RoleRewardsConfig snapshot = current;
        if (snapshot == null) {
            throw new IllegalStateException("Configuration has not been loaded yet");
        }
        return snapshot;
    }

    private RoleRewardsConfig parse(FileConfiguration config) {
        String timezone = config.getString("timezone", "America/New_York");
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid timezone: " + timezone, ex);
        }

        int checkMinutes = config.getInt("scheduler-check-minutes", 5);
        if (checkMinutes < 1 || checkMinutes > 60) {
            throw new IllegalArgumentException("scheduler-check-minutes must be between 1 and 60");
        }

        ConfigurationSection rewardsSection = config.getConfigurationSection("rewards");
        if (rewardsSection == null || rewardsSection.getKeys(false).isEmpty()) {
            throw new IllegalArgumentException("At least one reward must be configured under rewards");
        }

        Map<String, RewardDefinition> rewards = new LinkedHashMap<>();
        for (String rawId : rewardsSection.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            if (!REWARD_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid reward id '" + rawId + "'; use a-z, 0-9, _ or -");
            }
            if (rewards.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate reward id after case normalization: '" + rawId + "'");
            }

            ConfigurationSection section = rewardsSection.getConfigurationSection(rawId);
            if (section == null) {
                throw new IllegalArgumentException("Reward '" + rawId + "' must be a configuration section");
            }

            String group = section.getString("group");
            if (group == null || group.isBlank()) {
                throw new IllegalArgumentException("Reward '" + id + "' requires a LuckPerms group");
            }
            group = group.trim();

            boolean directOnly = section.getBoolean("membership.direct-only", true);
            if (!directOnly) {
                throw new IllegalArgumentException("Reward '" + id + "': RoleRewards v1 supports direct LuckPerms membership only");
            }

            boolean enabled = section.getBoolean("schedule.enabled", false);
            int day = section.getInt("schedule.day-of-month", 1);
            if (day < 1 || day > 31) {
                throw new IllegalArgumentException("Reward '" + id + "': schedule.day-of-month must be between 1 and 31");
            }

            String rawTime = section.getString("schedule.time", "22:00");
            LocalTime time;
            try {
                time = LocalTime.parse(rawTime, TIME_FORMAT);
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("Reward '" + id + "': schedule.time must use 24-hour HH:mm format", ex);
            }

            List<String> commands = new ArrayList<>(section.getStringList("commands"));
            commands.removeIf(String::isBlank);
            if (commands.isEmpty()) {
                throw new IllegalArgumentException("Reward '" + id + "' must configure at least one console command");
            }
            for (String command : commands) {
                String dispatchable = command.trim();
                while (dispatchable.startsWith("/")) {
                    dispatchable = dispatchable.substring(1).trim();
                }
                if (dispatchable.isBlank()) {
                    throw new IllegalArgumentException("Reward '" + id + "' contains an empty console command");
                }
            }

            rewards.put(id, new RewardDefinition(
                    id,
                    group,
                    directOnly,
                    enabled,
                    day,
                    time,
                    List.copyOf(commands)
            ));
        }

        return new RoleRewardsConfig(zoneId, checkMinutes, rewards);
    }
}
