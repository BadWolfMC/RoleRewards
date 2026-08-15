package com.badwolfmc.rolerewards.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final Path file;
    private volatile RoleRewardsConfig current;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.file = plugin.getDataFolder().toPath().resolve("config.yml");
    }

    public InitialLoad prepareInitial() {
        YamlConfiguration bundled = YamlFileIO.loadResource(plugin, "config.yml");
        ConfigSchema.requireBundledCurrent(bundled);

        boolean existed = Files.exists(file);
        YamlConfiguration installed = existed ? YamlFileIO.load(file, "config.yml") : bundled;
        SchemaMigrationResult migration = ConfigSchema.migrateForStartup(installed);
        RoleRewardsConfig parsed = parse(migration.configuration());

        YamlFileIO.PendingWrite pendingWrite = null;
        if (!existed || migration.changed()) {
            pendingWrite = YamlFileIO.pendingWrite(
                    file,
                    migration.configuration().saveToString(),
                    existed,
                    migration.fromVersion(),
                    migration.toVersion()
            );
        }
        return new InitialLoad(parsed, migration, pendingWrite, existed);
    }

    public void commitInitial(InitialLoad initial) {
        Objects.requireNonNull(initial, "initial");
        if (initial.pendingWrite() == null) {
            return;
        }
        try {
            var backup = initial.pendingWrite().commit();
            if (!initial.existed()) {
                plugin.getLogger().info("Created config.yml at schema version " + ConfigSchema.CURRENT_VERSION + ".");
            } else {
                long addedDefaults = initial.migration().changes().stream()
                        .filter(change -> change.startsWith("added "))
                        .count();
                String details = addedDefaults > 0
                        ? "; added " + addedDefaults + " missing schema default(s)"
                        : "";
                plugin.getLogger().info(
                        "Updated config.yml schema from " + initial.migration().fromVersion() + " to "
                                + initial.migration().toVersion() + details + formatBackup(backup.orElse(null))
                );
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not safely write upgraded config.yml", ex);
        }
    }

    public RoleRewardsConfig loadCandidate() {
        YamlConfiguration loaded = YamlFileIO.load(file, "config.yml");
        ConfigSchema.requireCurrent(loaded);
        return parse(loaded);
    }

    public void apply(RoleRewardsConfig loaded) {
        this.current = Objects.requireNonNull(loaded, "loaded");
    }

    public RoleRewardsConfig reload() {
        RoleRewardsConfig loaded = loadCandidate();
        apply(loaded);
        return loaded;
    }

    public RoleRewardsConfig current() {
        RoleRewardsConfig snapshot = current;
        if (snapshot == null) {
            throw new IllegalStateException("Configuration has not been loaded yet");
        }
        return snapshot;
    }

    static RoleRewardsConfig parse(FileConfiguration config) {
        String timezone = optionalString(config, "timezone", "America/New_York", "timezone");
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid timezone: " + timezone, ex);
        }

        int checkMinutes = optionalInt(config, "scheduler-check-minutes", 5, "scheduler-check-minutes");
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

            String group = requiredString(section, "group", "Reward '" + id + "': group").trim();
            if (group.isBlank()) {
                throw new IllegalArgumentException("Reward '" + id + "' requires a LuckPerms group");
            }

            boolean directOnly = optionalBoolean(
                    section,
                    "membership.direct-only",
                    true,
                    "Reward '" + id + "': membership.direct-only"
            );
            if (!directOnly) {
                throw new IllegalArgumentException("Reward '" + id + "': RoleRewards v1 supports direct LuckPerms membership only");
            }

            boolean enabled = optionalBoolean(
                    section,
                    "schedule.enabled",
                    false,
                    "Reward '" + id + "': schedule.enabled"
            );
            int day = optionalInt(
                    section,
                    "schedule.day-of-month",
                    1,
                    "Reward '" + id + "': schedule.day-of-month"
            );
            if (day < 1 || day > 31) {
                throw new IllegalArgumentException("Reward '" + id + "': schedule.day-of-month must be between 1 and 31");
            }

            String rawTime = optionalString(
                    section,
                    "schedule.time",
                    "22:00",
                    "Reward '" + id + "': schedule.time"
            );
            LocalTime time;
            try {
                time = LocalTime.parse(rawTime, TIME_FORMAT);
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("Reward '" + id + "': schedule.time must use 24-hour HH:mm format", ex);
            }

            List<String> commands = requiredStringList(
                    section,
                    "commands",
                    "Reward '" + id + "': commands"
            );
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

    private static String requiredString(ConfigurationSection section, String path, String displayName) {
        Object raw = section.get(path);
        if (raw == null) {
            throw new IllegalArgumentException(displayName + " is required");
        }
        if (!(raw instanceof String value)) {
            throw new IllegalArgumentException(displayName + " must be a string");
        }
        return value;
    }

    private static String optionalString(
            ConfigurationSection section,
            String path,
            String defaultValue,
            String displayName
    ) {
        Object raw = section.get(path);
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof String value)) {
            throw new IllegalArgumentException(displayName + " must be a string");
        }
        return value;
    }

    private static boolean optionalBoolean(
            ConfigurationSection section,
            String path,
            boolean defaultValue,
            String displayName
    ) {
        Object raw = section.get(path);
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Boolean value)) {
            throw new IllegalArgumentException(displayName + " must be true or false");
        }
        return value;
    }

    private static int optionalInt(
            ConfigurationSection section,
            String path,
            int defaultValue,
            String displayName
    ) {
        Object raw = section.get(path);
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)) {
            throw new IllegalArgumentException(displayName + " must be an integer");
        }
        long value = ((Number) raw).longValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(displayName + " is outside the supported integer range");
        }
        return (int) value;
    }

    private static List<String> requiredStringList(
            ConfigurationSection section,
            String path,
            String displayName
    ) {
        Object raw = section.get(path);
        if (raw == null) {
            throw new IllegalArgumentException(displayName + " is required");
        }
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException(displayName + " must be a YAML list of strings");
        }

        List<String> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (!(value instanceof String command)) {
                throw new IllegalArgumentException(displayName + " entry " + (index + 1) + " must be a string");
            }
            result.add(command);
        }
        return result;
    }

    private String formatBackup(Path backup) {
        if (backup == null) {
            return ".";
        }
        return " (backup: " + backup.getFileName() + ").";
    }

    public record InitialLoad(
            RoleRewardsConfig config,
            SchemaMigrationResult migration,
            YamlFileIO.PendingWrite pendingWrite,
            boolean existed
    ) {
        public InitialLoad {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(migration, "migration");
        }
    }
}
